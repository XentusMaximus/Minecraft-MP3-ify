package de.vibecoded.mp3player.client.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;

/**
 * Manages a single streaming OpenAL source and reusable buffer pool.
 * Must only be invoked from the Minecraft Client Main Thread.
 */
public final class OpenAlStreamChannel implements AutoCloseable {
	private static final int AL_SOURCE_DISTANCE_MODEL = 0xD000;
	private static final int MAX_QUEUED_BUFFERS = 10; // ~0.83s smooth OpenAL buffer window

	private int sourceId = 0;
	private boolean positional3D;
	private float posX;
	private float posY;
	private float posZ;

	private final Deque<Integer> freeBuffers = new ArrayDeque<>();
	private final List<Integer> allBuffers = new ArrayList<>();
	private final Map<Integer, BufferMeta> queuedMeta = new HashMap<>();

	private long completedFrames = 0L;
	private int lastSampleRate = 44100;
	private float currentVisualizerLevel = 0.0f;
	private boolean eosReceived = false;
	private boolean closed = false;

	private record BufferMeta(int frameCount, int sampleRate, float rmsLevel) {
	}

	public OpenAlStreamChannel(boolean positional3D) {
		this.positional3D = positional3D;
	}

	public void setPositional3D(boolean positional3D) {
		if (this.positional3D != positional3D) {
			this.positional3D = positional3D;
			if (sourceId != 0) {
				applySpatialMode();
			}
		}
	}

	public void setWorldPosition(double x, double y, double z) {
		this.posX = (float) x;
		this.posY = (float) y;
		this.posZ = (float) z;
		if (sourceId != 0 && positional3D) {
			AL10.alSource3f(sourceId, AL10.AL_POSITION, posX, posY, posZ);
		}
	}

	public void updateGainNow(float effectiveGain) {
		if (!closed && sourceId != 0 && ALC10.alcGetCurrentContext() != 0L) {
			AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, Math.min(1.0f, effectiveGain)));
		}
	}

	public boolean pump(Queue<PcmChunk> pcmQueue, boolean paused, float effectiveGain, int minPrebufferChunks) {
		return pump(pcmQueue, paused, effectiveGain, minPrebufferChunks, null);
	}

	/**
	 * Unqueues finished buffers, queues newly decoded PCM buffers, invokes optional tap listener
	 * (used to feed 3D Opus network streaming in 1:1 lockstep with OpenAL playback),
	 * updates gain/pause state, and returns true when playback has completely finished.
	 */
	public boolean pump(
			Queue<PcmChunk> pcmQueue,
			boolean paused,
			float effectiveGain,
			int minPrebufferChunks,
			Consumer<PcmChunk> onChunkQueued
	) {
		if (closed || ALC10.alcGetCurrentContext() == 0L) {
			return false;
		}

		if (sourceId == 0) {
			if (pcmQueue.isEmpty()) {
				return false;
			}
			sourceId = AL10.alGenSources();
			if (AL10.alGetError() != AL10.AL_NO_ERROR || sourceId == 0) {
				sourceId = 0;
				return false;
			}
			AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f);
			AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
			applySpatialMode();
		}

		AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, Math.min(1.0f, effectiveGain)));

		// 1. Reclaim finished OpenAL buffers
		int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
		float latestRms = currentVisualizerLevel * 0.82f;
		for (int i = 0; i < processed; i++) {
			int bufId = AL10.alSourceUnqueueBuffers(sourceId);
			if (bufId != 0) {
				BufferMeta meta = queuedMeta.remove(bufId);
				if (meta != null) {
					completedFrames += meta.frameCount();
					lastSampleRate = meta.sampleRate();
					latestRms = Math.max(latestRms, meta.rmsLevel());
				}
				freeBuffers.push(bufId);
			}
		}
		currentVisualizerLevel = paused ? (currentVisualizerLevel * 0.7f) : latestRms;

		// 2. Queue newly decoded PCM chunks up to MAX_QUEUED_BUFFERS
		int currentlyQueued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
		while (currentlyQueued < MAX_QUEUED_BUFFERS && !eosReceived) {
			PcmChunk chunk = pcmQueue.poll();
			if (chunk == null) {
				break;
			}
			if (chunk.endOfStream()) {
				eosReceived = true;
				if (onChunkQueued != null) {
					onChunkQueued.accept(chunk);
				}
				break;
			}
			if (chunk.samples().length == 0) {
				continue;
			}

			int bufId = acquireBuffer();
			if (bufId == 0) {
				break;
			}
			int alFormat = (chunk.channels() == 2 && !positional3D)
					? AL10.AL_FORMAT_STEREO16
					: AL10.AL_FORMAT_MONO16;

			short[] dataToUpload = chunk.samples();
			if (chunk.channels() == 2 && positional3D) {
				// Downmix stereo to mono on-the-fly if switched to 3D mode
				short[] mono = new short[dataToUpload.length / 2];
				for (int i = 0, j = 0; i + 1 < dataToUpload.length; i += 2, j++) {
					mono[j] = (short) (((int) dataToUpload[i] + (int) dataToUpload[i + 1]) >> 1);
				}
				dataToUpload = mono;
			}

			AL10.alBufferData(bufId, alFormat, dataToUpload, chunk.sampleRate());
			if (AL10.alGetError() == AL10.AL_NO_ERROR) {
				AL10.alSourceQueueBuffers(sourceId, bufId);
				queuedMeta.put(bufId, new BufferMeta(chunk.frameCount(), chunk.sampleRate(), chunk.rmsLevel()));
				lastSampleRate = chunk.sampleRate();
				if (currentlyQueued == 0) {
					currentVisualizerLevel = Math.max(currentVisualizerLevel, chunk.rmsLevel());
				}
				currentlyQueued++;
				if (onChunkQueued != null) {
					onChunkQueued.accept(chunk);
				}
			} else {
				freeBuffers.push(bufId);
				break;
			}
		}

		// 3. Control Play / Pause state
		int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
		if (paused) {
			if (state == AL10.AL_PLAYING) {
				AL10.alSourcePause(sourceId);
			}
		} else {
			if (state != AL10.AL_PLAYING && currentlyQueued > 0) {
				if (eosReceived || state == AL10.AL_PAUSED || currentlyQueued >= Math.max(1, minPrebufferChunks)) {
					AL10.alSourcePlay(sourceId);
				}
			}
		}

		if (eosReceived && currentlyQueued == 0) {
			int finalState = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
			return finalState != AL10.AL_PLAYING && finalState != AL10.AL_PAUSED;
		}
		return false;
	}

	public double elapsedSeconds() {
		long extraSampleOffset = 0L;
		if (sourceId != 0 && ALC10.alcGetCurrentContext() != 0L) {
			int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
			if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
				extraSampleOffset = AL10.alGetSourcei(sourceId, AL11.AL_SAMPLE_OFFSET);
			}
		}
		return (completedFrames + Math.max(0L, extraSampleOffset)) / (double) Math.max(1, lastSampleRate);
	}

	public float visualizerLevel() {
		return currentVisualizerLevel;
	}

	private int acquireBuffer() {
		if (!freeBuffers.isEmpty()) {
			return freeBuffers.pop();
		}
		int id = AL10.alGenBuffers();
		if (AL10.alGetError() != AL10.AL_NO_ERROR || id == 0) {
			return 0;
		}
		allBuffers.add(id);
		return id;
	}

	private void applySpatialMode() {
		if (positional3D) {
			AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
			AL10.alSourcei(sourceId, AL_SOURCE_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
			AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 4.0f);
			AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 54.0f);
			AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
			AL10.alSource3f(sourceId, AL10.AL_POSITION, posX, posY, posZ);
		} else {
			AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
			AL10.alSourcei(sourceId, AL_SOURCE_DISTANCE_MODEL, AL10.AL_NONE);
			AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0.0f);
			AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (ALC10.alcGetCurrentContext() != 0L) {
			if (sourceId != 0) {
				AL10.alSourceStop(sourceId);
				AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
				AL10.alDeleteSources(sourceId);
				sourceId = 0;
			}
			for (int bufId : allBuffers) {
				if (bufId != 0) {
					AL10.alDeleteBuffers(bufId);
				}
			}
		}
		allBuffers.clear();
		freeBuffers.clear();
		queuedMeta.clear();
	}
}

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

	public static final float DEFAULT_REF_DISTANCE = 4.0f;
	public static final float DEFAULT_MAX_DISTANCE = 54.0f;

	// Extended 32x32x32 (±32 in X, Y, Z) speaker range parameters
	public static final float SPEAKER_REF_DISTANCE = 18.0f;
	public static final float SPEAKER_MAX_DISTANCE = 76.0f;

	private int sourceId = 0;
	private boolean positional3D;
	private final float refDistance;
	private final float maxDistance;
	private float posX;
	private float posY;
	private float posZ;

	private final Deque<Integer> freeBuffers = new ArrayDeque<>();
	private final List<Integer> allBuffers = new ArrayList<>();
	private final Map<Integer, BufferMeta> queuedMeta = new HashMap<>();
	private final Deque<PcmChunk> queuedChunks = new ArrayDeque<>();

	private long completedFrames = 0L;
	private long queuedFramesCount = 0L;
	private int lastSampleRate = 44100;
	private float currentVisualizerLevel = 0.0f;
	private boolean eosReceived = false;
	private boolean closed = false;

	private record BufferMeta(int frameCount, int sampleRate, float rmsLevel, PcmChunk chunk) {
	}

	public OpenAlStreamChannel(boolean positional3D) {
		this(positional3D, DEFAULT_REF_DISTANCE, DEFAULT_MAX_DISTANCE);
	}

	public OpenAlStreamChannel(boolean positional3D, float refDistance, float maxDistance) {
		this.positional3D = positional3D;
		this.refDistance = refDistance;
		this.maxDistance = maxDistance;
	}

	public static OpenAlStreamChannel createSpeakerChannel() {
		return new OpenAlStreamChannel(true, SPEAKER_REF_DISTANCE, SPEAKER_MAX_DISTANCE);
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
		reclaimProcessedBuffers(paused);

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

			if (uploadAndQueueChunk(chunk, currentlyQueued == 0)) {
				currentlyQueued++;
				if (onChunkQueued != null) {
					onChunkQueued.accept(chunk);
				}
			} else {
				break;
			}
		}

		// 3. Control Play / Pause state
		updatePlayState(paused, currentlyQueued, minPrebufferChunks);

		if (eosReceived && currentlyQueued == 0) {
			int finalState = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
			return finalState != AL10.AL_PLAYING && finalState != AL10.AL_PAUSED;
		}
		return false;
	}

	/**
	 * Synchronizes and pumps a linked 3D speaker channel in sample-accurate lockstep
	 * with the given {@code master} channel. When a speaker is linked or enabled mid-playback,
	 * it immediately receives the master channel's active OpenAL buffer queue and snaps to
	 * the exact current sample offset so all speakers play without echo or delay.
	 */
	public void pumpFollower(
			OpenAlStreamChannel master,
			Queue<PcmChunk> inbox,
			boolean paused,
			float effectiveGain
	) {
		if (closed || master == null || master.closed || ALC10.alcGetCurrentContext() == 0L) {
			return;
		}
		if (master.sourceId == 0 || master.queuedChunks.isEmpty()) {
			return;
		}

		if (sourceId == 0) {
			inbox.clear();
			syncFromMaster(master, paused, effectiveGain);
			return;
		}

		AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, Math.min(1.0f, effectiveGain)));

		// 1. Reclaim finished OpenAL buffers on this speaker
		reclaimProcessedBuffers(paused);

		// 2. Queue newly arrived delta chunks from master
		int currentlyQueued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
		while (currentlyQueued < MAX_QUEUED_BUFFERS && !eosReceived) {
			PcmChunk chunk = inbox.poll();
			if (chunk == null) {
				break;
			}
			if (chunk.endOfStream()) {
				eosReceived = true;
				break;
			}
			if (chunk.samples().length == 0) {
				continue;
			}
			if (uploadAndQueueChunk(chunk, currentlyQueued == 0)) {
				currentlyQueued++;
			} else {
				break;
			}
		}

		// 3. Control Play / Pause state
		updatePlayState(paused, currentlyQueued, 1);

		// 4. Verify sample-level synchronization with master channel
		long masterTotalFrames = master.totalPlayedFrames();
		long followerTotalFrames = this.totalPlayedFrames();
		long driftFrames = Math.abs(masterTotalFrames - followerTotalFrames);

		if (driftFrames > 192L) { // > ~4ms drift
			long targetOffsetInQueue = masterTotalFrames - this.completedFrames;
			if (targetOffsetInQueue >= 0L && targetOffsetInQueue < this.queuedFramesCount && currentlyQueued > 0) {
				AL10.alSourcei(sourceId, AL11.AL_SAMPLE_OFFSET, (int) targetOffsetInQueue);
			} else {
				inbox.clear();
				syncFromMaster(master, paused, effectiveGain);
			}
		}
	}

	/**
	 * Clones the master channel's currently queued OpenAL buffers and snaps the playback
	 * cursor to the master's exact sample offset.
	 */
	public void syncFromMaster(OpenAlStreamChannel master, boolean paused, float effectiveGain) {
		if (closed || master == null || master.closed || master.sourceId == 0 || ALC10.alcGetCurrentContext() == 0L) {
			return;
		}

		if (sourceId == 0) {
			sourceId = AL10.alGenSources();
			if (AL10.alGetError() != AL10.AL_NO_ERROR || sourceId == 0) {
				sourceId = 0;
				return;
			}
			AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f);
			AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
			applySpatialMode();
		} else {
			AL10.alSourceStop(sourceId);
			int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
			for (int i = 0; i < queued; i++) {
				int bufId = AL10.alSourceUnqueueBuffers(sourceId);
				if (bufId != 0) {
					freeBuffers.push(bufId);
				}
			}
			AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
			queuedMeta.clear();
			queuedChunks.clear();
			queuedFramesCount = 0L;
		}

		AL10.alSourcef(sourceId, AL10.AL_GAIN, Math.max(0.0f, Math.min(1.0f, effectiveGain)));

		this.completedFrames = master.completedFrames;
		this.lastSampleRate = master.lastSampleRate;
		this.eosReceived = master.eosReceived;

		int queuedCount = 0;
		for (PcmChunk chunk : master.queuedChunks) {
			if (chunk != null && !chunk.endOfStream() && chunk.samples().length > 0) {
				if (uploadAndQueueChunk(chunk, queuedCount == 0)) {
					queuedCount++;
				}
			}
		}

		if (queuedCount == 0) {
			return;
		}

		int masterOffset = master.currentSampleOffset();
		int clampedOffset = (int) Math.max(0L, Math.min(Math.max(0L, queuedFramesCount - 1L), masterOffset));
		AL10.alSourcei(sourceId, AL11.AL_SAMPLE_OFFSET, clampedOffset);

		if (!paused) {
			AL10.alSourcePlay(sourceId);
			int postPlayMasterOffset = master.currentSampleOffset();
			int postClamped = (int) Math.max(0L, Math.min(Math.max(0L, queuedFramesCount - 1L), postPlayMasterOffset));
			AL10.alSourcei(sourceId, AL11.AL_SAMPLE_OFFSET, postClamped);
		}
	}

	private void reclaimProcessedBuffers(boolean paused) {
		int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
		float latestRms = currentVisualizerLevel * 0.82f;
		for (int i = 0; i < processed; i++) {
			int bufId = AL10.alSourceUnqueueBuffers(sourceId);
			if (bufId != 0) {
				BufferMeta meta = queuedMeta.remove(bufId);
				queuedChunks.pollFirst();
				if (meta != null) {
					completedFrames += meta.frameCount();
					queuedFramesCount = Math.max(0L, queuedFramesCount - meta.frameCount());
					lastSampleRate = meta.sampleRate();
					latestRms = Math.max(latestRms, meta.rmsLevel());
				}
				freeBuffers.push(bufId);
			}
		}
		currentVisualizerLevel = paused ? (currentVisualizerLevel * 0.7f) : latestRms;
	}

	private boolean uploadAndQueueChunk(PcmChunk chunk, boolean firstInQueue) {
		int bufId = acquireBuffer();
		if (bufId == 0) {
			return false;
		}
		int alFormat = (chunk.channels() == 2 && !positional3D)
				? AL10.AL_FORMAT_STEREO16
				: AL10.AL_FORMAT_MONO16;

		short[] dataToUpload = (chunk.channels() == 2 && positional3D)
				? chunk.monoSamples()
				: chunk.samples();

		AL10.alBufferData(bufId, alFormat, dataToUpload, chunk.sampleRate());
		if (AL10.alGetError() == AL10.AL_NO_ERROR) {
			AL10.alSourceQueueBuffers(sourceId, bufId);
			int frames = chunk.frameCount();
			queuedMeta.put(bufId, new BufferMeta(frames, chunk.sampleRate(), chunk.rmsLevel(), chunk));
			queuedChunks.addLast(chunk);
			queuedFramesCount += frames;
			lastSampleRate = chunk.sampleRate();
			if (firstInQueue) {
				currentVisualizerLevel = Math.max(currentVisualizerLevel, chunk.rmsLevel());
			}
			return true;
		} else {
			freeBuffers.push(bufId);
			return false;
		}
	}

	private void updatePlayState(boolean paused, int currentlyQueued, int minPrebufferChunks) {
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
	}

	public int currentSampleOffset() {
		if (sourceId != 0 && ALC10.alcGetCurrentContext() != 0L) {
			int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
			if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
				return Math.max(0, AL10.alGetSourcei(sourceId, AL11.AL_SAMPLE_OFFSET));
			}
		}
		return 0;
	}

	public long totalPlayedFrames() {
		return completedFrames + currentSampleOffset();
	}

	public double elapsedSeconds() {
		return totalPlayedFrames() / (double) Math.max(1, lastSampleRate);
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
			AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, refDistance);
			AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, maxDistance);
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
		queuedChunks.clear();
		queuedFramesCount = 0L;
	}
}

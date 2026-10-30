package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.decoder.AudioTrackDecoder;
import de.vibecoded.mp3player.client.audio.decoder.AudioTrackDecoder.DecodedPcm;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous multi-format (MP3, M4A, OPUS, WAV, FLAC, OGG) -> 16-bit PCM decoder thread.
 * Performs zero OpenAL calls on this thread; pushes ~83ms PCM chunks into a bounded queue
 * consumed on the Minecraft Client Main Thread.
 */
public final class Mp3DecodeWorker {
	private static final int QUEUE_CAPACITY = 18; // ~1.5s max buffered PCM

	private final BlockingQueue<PcmChunk> outputQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AudioTrackDecoder trackDecoder;
	private final boolean forceMono;
	private final Thread workerThread;

	public Mp3DecodeWorker(String name, AudioTrackDecoder trackDecoder, boolean forceMono) {
		this.trackDecoder = trackDecoder;
		this.forceMono = forceMono;
		this.workerThread = new Thread(this::runDecodeLoop, "Audio-Decoder-" + name);
		this.workerThread.setDaemon(true);
	}

	public void start() {
		workerThread.start();
	}

	public BlockingQueue<PcmChunk> outputQueue() {
		return outputQueue;
	}

	public void cancel() {
		if (cancelled.compareAndSet(false, true)) {
			outputQueue.clear();
			try {
				trackDecoder.close();
			} catch (Exception ignored) {
			}
			workerThread.interrupt();
		}
	}

	private void runDecodeLoop() {
		short[] accum = null;
		int accumPos = 0;
		int currentSampleRate = 44100;
		int currentOutChannels = forceMono ? 1 : 2;
		int targetShortsPerChunk = 7350;

		try {
			while (!cancelled.get()) {
				DecodedPcm decoded = trackDecoder.decodeNext();
				if (decoded == null) {
					break; // EOF
				}

				int freq = decoded.sampleRate();
				int srcChannels = decoded.channels();
				int srcLen = decoded.length();
				short[] srcBuf = decoded.samples();

				if (srcLen <= 0 || freq <= 0 || srcChannels <= 0 || srcBuf == null) {
					continue;
				}

				int outChannels = forceMono ? 1 : Math.min(2, srcChannels);
				if (accum != null && (freq != currentSampleRate || outChannels != currentOutChannels)) {
					flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
					accum = null;
					accumPos = 0;
				}

				currentSampleRate = freq;
				currentOutChannels = outChannels;
				// Target ~83ms per OpenAL buffer across any sample rate (16 kHz .. 192 kHz)
				int targetFrames = Math.max(1024, Math.min(16384, freq / 12));
				targetShortsPerChunk = targetFrames * outChannels;

				short[] toAppend;
				int appendLen;
				if (forceMono && srcChannels == 2) {
					appendLen = srcLen / 2;
					toAppend = new short[appendLen];
					for (int i = 0, j = 0; i + 1 < srcLen; i += 2, j++) {
						toAppend[j] = (short) (((int) srcBuf[i] + (int) srcBuf[i + 1]) >> 1);
					}
				} else {
					toAppend = srcBuf;
					appendLen = Math.min(srcLen, srcBuf.length);
				}

				if (accum == null) {
					accum = new short[Math.max(targetShortsPerChunk * 2, appendLen * 2)];
				}

				int readOffset = 0;
				while (readOffset < appendLen && !cancelled.get()) {
					int space = targetShortsPerChunk - accumPos;
					if (space <= 0) {
						flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
						accumPos = 0;
						space = targetShortsPerChunk;
					}
					int copy = Math.min(space, appendLen - readOffset);
					if (accumPos + copy > accum.length) {
						short[] grown = new short[Math.max(accum.length * 2, accumPos + copy)];
						System.arraycopy(accum, 0, grown, 0, accumPos);
						accum = grown;
					}
					System.arraycopy(toAppend, readOffset, accum, accumPos, copy);
					accumPos += copy;
					readOffset += copy;

					if (accumPos >= targetShortsPerChunk) {
						flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
						accumPos = 0;
					}
				}
			}

			if (!cancelled.get() && accum != null && accumPos > 0) {
				flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
			}
		} catch (Exception e) {
			if (!cancelled.get()) {
				Mp3PlayerMod.LOGGER.debug("Audio decode stream ended: {}", e.getMessage());
			}
		} finally {
			if (!cancelled.get()) {
				offerBlocking(PcmChunk.EOS);
			}
			try {
				trackDecoder.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void flushChunk(short[] buffer, int length, int sampleRate, int channels) {
		if (length <= 0 || cancelled.get()) {
			return;
		}
		short[] slice = new short[length];
		System.arraycopy(buffer, 0, slice, 0, length);

		double sumSq = 0.0;
		int step = Math.max(1, length / 256);
		int count = 0;
		for (int i = 0; i < length; i += step) {
			double norm = slice[i] / 32768.0;
			sumSq += norm * norm;
			count++;
		}
		float rms = count > 0 ? (float) Math.min(1.0, Math.sqrt(sumSq / count) * 2.4) : 0.0f;

		offerBlocking(new PcmChunk(slice, sampleRate, channels, rms, false));
	}

	private void offerBlocking(PcmChunk chunk) {
		while (!cancelled.get()) {
			try {
				if (outputQueue.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
					return;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}

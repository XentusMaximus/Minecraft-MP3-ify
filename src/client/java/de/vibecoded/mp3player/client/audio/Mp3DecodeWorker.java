package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Asynchronous JLayer MP3 -> 16-bit PCM decoder thread.
 * Performs zero OpenAL calls on this thread; pushes ~100ms PCM chunks into a bounded queue
 * consumed on the Minecraft Client Main Thread.
 */
public final class Mp3DecodeWorker {
	private static final int FRAMES_PER_CHUNK = 4; // ~104 ms per OpenAL buffer at 44.1 kHz
	private static final int QUEUE_CAPACITY = 18;  // ~1.8s max buffered PCM

	private final BlockingQueue<PcmChunk> outputQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final InputStream sourceStream;
	private final boolean forceMono;
	private final Thread workerThread;

	public Mp3DecodeWorker(String name, InputStream sourceStream, boolean forceMono) {
		this.sourceStream = sourceStream;
		this.forceMono = forceMono;
		this.workerThread = new Thread(this::runDecodeLoop, "MP3-Decoder-" + name);
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
				sourceStream.close();
			} catch (IOException ignored) {
			}
			workerThread.interrupt();
		}
	}

	private void runDecodeLoop() {
		Bitstream bitstream = new Bitstream(sourceStream);
		Decoder decoder = new Decoder();

		short[] accum = null;
		int accumPos = 0;
		int currentSampleRate = 44100;
		int currentOutChannels = forceMono ? 1 : 2;
		int framesInAccum = 0;

		try {
			while (!cancelled.get()) {
				Header header;
				try {
					header = bitstream.readFrame();
				} catch (BitstreamException e) {
					continue;
				}
				if (header == null) {
					break; // EOF
				}

				try {
					SampleBuffer decoded = (SampleBuffer) decoder.decodeFrame(header, bitstream);
					int freq = decoded.getSampleFrequency();
					int srcChannels = decoded.getChannelCount();
					int srcLen = decoded.getBufferLength();
					short[] srcBuf = decoded.getBuffer();

					if (srcLen > 0 && freq > 0 && srcChannels > 0) {
						int outChannels = forceMono ? 1 : Math.min(2, srcChannels);
						if (accum != null && (freq != currentSampleRate || outChannels != currentOutChannels)) {
							flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
							accum = null;
							accumPos = 0;
							framesInAccum = 0;
						}

						currentSampleRate = freq;
						currentOutChannels = outChannels;

						int outSamplesNeeded = (forceMono && srcChannels == 2) ? (srcLen / 2) : srcLen;
						if (accum == null) {
							accum = new short[Math.max(outSamplesNeeded * (FRAMES_PER_CHUNK + 1), 8192)];
						} else if (accumPos + outSamplesNeeded > accum.length) {
							flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
							accumPos = 0;
							framesInAccum = 0;
							if (outSamplesNeeded > accum.length) {
								accum = new short[outSamplesNeeded * 2];
							}
						}

						if (forceMono && srcChannels == 2) {
							for (int i = 0; i + 1 < srcLen; i += 2) {
								int mixed = ((int) srcBuf[i] + (int) srcBuf[i + 1]) >> 1;
								accum[accumPos++] = (short) mixed;
							}
						} else {
							System.arraycopy(srcBuf, 0, accum, accumPos, srcLen);
							accumPos += srcLen;
						}

						framesInAccum++;
						if (framesInAccum >= FRAMES_PER_CHUNK) {
							flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
							accumPos = 0;
							framesInAccum = 0;
						}
					}
				} catch (Exception decodeErr) {
					// Skip damaged individual frame without aborting the song
				} finally {
					bitstream.closeFrame();
				}
			}

			if (!cancelled.get() && accum != null && accumPos > 0) {
				flushChunk(accum, accumPos, currentSampleRate, currentOutChannels);
			}
		} catch (Exception e) {
			if (!cancelled.get()) {
				Mp3PlayerMod.LOGGER.debug("MP3 decode stream ended: {}", e.getMessage());
			}
		} finally {
			if (!cancelled.get()) {
				offerBlocking(PcmChunk.EOS);
			}
			try {
				bitstream.close();
			} catch (BitstreamException ignored) {
			}
			try {
				sourceStream.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void flushChunk(short[] buffer, int length, int sampleRate, int channels) throws InterruptedException {
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


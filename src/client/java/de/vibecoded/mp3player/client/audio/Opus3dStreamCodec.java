package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.network.Mp3Payloads;
import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusEncoder;
import io.github.jaredmdobson.OpusException;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-Java 48 kHz Mono Opus network streaming codec (Concentus) for 3D Speaker mode.
 * Allows any local audio format (MP3, M4A, OPUS, WAV, FLAC, OGG) to be broadcast in 3D
 * at ~8 KB/s (64 kbps) inside compact <= 4 KB Essential-compatible packets, with instant
 * headerless mid-song joining and seeking.
 */
public final class Opus3dStreamCodec {
	public static final int STREAM_SAMPLE_RATE = 48000;
	public static final int FRAME_SAMPLES = 960; // 20 ms @ 48 kHz mono
	private static final int TARGET_BITRATE_BPS = 64000;
	private static final int FRAMES_PER_PACKET = 5; // 100 ms (~800 bytes per network packet)
	private static final int MAX_SINGLE_OPUS_FRAME_BYTES = 512;

	private static final byte MAGIC_0 = 'O';
	private static final byte MAGIC_1 = 'P';
	private static final byte PROTOCOL_VERSION = 1;

	private Opus3dStreamCodec() {
	}

	/**
	 * Background encoder that downmixes arbitrary local PCM chunks to mono, resamples cleanly
	 * to 48 kHz without boundary clicks, encodes 20ms Opus frames, and batches them into <= 4 KB packets.
	 */
	public static final class Opus3dBroadcaster implements AutoCloseable {
		private final BlockingQueue<PcmChunk> inboundPcm = new LinkedBlockingQueue<>(48);
		private final BlockingQueue<byte[]> outboundPackets = new LinkedBlockingQueue<>(64);
		private final AtomicBoolean closed = new AtomicBoolean(false);
		private final Thread workerThread;

		public Opus3dBroadcaster() {
			this.workerThread = new Thread(this::runEncodeLoop, "MP3-Opus3D-Broadcaster");
			this.workerThread.setDaemon(true);
			this.workerThread.start();
		}

		public void submitPcm(PcmChunk chunk) {
			if (closed.get() || chunk == null) {
				return;
			}
			if (!inboundPcm.offer(chunk)) {
				inboundPcm.poll();
				inboundPcm.offer(chunk);
			}
		}

		public byte[] pollOutboundPacket() {
			return outboundPackets.poll();
		}

		private void runEncodeLoop() {
			OpusEncoder encoder;
			try {
				encoder = new OpusEncoder(STREAM_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
				encoder.setBitrate(TARGET_BITRATE_BPS);
				encoder.setComplexity(5);
			} catch (OpusException e) {
				Mp3PlayerMod.LOGGER.error("Failed to initialize 3D OpusEncoder", e);
				return;
			}

			short[] frameBuf = new short[FRAME_SAMPLES];
			int frameFill = 0;
			byte[] opusEncodeScratch = new byte[MAX_SINGLE_OPUS_FRAME_BYTES];

			ByteArrayOutputStream batchPayload = new ByteArrayOutputStream(1200);
			int framesInBatch = 0;

			// Continuous resampler state across chunk boundaries
			int lastSrcRate = STREAM_SAMPLE_RATE;
			double resamplePos = 0.0;
			short prevMonoSample = 0;
			boolean hasPrevSample = false;

			try {
				while (!closed.get()) {
					PcmChunk chunk = inboundPcm.poll(150, TimeUnit.MILLISECONDS);
					if (chunk == null) {
						continue;
					}

					if (chunk.endOfStream()) {
						// Zero-pad final partial 20ms frame if present and flush batch
						if (frameFill > 0) {
							for (int i = frameFill; i < FRAME_SAMPLES; i++) {
								frameBuf[i] = 0;
							}
							frameFill = 0;
							framesInBatch = encodeAndAppendFrame(
									encoder, frameBuf, opusEncodeScratch, batchPayload, framesInBatch
							);
						}
						if (framesInBatch > 0) {
							emitBatch(batchPayload, framesInBatch);
							framesInBatch = 0;
						}
						continue;
					}

					short[] src = chunk.samples();
					int srcChannels = chunk.channels();
					int srcRate = chunk.sampleRate();
					if (src == null || src.length == 0 || srcChannels <= 0 || srcRate <= 0) {
						continue;
					}

					if (srcRate != lastSrcRate) {
						lastSrcRate = srcRate;
						resamplePos = 0.0;
						hasPrevSample = false;
					}

					// 1. Downmix to Mono
					int monoLen = src.length / srcChannels;
					if (monoLen <= 0) {
						continue;
					}
					short[] mono;
					if (srcChannels == 1) {
						mono = src;
					} else {
						mono = new short[monoLen];
						for (int i = 0, s = 0; i < monoLen; i++, s += srcChannels) {
							int mixed = ((int) src[s] + (int) src[s + 1]) >> 1;
							mono[i] = (short) mixed;
						}
					}

					// 2. Resample Mono -> 48000 Hz & slice into 960-sample Opus frames
					if (srcRate == STREAM_SAMPLE_RATE) {
						int offset = 0;
						while (offset < monoLen && !closed.get()) {
							int copy = Math.min(FRAME_SAMPLES - frameFill, monoLen - offset);
							System.arraycopy(mono, offset, frameBuf, frameFill, copy);
							frameFill += copy;
							offset += copy;
							if (frameFill == FRAME_SAMPLES) {
								frameFill = 0;
								framesInBatch = encodeAndAppendFrame(
										encoder, frameBuf, opusEncodeScratch, batchPayload, framesInBatch
								);
							}
						}
						prevMonoSample = mono[monoLen - 1];
						hasPrevSample = true;
					} else {
						double step = srcRate / (double) STREAM_SAMPLE_RATE;
						if (!hasPrevSample) {
							prevMonoSample = mono[0];
							hasPrevSample = true;
							resamplePos = 0.0;
						}

						while (resamplePos < monoLen - 1 && !closed.get()) {
							double interpolated;
							if (resamplePos < 0.0) {
								double frac = resamplePos + 1.0;
								interpolated = prevMonoSample + frac * (mono[0] - prevMonoSample);
							} else {
								int idx = (int) resamplePos;
								double frac = resamplePos - idx;
								interpolated = mono[idx] + frac * (mono[idx + 1] - mono[idx]);
							}

							int clamped = (int) Math.round(interpolated);
							if (clamped > 32767) {
								clamped = 32767;
							} else if (clamped < -32768) {
								clamped = -32768;
							}

							frameBuf[frameFill++] = (short) clamped;
							if (frameFill == FRAME_SAMPLES) {
								frameFill = 0;
								framesInBatch = encodeAndAppendFrame(
										encoder, frameBuf, opusEncodeScratch, batchPayload, framesInBatch
								);
							}
							resamplePos += step;
						}

						prevMonoSample = mono[monoLen - 1];
						resamplePos -= monoLen;
						if (resamplePos < -1.0) {
							resamplePos = -1.0;
						}
					}
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				if (!closed.get()) {
					Mp3PlayerMod.LOGGER.debug("3D Opus broadcaster stopped: {}", e.getMessage());
				}
			}
		}

		private int encodeAndAppendFrame(
				OpusEncoder encoder,
				short[] frameBuf,
				byte[] opusScratch,
				ByteArrayOutputStream batchPayload,
				int framesInBatch
		) {
			try {
				int encodedBytes = encoder.encode(frameBuf, 0, FRAME_SAMPLES, opusScratch, 0, opusScratch.length);
				if (encodedBytes <= 0) {
					return framesInBatch;
				}

				// Ensure packet strictly stays <= 4096 bytes (4-byte header + existing + 2-byte len + frame)
				if (framesInBatch > 0 && (4 + batchPayload.size() + 2 + encodedBytes > Mp3Payloads.MAX_CHUNK_BYTES)) {
					emitBatch(batchPayload, framesInBatch);
					framesInBatch = 0;
				}

				batchPayload.write((encodedBytes >> 8) & 0xFF);
				batchPayload.write(encodedBytes & 0xFF);
				batchPayload.write(opusScratch, 0, encodedBytes);
				framesInBatch++;

				if (framesInBatch >= FRAMES_PER_PACKET) {
					emitBatch(batchPayload, framesInBatch);
					framesInBatch = 0;
				}
			} catch (OpusException e) {
				Mp3PlayerMod.LOGGER.debug("Skipped 3D Opus frame encode error: {}", e.getMessage());
			}
			return framesInBatch;
		}

		private void emitBatch(ByteArrayOutputStream batchPayload, int frameCount) {
			byte[] framesData = batchPayload.toByteArray();
			batchPayload.reset();
			if (frameCount <= 0 || framesData.length == 0) {
				return;
			}

			byte[] packet = new byte[4 + framesData.length];
			packet[0] = MAGIC_0;
			packet[1] = MAGIC_1;
			packet[2] = PROTOCOL_VERSION;
			packet[3] = (byte) frameCount;
			System.arraycopy(framesData, 0, packet, 4, framesData.length);

			if (!outboundPackets.offer(packet)) {
				outboundPackets.poll();
				outboundPackets.offer(packet);
			}
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				inboundPcm.clear();
				outboundPackets.clear();
				workerThread.interrupt();
			}
		}
	}

	/**
	 * Background receiver-side Opus -> 48 kHz Mono 16-bit PCM decoder thread for remote 3D speakers.
	 */
	public static final class RemoteOpusDecodeWorker implements AutoCloseable {
		private final BlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>(128);
		private final BlockingQueue<PcmChunk> outputQueue = new LinkedBlockingQueue<>(24);
		private final AtomicBoolean cancelled = new AtomicBoolean(false);
		private final Thread workerThread;

		public RemoteOpusDecodeWorker(String speakerName) {
			this.workerThread = new Thread(this::runDecodeLoop, "MP3-Opus3D-Remote-" + speakerName);
			this.workerThread.setDaemon(true);
		}

		public void start() {
			workerThread.start();
		}

		public BlockingQueue<PcmChunk> outputQueue() {
			return outputQueue;
		}

		public void pushPacket(byte[] packet) {
			if (cancelled.get() || packet == null || packet.length < 6) {
				return;
			}
			if (!packetQueue.offer(packet)) {
				packetQueue.poll(); // drop oldest if severely backed up so 3D audio stays live
				packetQueue.offer(packet);
			}
		}

		private void runDecodeLoop() {
			OpusDecoder decoder;
			try {
				decoder = new OpusDecoder(STREAM_SAMPLE_RATE, 1);
			} catch (OpusException e) {
				Mp3PlayerMod.LOGGER.error("Failed to initialize remote 3D OpusDecoder", e);
				return;
			}

			short[] singleFramePcm = new short[5760];

			try {
				while (!cancelled.get()) {
					byte[] packet = packetQueue.poll(200, TimeUnit.MILLISECONDS);
					if (packet == null) {
						continue;
					}
					if (packet.length < 6 || packet[0] != MAGIC_0 || packet[1] != MAGIC_1 || packet[2] != PROTOCOL_VERSION) {
						continue;
					}

					int frameCount = packet[3] & 0xFF;
					if (frameCount <= 0 || frameCount > 32) {
						continue;
					}

					short[] batchPcm = new short[frameCount * FRAME_SAMPLES];
					int pcmWritePos = 0;
					int pos = 4;

					for (int f = 0; f < frameCount && pos + 2 <= packet.length; f++) {
						int fLen = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
						pos += 2;
						if (fLen <= 0 || pos + fLen > packet.length) {
							break;
						}

						try {
							int decoded = decoder.decode(packet, pos, fLen, singleFramePcm, 0, singleFramePcm.length, false);
							if (decoded > 0) {
								if (pcmWritePos + decoded > batchPcm.length) {
									short[] grown = new short[Math.max(batchPcm.length * 2, pcmWritePos + decoded)];
									System.arraycopy(batchPcm, 0, grown, 0, pcmWritePos);
									batchPcm = grown;
								}
								System.arraycopy(singleFramePcm, 0, batchPcm, pcmWritePos, decoded);
								pcmWritePos += decoded;
							}
						} catch (OpusException | IllegalArgumentException ignored) {
						}
						pos += fLen;
					}

					if (pcmWritePos > 0 && !cancelled.get()) {
						short[] finalSamples;
						if (pcmWritePos == batchPcm.length) {
							finalSamples = batchPcm;
						} else {
							finalSamples = new short[pcmWritePos];
							System.arraycopy(batchPcm, 0, finalSamples, 0, pcmWritePos);
						}

						float rms = computeRms(finalSamples);
						PcmChunk chunk = new PcmChunk(finalSamples, STREAM_SAMPLE_RATE, 1, rms, false);
						while (!cancelled.get()) {
							if (outputQueue.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
								break;
							}
						}
					}
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}

		private static float computeRms(short[] samples) {
			int len = samples.length;
			if (len == 0) {
				return 0.0f;
			}
			double sumSq = 0.0;
			int step = Math.max(1, len / 128);
			int count = 0;
			for (int i = 0; i < len; i += step) {
				double norm = samples[i] / 32768.0;
				sumSq += norm * norm;
				count++;
			}
			return count > 0 ? (float) Math.min(1.0, Math.sqrt(sumSq / count) * 2.4) : 0.0f;
		}

		@Override
		public void close() {
			if (cancelled.compareAndSet(false, true)) {
				packetQueue.clear();
				outputQueue.clear();
				workerThread.interrupt();
			}
		}
	}
}


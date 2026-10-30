package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;
import org.jflac.ChannelData;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.io.RandomFileInputStream;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.SeekPoint;
import org.jflac.metadata.SeekTable;
import org.jflac.metadata.StreamInfo;

/**
 * Pure-Java FLAC decoder (8/12/16/20/24/32-bit) with instant SeekTable / frame-resync seeking.
 */
public final class FlacFormatDecoder implements AudioTrackDecoder {
	private final RandomFileInputStream rfis;
	private final FLACDecoder decoder;
	private final StreamInfo streamInfo;

	public FlacFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.rfis = new RandomFileInputStream(track.file().toFile());
		try {
			this.decoder = new FLACDecoder(rfis);
			Metadata[] metas = decoder.readMetadata();
			StreamInfo si = decoder.getStreamInfo();
			SeekTable seekTable = null;
			if (metas != null) {
				for (Metadata meta : metas) {
					if (meta instanceof StreamInfo s && si == null) {
						si = s;
					} else if (meta instanceof SeekTable st) {
						seekTable = st;
					}
				}
			}
			if (si == null || si.getSampleRate() <= 0 || si.getChannels() <= 0) {
				throw new IOException("Missing FLAC StreamInfo in " + track.file());
			}
			this.streamInfo = si;

			long audioStartOffset = Math.max(0L, decoder.getTotalBytesRead());
			long fileLength = rfis.getLength();

			if (seekRatio > 0.0001 && fileLength > audioStartOffset) {
				applySeek(seekTable, audioStartOffset, fileLength, seekRatio);
			}
		} catch (IOException | RuntimeException e) {
			try {
				rfis.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
	}

	private void applySeek(SeekTable seekTable, long audioStartOffset, long fileLength, double seekRatio) throws IOException {
		long totalSamples = streamInfo.getTotalSamples();
		long targetSample = totalSamples > 0 ? (long) (totalSamples * seekRatio) : -1L;

		SeekPoint bestLower = null;
		SeekPoint bestUpper = null;

		if (seekTable != null && targetSample > 0) {
			int n = seekTable.numberOfPoints();
			for (int i = 0; i < n; i++) {
				SeekPoint sp = seekTable.getSeekPoint(i);
				if (sp == null || sp.getSampleNumber() < 0 || sp.getStreamOffset() < 0) {
					continue; // skip 0xFFFFFFFFFFFFFFFF placeholder points
				}
				if (sp.getSampleNumber() <= targetSample) {
					if (bestLower == null || sp.getSampleNumber() > bestLower.getSampleNumber()) {
						bestLower = sp;
					}
				} else {
					if (bestUpper == null || sp.getSampleNumber() < bestUpper.getSampleNumber()) {
						bestUpper = sp;
					}
				}
			}
		}

		long targetByteOffset;
		if (bestLower != null && (targetSample - bestLower.getSampleNumber()) <= streamInfo.getSampleRate() * 2L) {
			// Exact SeekTable hit within 2 seconds: jump straight to the exact FLAC frame byte!
			targetByteOffset = audioStartOffset + bestLower.getStreamOffset();
		} else if (bestLower != null || bestUpper != null) {
			long lowByte = bestLower != null ? (audioStartOffset + bestLower.getStreamOffset()) : audioStartOffset;
			long highByte = bestUpper != null ? (audioStartOffset + bestUpper.getStreamOffset()) : fileLength;
			long lowSample = bestLower != null ? bestLower.getSampleNumber() : 0L;
			long highSample = bestUpper != null ? bestUpper.getSampleNumber() : Math.max(lowSample + 1L, totalSamples);
			double localRatio = (highSample > lowSample && targetSample >= lowSample)
					? ((targetSample - lowSample) / (double) (highSample - lowSample))
					: seekRatio;
			targetByteOffset = lowByte + (long) ((highByte - lowByte) * Math.max(0.0, Math.min(0.995, localRatio)));
		} else {
			targetByteOffset = audioStartOffset + (long) ((fileLength - audioStartOffset) * seekRatio);
		}

		long clampedOffset = Math.max(audioStartOffset, Math.min(fileLength - 4L, targetByteOffset));
		rfis.seek(clampedOffset);
		decoder.getBitInputStream().reset();
	}

	@Override
	public DecodedPcm decodeNext() throws IOException {
		while (!decoder.isEOF()) {
			Frame frame = decoder.readNextFrame();
			if (frame == null || frame.header == null) {
				return null;
			}

			int blockSize = frame.header.blockSize;
			int srcChannels = frame.header.channels > 0 ? frame.header.channels : streamInfo.getChannels();
			int sampleRate = frame.header.sampleRate > 0 ? frame.header.sampleRate : streamInfo.getSampleRate();
			int bps = frame.header.bitsPerSample > 0 ? frame.header.bitsPerSample : streamInfo.getBitsPerSample();

			ChannelData[] chData = decoder.getChannelData();
			if (blockSize <= 0 || srcChannels <= 0 || chData == null) {
				continue;
			}

			int outChannels = Math.min(2, srcChannels);
			if (chData.length < outChannels || chData[0] == null || (outChannels == 2 && chData[1] == null)) {
				continue;
			}

			short[] pcm = new short[blockSize * outChannels];
			int outIdx = 0;

			if (outChannels == 1) {
				int[] c0 = chData[0].getOutput();
				int limit = Math.min(blockSize, c0.length);
				for (int i = 0; i < limit; i++) {
					pcm[outIdx++] = scaleTo16Bit(c0[i], bps);
				}
			} else {
				int[] c0 = chData[0].getOutput();
				int[] c1 = chData[1].getOutput();
				int limit = Math.min(blockSize, Math.min(c0.length, c1.length));
				for (int i = 0; i < limit; i++) {
					pcm[outIdx++] = scaleTo16Bit(c0[i], bps);
					pcm[outIdx++] = scaleTo16Bit(c1[i], bps);
				}
			}

			if (outIdx > 0) {
				return new DecodedPcm(pcm, outIdx, sampleRate, outChannels);
			}
		}
		return null;
	}

	private static short scaleTo16Bit(int sample, int bps) {
		int shifted;
		if (bps == 16) {
			shifted = sample;
		} else if (bps > 16) {
			shifted = sample >> (bps - 16);
		} else {
			shifted = sample << (16 - bps);
		}
		if (shifted > 32767) {
			return 32767;
		}
		if (shifted < -32768) {
			return -32768;
		}
		return (short) shifted;
	}

	@Override
	public void close() {
		try {
			rfis.close();
		} catch (IOException ignored) {
		}
	}
}


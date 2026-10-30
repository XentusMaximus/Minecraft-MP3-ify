package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.mp3.WavHeaderInfo;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Pure-Java streaming WAV decoder supporting 8/16/24/32-bit PCM and 32/64-bit IEEE Float,
 * with exact O(1) frame-aligned seeking.
 */
public final class WavFormatDecoder implements AudioTrackDecoder {
	private static final int FRAMES_PER_READ = 2048;

	private final WavHeaderInfo header;
	private final RandomAccessFile raf;
	private final int outChannels;
	private final int bytesPerSample;
	private final byte[] rawBuf;
	private long framesRemaining;

	public WavFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.header = WavHeaderInfo.parse(track.file());
		this.outChannels = Math.min(2, header.channels());
		this.bytesPerSample = (header.bitsPerSample() + 7) / 8;
		this.rawBuf = new byte[FRAMES_PER_READ * header.blockAlign()];

		long totalFrames = header.totalFrames();
		long startFrame = (seekRatio <= 0.0001 || totalFrames <= 0)
				? 0L
				: Math.min(totalFrames - 1L, (long) (totalFrames * seekRatio));

		this.framesRemaining = Math.max(0L, totalFrames - startFrame);
		this.raf = new RandomAccessFile(track.file().toFile(), "r");
		this.raf.seek(header.dataStartOffset() + startFrame * header.blockAlign());
	}

	@Override
	public DecodedPcm decodeNext() throws IOException {
		if (framesRemaining <= 0) {
			return null;
		}
		int framesToRead = (int) Math.min(FRAMES_PER_READ, framesRemaining);
		int bytesWanted = framesToRead * header.blockAlign();
		int totalRead = 0;
		while (totalRead < bytesWanted) {
			int r = raf.read(rawBuf, totalRead, bytesWanted - totalRead);
			if (r <= 0) {
				break;
			}
			totalRead += r;
		}

		int framesRead = totalRead / header.blockAlign();
		if (framesRead <= 0) {
			framesRemaining = 0;
			return null;
		}
		framesRemaining -= framesRead;

		short[] pcm = new short[framesRead * outChannels];
		int blockAlign = header.blockAlign();
		int bits = header.bitsPerSample();
		boolean isFloat = header.ieeeFloat();

		int outIdx = 0;
		for (int f = 0; f < framesRead; f++) {
			int frameBase = f * blockAlign;
			for (int ch = 0; ch < outChannels; ch++) {
				int sOff = frameBase + ch * bytesPerSample;
				pcm[outIdx++] = decodeSample16(rawBuf, sOff, bits, isFloat);
			}
		}

		return new DecodedPcm(pcm, outIdx, header.sampleRate(), outChannels);
	}

	private static short decodeSample16(byte[] b, int off, int bits, boolean isFloat) {
		if (isFloat) {
			if (bits == 32) {
				int intBits = (b[off] & 0xFF)
						| ((b[off + 1] & 0xFF) << 8)
						| ((b[off + 2] & 0xFF) << 16)
						| ((b[off + 3] & 0xFF) << 24);
				float val = Float.intBitsToFloat(intBits);
				if (Float.isNaN(val)) {
					return 0;
				}
				int scaled = Math.round(val * 32767.0f);
				return (short) Math.max(-32768, Math.min(32767, scaled));
			} else {
				long longBits = ((long) (b[off] & 0xFF))
						| ((long) (b[off + 1] & 0xFF) << 8)
						| ((long) (b[off + 2] & 0xFF) << 16)
						| ((long) (b[off + 3] & 0xFF) << 24)
						| ((long) (b[off + 4] & 0xFF) << 32)
						| ((long) (b[off + 5] & 0xFF) << 40)
						| ((long) (b[off + 6] & 0xFF) << 48)
						| ((long) (b[off + 7] & 0xFF) << 56);
				double val = Double.longBitsToDouble(longBits);
				if (Double.isNaN(val)) {
					return 0;
				}
				long scaled = Math.round(val * 32767.0);
				return (short) Math.max(-32768L, Math.min(32767L, scaled));
			}
		}

		return switch (bits) {
			case 8 -> (short) (((b[off] & 0xFF) - 128) << 8);
			case 16 -> (short) ((b[off] & 0xFF) | (b[off + 1] << 8));
			case 24 -> (short) ((b[off + 1] & 0xFF) | (b[off + 2] << 8));
			case 32 -> (short) ((b[off + 2] & 0xFF) | (b[off + 3] << 8));
			default -> 0;
		};
	}

	@Override
	public void close() {
		try {
			raf.close();
		} catch (IOException ignored) {
		}
	}
}


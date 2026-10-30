package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3MetadataParser;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

public final class Mp3FormatDecoder implements AudioTrackDecoder {
	private final InputStream sliceStream;
	private final Bitstream bitstream;
	private final Decoder decoder;

	public Mp3FormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		long startByteOffset = Mp3MetadataParser.findSeekFrameOffset(track, seekRatio);
		long endByteOffset = track.audioStartOffset() + track.audioByteLength();
		this.sliceStream = openBoundedSlice(track, startByteOffset, endByteOffset);
		this.bitstream = new Bitstream(sliceStream);
		this.decoder = new Decoder();
	}

	@Override
	public DecodedPcm decodeNext() throws Exception {
		while (true) {
			Header header;
			try {
				header = bitstream.readFrame();
			} catch (BitstreamException e) {
				continue;
			}
			if (header == null) {
				return null; // EOF
			}

			try {
				SampleBuffer decoded = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				int freq = decoded.getSampleFrequency();
				int srcChannels = decoded.getChannelCount();
				int srcLen = decoded.getBufferLength();
				short[] srcBuf = decoded.getBuffer();

				if (srcLen > 0 && freq > 0 && srcChannels > 0) {
					int outChannels = Math.min(2, srcChannels);
					short[] copy = new short[srcLen];
					System.arraycopy(srcBuf, 0, copy, 0, srcLen);
					return new DecodedPcm(copy, srcLen, freq, outChannels);
				}
			} catch (Exception ignored) {
				// Skip single damaged MPEG frame
			} finally {
				bitstream.closeFrame();
			}
		}
	}

	@Override
	public void close() {
		try {
			bitstream.close();
		} catch (BitstreamException ignored) {
		}
		try {
			sliceStream.close();
		} catch (IOException ignored) {
		}
	}

	private static InputStream openBoundedSlice(Mp3TrackInfo track, long startOffset, long endOffset) throws IOException {
		FileInputStream fis = new FileInputStream(track.file().toFile());
		long toSkip = Math.max(0L, startOffset);
		while (toSkip > 0) {
			long skipped = fis.skip(toSkip);
			if (skipped <= 0) {
				if (fis.read() == -1) {
					break;
				}
				toSkip--;
			} else {
				toSkip -= skipped;
			}
		}
		long maxRemaining = Math.max(0L, endOffset - startOffset);
		return new InputStream() {
			private long remaining = maxRemaining;

			@Override
			public int read() throws IOException {
				if (remaining <= 0) {
					return -1;
				}
				int b = fis.read();
				if (b != -1) {
					remaining--;
				}
				return b;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (remaining <= 0) {
					return -1;
				}
				int capped = (int) Math.min(len, remaining);
				int r = fis.read(b, off, capped);
				if (r > 0) {
					remaining -= r;
				}
				return r;
			}

			@Override
			public void close() throws IOException {
				fis.close();
			}
		};
	}
}


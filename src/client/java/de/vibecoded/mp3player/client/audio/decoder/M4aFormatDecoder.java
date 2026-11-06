package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.MP4InputStream;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;
import net.sourceforge.jaad.mp4.od.DecoderSpecificInfo;

/**
 * Pure-Java M4A / MP4 (AAC-LC / HE-AAC / SBR / PS) streaming decoder powered by JAAD,
 * supporting both standard MP4 sample tables (stbl) and fragmented DASH MP4 (moof/traf/trun + mdat)
 * with O(1) sample seeking.
 */
public final class M4aFormatDecoder implements AudioTrackDecoder {
	private final RandomAccessFile raf;
	private final MP4InputStream mp4In;
	private final AudioTrack audioTrack;
	private final Decoder aacDecoder;
	private final SampleBuffer sampleBuffer;

	// Fragmented DASH M4A (moof/mdat) frame index when stbl has 0 frames (e.g. yt-dlp without ffmpeg)
	private final long[] dashFrameOffsets;
	private final int[] dashFrameSizes;
	private int dashFrameIndex = 0;

	public M4aFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.raf = new RandomAccessFile(track.file().toFile(), "r");
		this.mp4In = MP4InputStream.open(raf);
		try {
			MP4Container container = new MP4Container(mp4In);
			Movie movie = container.getMovie();
			if (movie == null) {
				throw new IOException("M4A container contains no Movie box: " + track.file());
			}

			List<Track> tracks = movie.getTracks(AudioTrack.AudioCodec.AAC);
			if (tracks == null || tracks.isEmpty()) {
				throw new IOException("M4A file contains no AAC audio track: " + track.file());
			}

			this.audioTrack = (AudioTrack) tracks.getFirst();
			DecoderSpecificInfo dsi = audioTrack.getDecoderSpecificInfo();
			if (dsi == null || dsi.getData() == null || dsi.getData().length == 0) {
				throw new IOException("Missing AAC DecoderSpecificInfo in " + track.file());
			}

			this.aacDecoder = Decoder.create(dsi.getData());
			java.util.logging.Logger.getLogger("jaad.aac.Decoder").setLevel(java.util.logging.Level.OFF);
			this.sampleBuffer = new SampleBuffer();
			this.sampleBuffer.setBigEndian(false);

			if (!audioTrack.hasMoreFrames()) {
				List<long[]> dashFrames = parseDashFrames(raf);
				if (dashFrames.isEmpty()) {
					throw new IOException("M4A file contains neither stbl frames nor DASH moof fragments: " + track.file());
				}
				this.dashFrameOffsets = new long[dashFrames.size()];
				this.dashFrameSizes = new int[dashFrames.size()];
				for (int i = 0; i < dashFrames.size(); i++) {
					long[] entry = dashFrames.get(i);
					this.dashFrameOffsets[i] = entry[0];
					this.dashFrameSizes[i] = (int) entry[1];
				}
				if (seekRatio > 0.0001) {
					this.dashFrameIndex = Math.min(
							this.dashFrameOffsets.length - 1,
							Math.max(0, (int) Math.round(seekRatio * this.dashFrameOffsets.length))
					);
				}
			} else {
				this.dashFrameOffsets = null;
				this.dashFrameSizes = null;
				if (seekRatio > 0.0001) {
					double duration = movie.getDuration() > 0.0 ? movie.getDuration() : track.durationSeconds();
					if (duration > 0.0) {
						audioTrack.seek(duration * seekRatio);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
	}

	@Override
	public DecodedPcm decodeNext() throws Exception {
		while (hasNextFrame()) {
			byte[] data = readNextAacFrame();
			if (data == null || data.length == 0) {
				continue;
			}

			try {
				aacDecoder.decodeFrame(data, sampleBuffer);
			} catch (Exception ignored) {
				// Skip single damaged AAC access unit
				continue;
			}

			byte[] pcmBytes = sampleBuffer.getData();
			int sr = sampleBuffer.getSampleRate();
			int srcChannels = sampleBuffer.getChannels();
			if (pcmBytes == null || pcmBytes.length < 2 || sr <= 0 || srcChannels <= 0) {
				continue;
			}

			int totalFrames = pcmBytes.length / (2 * srcChannels);
			if (totalFrames <= 0) {
				continue;
			}

			int outChannels = Math.min(2, srcChannels);
			short[] pcm = new short[totalFrames * outChannels];
			int outIdx = 0;

			for (int f = 0; f < totalFrames; f++) {
				int frameByteBase = f * srcChannels * 2;
				for (int ch = 0; ch < outChannels; ch++) {
					int bOff = frameByteBase + ch * 2;
					pcm[outIdx++] = (short) ((pcmBytes[bOff] & 0xFF) | (pcmBytes[bOff + 1] << 8));
				}
			}

			return new DecodedPcm(pcm, outIdx, sr, outChannels);
		}
		return null;
	}

	private boolean hasNextFrame() {
		if (dashFrameOffsets != null) {
			return dashFrameIndex < dashFrameOffsets.length;
		}
		return audioTrack.hasMoreFrames();
	}

	private byte[] readNextAacFrame() throws IOException {
		if (dashFrameOffsets != null) {
			if (dashFrameIndex >= dashFrameOffsets.length) {
				return null;
			}
			long offset = dashFrameOffsets[dashFrameIndex];
			int size = dashFrameSizes[dashFrameIndex];
			dashFrameIndex++;
			if (size <= 0 || offset < 0 || offset + size > raf.length()) {
				return new byte[0];
			}
			raf.seek(offset);
			byte[] buf = new byte[size];
			raf.readFully(buf);
			return buf;
		}
		Frame frame = audioTrack.readNextFrame();
		return frame != null ? frame.getData() : null;
	}

	private static List<long[]> parseDashFrames(RandomAccessFile raf) throws IOException {
		List<long[]> list = new ArrayList<>(4096);
		long fileLen = raf.length();
		long pos = 0L;
		while (pos + 8L <= fileLen) {
			raf.seek(pos);
			long size = Integer.toUnsignedLong(raf.readInt());
			int type = raf.readInt();
			long headerSize = 8L;
			if (size == 1L) {
				if (pos + 16L > fileLen) {
					break;
				}
				size = raf.readLong();
				headerSize = 16L;
			} else if (size == 0L) {
				size = fileLen - pos;
			}
			if (size < headerSize || pos + size > fileLen) {
				break;
			}
			if (type == 0x6D6F6F66) { // 'moof'
				parseMoof(raf, pos, pos + headerSize, pos + size, list);
			}
			pos += size;
		}
		return list;
	}

	private static void parseMoof(
			RandomAccessFile raf,
			long moofStart,
			long start,
			long end,
			List<long[]> out
	) throws IOException {
		long p = start;
		while (p + 8L <= end) {
			raf.seek(p);
			long sz = Integer.toUnsignedLong(raf.readInt());
			int tp = raf.readInt();
			if (sz < 8L || p + sz > end) {
				break;
			}
			if (tp == 0x74726166) { // 'traf'
				parseTraf(raf, moofStart, p + 8L, p + sz, out);
			}
			p += sz;
		}
	}

	private static void parseTraf(
			RandomAccessFile raf,
			long moofStart,
			long start,
			long end,
			List<long[]> out
	) throws IOException {
		long p = start;
		int defaultSampleSize = 0;
		long baseDataOffset = moofStart;
		while (p + 8L <= end) {
			raf.seek(p);
			long sz = Integer.toUnsignedLong(raf.readInt());
			int tp = raf.readInt();
			if (sz < 8L || p + sz > end) {
				break;
			}
			if (tp == 0x74666864) { // 'tfhd'
				int vf = raf.readInt();
				int flags = vf & 0xFFFFFF;
				raf.readInt(); // track_ID
				if ((flags & 0x000001) != 0) {
					baseDataOffset = raf.readLong();
				}
				if ((flags & 0x000002) != 0) {
					raf.readInt();
				}
				if ((flags & 0x000008) != 0) {
					raf.readInt();
				}
				if ((flags & 0x000010) != 0) {
					defaultSampleSize = raf.readInt();
				}
			} else if (tp == 0x7472756E) { // 'trun'
				int vf = raf.readInt();
				int flags = vf & 0xFFFFFF;
				int sampleCount = raf.readInt();
				long dataOffset = baseDataOffset;
				if ((flags & 0x000001) != 0) {
					dataOffset += raf.readInt();
				}
				if ((flags & 0x000004) != 0) {
					raf.readInt();
				}
				long curOff = dataOffset;
				for (int i = 0; i < sampleCount && raf.getFilePointer() < p + sz; i++) {
					if ((flags & 0x000100) != 0) {
						raf.readInt();
					}
					int sSize = ((flags & 0x000200) != 0) ? raf.readInt() : defaultSampleSize;
					if ((flags & 0x000400) != 0) {
						raf.readInt();
					}
					if ((flags & 0x000800) != 0) {
						raf.readInt();
					}
					if (sSize > 0) {
						out.add(new long[]{curOff, sSize});
						curOff += sSize;
					}
				}
			}
			p += sz;
		}
	}

	@Override
	public void close() {
		try {
			mp4In.close();
		} catch (IOException ignored) {
		}
		try {
			raf.close();
		} catch (IOException ignored) {
		}
	}
}

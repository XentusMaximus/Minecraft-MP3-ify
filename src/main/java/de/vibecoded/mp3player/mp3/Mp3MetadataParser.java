package de.vibecoded.mp3player.mp3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java ID3v1/ID3v2 metadata parser, CBR/VBR duration calculator,
 * and MPEG frame-boundary chunker for glitch-free seeking and network streaming.
 */
public final class Mp3MetadataParser {
	private static final int[] SAMPLE_RATES_V1 = {44100, 48000, 32000};
	private static final int[] SAMPLE_RATES_V2 = {22050, 24000, 16000};
	private static final int[] SAMPLE_RATES_V25 = {11025, 12000, 8000};

	private static final int[] BITRATES_V1_L1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0};
	private static final int[] BITRATES_V1_L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0};
	private static final int[] BITRATES_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
	private static final int[] BITRATES_V2_L1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0};
	private static final int[] BITRATES_V2_L23 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};

	private Mp3MetadataParser() {
	}

	public record FrameHeader(
			int mpegVersionId, // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
			int layer,         // 1, 2, or 3
			int bitrateKbps,
			int sampleRate,
			int channelMode,   // 3 = Mono
			int samplesPerFrame,
			int frameSize
	) {
		public long durationNanos() {
			if (sampleRate <= 0) {
				return 26_122_449L;
			}
			return (samplesPerFrame * 1_000_000_000L) / sampleRate;
		}
	}

	public record FrameChunk(byte[] data, long durationNanos, long nextFileOffset) {
	}

	public static Mp3TrackInfo parse(Path path) throws IOException {
		long fileSize = Files.size(path);
		String fileName = path.getFileName().toString();
		String title = null;
		String artist = null;
		long audioStart = 0L;
		long audioEnd = fileSize;

		try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
			// 1. Parse ID3v2 header if present
			if (fileSize >= 10) {
				byte[] header = new byte[10];
				raf.readFully(header);
				if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
					int majorVer = header[3] & 0xFF;
					int flags = header[5] & 0xFF;
					int tagSize = synchsafeToInt(header, 6);
					boolean hasFooter = (flags & 0x10) != 0;
					audioStart = Math.min(fileSize, 10L + tagSize + (hasFooter ? 10L : 0L));

					int inspectLen = (int) Math.min(tagSize, 262_144);
					if (inspectLen > 0 && 10L + inspectLen <= fileSize) {
						byte[] tagBytes = new byte[inspectLen];
						raf.seek(10L);
						raf.readFully(tagBytes);
						String[] parsed = parseId3v2Frames(tagBytes, majorVer, flags);
						if (parsed[0] != null && !parsed[0].isBlank()) {
							title = parsed[0];
						}
						if (parsed[1] != null && !parsed[1].isBlank()) {
							artist = parsed[1];
						}
					}
				}
			}

			// 2. Check trailing 128-byte ID3v1 tag
			if (fileSize >= audioStart + 128) {
				raf.seek(fileSize - 128);
				byte[] v1 = new byte[128];
				raf.readFully(v1);
				if (v1[0] == 'T' && v1[1] == 'A' && v1[2] == 'G') {
					audioEnd = fileSize - 128;
					if (title == null || title.isBlank()) {
						String t = cleanText(new String(v1, 3, 30, StandardCharsets.ISO_8859_1));
						if (!t.isBlank()) {
							title = t;
						}
					}
					if (artist == null || artist.isBlank()) {
						String a = cleanText(new String(v1, 33, 30, StandardCharsets.ISO_8859_1));
						if (!a.isBlank()) {
							artist = a;
						}
					}
				}
			}

			// 3. Locate first verified MPEG frame for duration & bitrate
			long scanLimit = Math.min(audioEnd, audioStart + 131_072L);
			int scanBytesLen = (int) Math.max(0L, scanLimit - audioStart);
			int durationSeconds = 0;
			int bitrateKbps = 192;
			int sampleRate = 44100;

			if (scanBytesLen >= 8) {
				byte[] scanBuf = new byte[scanBytesLen];
				raf.seek(audioStart);
				raf.readFully(scanBuf);

				int firstFrameRel = findVerifiedFrameOffset(scanBuf, 0, scanBuf.length);
				if (firstFrameRel >= 0) {
					audioStart += firstFrameRel;
					FrameHeader fh = decodeFrameHeader(scanBuf, firstFrameRel);
					if (fh != null) {
						bitrateKbps = fh.bitrateKbps();
						sampleRate = fh.sampleRate();
						long audioLen = Math.max(0L, audioEnd - audioStart);

						int vbrFrames = extractVbrTotalFrames(scanBuf, firstFrameRel, fh);
						if (vbrFrames > 0 && fh.sampleRate() > 0) {
							long totalSamples = (long) vbrFrames * fh.samplesPerFrame();
							durationSeconds = (int) Math.max(1L, Math.round(totalSamples / (double) fh.sampleRate()));
							if (durationSeconds > 0) {
								bitrateKbps = (int) ((audioLen * 8L) / (durationSeconds * 1000L));
							}
						} else if (fh.bitrateKbps() > 0) {
							durationSeconds = (int) Math.max(1L, Math.round((audioLen * 8.0) / (fh.bitrateKbps() * 1000.0)));
						}
					}
				}
			}

			long finalAudioLen = Math.max(0L, audioEnd - audioStart);
			return new Mp3TrackInfo(
					path,
					fileName,
					title,
					artist,
					fileSize,
					audioStart,
					finalAudioLen,
					durationSeconds,
					bitrateKbps,
					sampleRate
			);
		}
	}

	/**
	 * Computes a frame-aligned file byte offset for a seek ratio in [0.0, 1.0].
	 */
	public static long findSeekFrameOffset(Mp3TrackInfo track, double ratio) throws IOException {
		double clamped = Math.max(0.0, Math.min(0.995, ratio));
		if (clamped <= 0.0001) {
			return track.audioStartOffset();
		}
		long audioEnd = track.audioStartOffset() + track.audioByteLength();
		long target = track.audioStartOffset() + (long) (track.audioByteLength() * clamped);
		int window = (int) Math.min(16_384L, Math.max(0L, audioEnd - target));
		if (window < 8) {
			return track.audioStartOffset();
		}
		try (RandomAccessFile raf = new RandomAccessFile(track.file().toFile(), "r")) {
			byte[] buf = new byte[window];
			raf.seek(target);
			raf.readFully(buf);
			int rel = findVerifiedFrameOffset(buf, 0, buf.length);
			return rel >= 0 ? (target + rel) : target;
		}
	}

	/**
	 * Reads up to maxBytes of complete, frame-aligned MPEG audio frames starting at fileOffset.
	 * Guarantees every chunk starts on a valid MPEG frame header and never exceeds maxBytes.
	 */
	public static FrameChunk readFrameAlignedChunk(RandomAccessFile raf, long fileOffset, long audioEndOffset, int maxBytes) throws IOException {
		if (fileOffset >= audioEndOffset) {
			return null;
		}
		int lookahead = (int) Math.min(maxBytes + 4096L, audioEndOffset - fileOffset);
		if (lookahead < 4) {
			return null;
		}
		byte[] buf = new byte[lookahead];
		raf.seek(fileOffset);
		int read = raf.read(buf);
		if (read < 4) {
			return null;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, read));
		long totalNanos = 0L;
		int pos = 0;

		while (pos + 4 <= read) {
			FrameHeader fh = decodeFrameHeader(buf, pos);
			if (fh == null || fh.frameSize() <= 0) {
				// Resync forward to next valid frame
				int next = findNextFrameOffset(buf, pos + 1, read);
				if (next < 0) {
					pos = read;
					break;
				}
				pos = next;
				continue;
			}

			int fSize = fh.frameSize();
			if (pos + fSize > read) {
				// If not even a single frame fit into lookahead, emit what's left or advance
				if (out.size() == 0 && fileOffset + pos + fSize <= audioEndOffset && fSize <= maxBytes) {
					byte[] single = new byte[fSize];
					raf.seek(fileOffset + pos);
					raf.readFully(single);
					return new FrameChunk(single, fh.durationNanos(), fileOffset + pos + fSize);
				}
				break;
			}

			if (out.size() + fSize > maxBytes) {
				if (out.size() == 0) {
					// Rare oversized single frame > maxBytes: slice or clamp safely
					int clampedLen = Math.min(fSize, maxBytes);
					out.write(buf, pos, clampedLen);
					totalNanos += fh.durationNanos();
					pos += fSize;
				}
				break;
			}

			out.write(buf, pos, fSize);
			totalNanos += fh.durationNanos();
			pos += fSize;
		}

		if (out.size() == 0) {
			return null;
		}
		return new FrameChunk(out.toByteArray(), totalNanos, fileOffset + pos);
	}

	private static int findVerifiedFrameOffset(byte[] buf, int start, int length) {
		for (int i = start; i + 4 <= length; i++) {
			FrameHeader fh = decodeFrameHeader(buf, i);
			if (fh != null && fh.frameSize() >= 24) {
				int next = i + fh.frameSize();
				if (next + 4 <= length) {
					FrameHeader fh2 = decodeFrameHeader(buf, next);
					if (fh2 != null && fh2.sampleRate() == fh.sampleRate() && fh2.layer() == fh.layer()) {
						return i;
					}
				} else {
					return i;
				}
			}
		}
		return findNextFrameOffset(buf, start, length);
	}

	private static int findNextFrameOffset(byte[] buf, int start, int length) {
		for (int i = start; i + 4 <= length; i++) {
			if (decodeFrameHeader(buf, i) != null) {
				return i;
			}
		}
		return -1;
	}

	public static FrameHeader decodeFrameHeader(byte[] b, int off) {
		if (off + 4 > b.length) {
			return null;
		}
		int b0 = b[off] & 0xFF;
		int b1 = b[off + 1] & 0xFF;
		if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
			return null;
		}
		int versionId = (b1 >> 3) & 0x03;
		if (versionId == 1) {
			return null; // reserved
		}
		int layerIdx = (b1 >> 1) & 0x03;
		if (layerIdx == 0) {
			return null; // reserved
		}
		int layer = 4 - layerIdx; // 3->L1, 2->L2, 1->L3

		int b2 = b[off + 2] & 0xFF;
		int bitrateIdx = (b2 >> 4) & 0x0F;
		if (bitrateIdx == 0 || bitrateIdx == 15) {
			return null;
		}
		int srIdx = (b2 >> 2) & 0x03;
		if (srIdx == 3) {
			return null;
		}
		int padding = (b2 >> 1) & 0x01;
		int channelMode = ((b[off + 3] & 0xFF) >> 6) & 0x03;

		int sampleRate = switch (versionId) {
			case 3 -> SAMPLE_RATES_V1[srIdx];
			case 2 -> SAMPLE_RATES_V2[srIdx];
			default -> SAMPLE_RATES_V25[srIdx];
		};

		int bitrateKbps;
		if (versionId == 3) {
			bitrateKbps = switch (layer) {
				case 1 -> BITRATES_V1_L1[bitrateIdx];
				case 2 -> BITRATES_V1_L2[bitrateIdx];
				default -> BITRATES_V1_L3[bitrateIdx];
			};
		} else {
			bitrateKbps = (layer == 1) ? BITRATES_V2_L1[bitrateIdx] : BITRATES_V2_L23[bitrateIdx];
		}
		if (bitrateKbps <= 0 || sampleRate <= 0) {
			return null;
		}

		int samplesPerFrame;
		int frameSize;
		if (layer == 1) {
			samplesPerFrame = 384;
			frameSize = ((12 * bitrateKbps * 1000 / sampleRate) + padding) * 4;
		} else if (layer == 2) {
			samplesPerFrame = 1152;
			frameSize = (144 * bitrateKbps * 1000 / sampleRate) + padding;
		} else {
			if (versionId == 3) {
				samplesPerFrame = 1152;
				frameSize = (144 * bitrateKbps * 1000 / sampleRate) + padding;
			} else {
				samplesPerFrame = 576;
				frameSize = (72 * bitrateKbps * 1000 / sampleRate) + padding;
			}
		}

		return new FrameHeader(versionId, layer, bitrateKbps, sampleRate, channelMode, samplesPerFrame, frameSize);
	}

	private static int extractVbrTotalFrames(byte[] buf, int frameOffset, FrameHeader fh) {
		boolean mono = fh.channelMode() == 3;
		int sideInfoSize = (fh.mpegVersionId() == 3) ? (mono ? 17 : 32) : (mono ? 9 : 17);
		int xingPos = frameOffset + 4 + sideInfoSize;
		if (xingPos + 12 <= buf.length) {
			if ((buf[xingPos] == 'X' && buf[xingPos + 1] == 'i' && buf[xingPos + 2] == 'n' && buf[xingPos + 3] == 'g')
					|| (buf[xingPos] == 'I' && buf[xingPos + 1] == 'n' && buf[xingPos + 2] == 'f' && buf[xingPos + 3] == 'o')) {
				int flags = readIntBE(buf, xingPos + 4);
				if ((flags & 0x01) != 0) {
					return readIntBE(buf, xingPos + 8);
				}
			}
		}
		int vbriPos = frameOffset + 4 + 32;
		if (vbriPos + 18 <= buf.length) {
			if (buf[vbriPos] == 'V' && buf[vbriPos + 1] == 'B' && buf[vbriPos + 2] == 'R' && buf[vbriPos + 3] == 'I') {
				return readIntBE(buf, vbriPos + 14);
			}
		}
		return -1;
	}

	private static String[] parseId3v2Frames(byte[] data, int majorVer, int tagFlags) {
		String title = null;
		String artist = null;
		int pos = 0;

		// Skip optional extended header in v2.3 / v2.4
		if ((tagFlags & 0x40) != 0 && data.length >= 4) {
			int extSize = (majorVer == 4) ? synchsafeToInt(data, 0) : readIntBE(data, 0);
			pos += (majorVer == 4) ? extSize : (4 + extSize);
		}

		while (pos > -1 && pos < data.length) {
			if (majorVer == 2) {
				if (pos + 6 > data.length || data[pos] == 0) {
					break;
				}
				String frameId = new String(data, pos, 3, StandardCharsets.ISO_8859_1);
				int fSize = ((data[pos + 3] & 0xFF) << 16) | ((data[pos + 4] & 0xFF) << 8) | (data[pos + 5] & 0xFF);
				pos += 6;
				if (fSize <= 0 || pos + fSize > data.length) {
					break;
				}
				if ("TT2".equals(frameId) && title == null) {
					title = decodeId3Text(data, pos, fSize);
				} else if ("TP1".equals(frameId) && artist == null) {
					artist = decodeId3Text(data, pos, fSize);
				}
				pos += fSize;
			} else {
				if (pos + 10 > data.length || data[pos] == 0) {
					break;
				}
				String frameId = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
				int fSize = (majorVer == 4) ? synchsafeToInt(data, pos + 4) : readIntBE(data, pos + 4);
				pos += 10;
				if (fSize <= 0 || pos + fSize > data.length) {
					break;
				}
				if ("TIT2".equals(frameId) && title == null) {
					title = decodeId3Text(data, pos, fSize);
				} else if ("TPE1".equals(frameId) && artist == null) {
					artist = decodeId3Text(data, pos, fSize);
				}
				pos += fSize;
			}
			if (title != null && artist != null) {
				break;
			}
		}

		return new String[]{title, artist};
	}

	private static String decodeId3Text(byte[] data, int offset, int length) {
		if (length <= 1) {
			return "";
		}
		int encoding = data[offset] & 0xFF;
		Charset cs = switch (encoding) {
			case 1 -> StandardCharsets.UTF_16;
			case 2 -> StandardCharsets.UTF_16BE;
			case 3 -> StandardCharsets.UTF_8;
			default -> StandardCharsets.ISO_8859_1;
		};
		String raw = new String(data, offset + 1, length - 1, cs);
		return cleanText(raw);
	}

	private static String cleanText(String s) {
		if (s == null) {
			return "";
		}
		int nul = s.indexOf('\0');
		if (nul >= 0) {
			s = s.substring(0, nul);
		}
		return s.replaceAll("\\p{Cntrl}", "").trim();
	}

	private static int synchsafeToInt(byte[] b, int off) {
		return ((b[off] & 0x7F) << 21)
				| ((b[off + 1] & 0x7F) << 14)
				| ((b[off + 2] & 0x7F) << 7)
				| (b[off + 3] & 0x7F);
	}

	private static int readIntBE(byte[] b, int off) {
		return ((b[off] & 0xFF) << 24)
				| ((b[off + 1] & 0xFF) << 16)
				| ((b[off + 2] & 0xFF) << 8)
				| (b[off + 3] & 0xFF);
	}
}


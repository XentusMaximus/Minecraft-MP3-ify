package de.vibecoded.mp3player.mp3;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java RIFF/WAVE header & LIST/INFO metadata parser.
 * Supports 8/16/24/32-bit Linear PCM, 32/64-bit IEEE Float, and WAVE_FORMAT_EXTENSIBLE (0xFFFE).
 */
public record WavHeaderInfo(
		boolean ieeeFloat,
		int channels,
		int sampleRate,
		int blockAlign,
		int bitsPerSample,
		long dataStartOffset,
		long dataByteLength,
		String title,
		String artist
) {
	public long totalFrames() {
		return blockAlign > 0 ? (dataByteLength / blockAlign) : 0L;
	}

	public int durationSeconds() {
		long frames = totalFrames();
		if (frames <= 0 || sampleRate <= 0) {
			return 0;
		}
		return (int) Math.max(1L, Math.round(frames / (double) sampleRate));
	}

	public int bitrateKbps() {
		if (sampleRate <= 0 || blockAlign <= 0) {
			return 0;
		}
		return (int) Math.round((sampleRate * (double) blockAlign * 8.0) / 1000.0);
	}

	public static WavHeaderInfo parse(Path path) throws IOException {
		long fileSize = Files.size(path);
		if (fileSize < 44) {
			throw new IOException("WAV file too small: " + fileSize);
		}

		try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
			byte[] riffHeader = new byte[12];
			raf.readFully(riffHeader);
			if (riffHeader[0] != 'R' || riffHeader[1] != 'I' || riffHeader[2] != 'F' || riffHeader[3] != 'F'
					|| riffHeader[8] != 'W' || riffHeader[9] != 'A' || riffHeader[10] != 'V' || riffHeader[11] != 'E') {
				throw new IOException("Not a valid RIFF/WAVE file");
			}

			boolean foundFmt = false;
			boolean foundData = false;
			boolean ieeeFloat = false;
			int channels = 0;
			int sampleRate = 0;
			int blockAlign = 0;
			int bitsPerSample = 0;
			long dataStart = 0L;
			long dataLen = 0L;
			String title = null;
			String artist = null;

			long offset = 12L;
			byte[] chunkHdr = new byte[8];

			while (offset + 8 <= fileSize) {
				raf.seek(offset);
				raf.readFully(chunkHdr);
				String chunkId = new String(chunkHdr, 0, 4, StandardCharsets.ISO_8859_1);
				long chunkSize = readUInt32LE(chunkHdr, 4);
				long payloadOffset = offset + 8L;
				long clampedSize = Math.max(0L, Math.min(chunkSize, fileSize - payloadOffset));

				if ("fmt ".equals(chunkId) && clampedSize >= 16) {
					byte[] fmt = new byte[(int) Math.min(clampedSize, 64L)];
					raf.readFully(fmt);
					int formatTag = readUInt16LE(fmt, 0);
					channels = readUInt16LE(fmt, 2);
					sampleRate = (int) readUInt32LE(fmt, 4);
					blockAlign = readUInt16LE(fmt, 12);
					bitsPerSample = readUInt16LE(fmt, 14);

					int effectiveFormat = formatTag;
					if (formatTag == 0xFFFE && fmt.length >= 40) {
						// WAVE_FORMAT_EXTENSIBLE: subFormat GUID starts at byte 24
						effectiveFormat = readUInt16LE(fmt, 24);
					}

					if (effectiveFormat == 1) {
						ieeeFloat = false;
						if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
							throw new IOException("Unsupported WAV PCM bit depth: " + bitsPerSample);
						}
					} else if (effectiveFormat == 3) {
						ieeeFloat = true;
						if (bitsPerSample != 32 && bitsPerSample != 64) {
							throw new IOException("Unsupported WAV IEEE float bit depth: " + bitsPerSample);
						}
					} else {
						throw new IOException("Unsupported WAV audio format tag: 0x" + Integer.toHexString(formatTag));
					}

					if (channels < 1 || sampleRate <= 0) {
						throw new IOException("Invalid WAV channel count or sample rate");
					}
					int minBlockAlign = channels * ((bitsPerSample + 7) / 8);
					if (blockAlign < minBlockAlign) {
						blockAlign = minBlockAlign;
					}
					foundFmt = true;
				} else if ("data".equals(chunkId)) {
					dataStart = payloadOffset;
					dataLen = clampedSize;
					foundData = true;
				} else if ("LIST".equals(chunkId) && clampedSize >= 4 && clampedSize <= 262_144L) {
					byte[] listBuf = new byte[(int) clampedSize];
					raf.readFully(listBuf);
					if (listBuf[0] == 'I' && listBuf[1] == 'N' && listBuf[2] == 'F' && listBuf[3] == 'O') {
						String[] info = parseListInfo(listBuf);
						if (info[0] != null && !info[0].isBlank()) {
							title = info[0];
						}
						if (info[1] != null && !info[1].isBlank()) {
							artist = info[1];
						}
					}
				}

				long paddedAdvance = chunkSize + (chunkSize & 1L);
				if (paddedAdvance < 0) {
					break;
				}
				offset = payloadOffset + paddedAdvance;
			}

			if (!foundFmt || !foundData || dataLen <= 0) {
				throw new IOException("Incomplete WAV file (missing fmt or data chunk)");
			}

			return new WavHeaderInfo(
					ieeeFloat,
					channels,
					sampleRate,
					blockAlign,
					bitsPerSample,
					dataStart,
					dataLen,
					title,
					artist
			);
		}
	}

	private static String[] parseListInfo(byte[] buf) {
		String title = null;
		String artist = null;
		int pos = 4;
		while (pos + 8 <= buf.length) {
			String subId = new String(buf, pos, 4, StandardCharsets.ISO_8859_1);
			int subLen = (int) Math.min(buf.length - (pos + 8L), readUInt32LE(buf, pos + 4));
			pos += 8;
			if (subLen < 0 || pos + subLen > buf.length) {
				break;
			}
			if ("INAM".equalsIgnoreCase(subId) && title == null) {
				title = decodeInfoText(buf, pos, subLen);
			} else if ("IART".equalsIgnoreCase(subId) && artist == null) {
				artist = decodeInfoText(buf, pos, subLen);
			}
			pos += subLen + (subLen & 1);
		}
		return new String[]{title, artist};
	}

	private static String decodeInfoText(byte[] buf, int off, int len) {
		if (len <= 0) {
			return null;
		}
		String s = new String(buf, off, len, StandardCharsets.UTF_8);
		if (s.indexOf('\uFFFD') >= 0) {
			s = new String(buf, off, len, StandardCharsets.ISO_8859_1);
		}
		int nul = s.indexOf('\0');
		if (nul >= 0) {
			s = s.substring(0, nul);
		}
		String cleaned = s.replaceAll("\\p{Cntrl}", "").trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static int readUInt16LE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static long readUInt32LE(byte[] b, int off) {
		return ((long) (b[off] & 0xFF))
				| ((long) (b[off + 1] & 0xFF) << 8)
				| ((long) (b[off + 2] & 0xFF) << 16)
				| ((long) (b[off + 3] & 0xFF) << 24);
	}
}


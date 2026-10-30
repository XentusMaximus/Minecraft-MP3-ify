package de.vibecoded.mp3player.mp3;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Pure-Java RFC 3533 Ogg page and laced-packet demuxer with O(1) page-resync seeking,
 * Vorbis comment metadata parsing, and trailing granule-position duration scanning.
 */
public final class OggPacketReader implements Closeable {
	private static final int MAX_PACKET_BYTES = 4 * 1024 * 1024; // 4 MB safety cap per Ogg packet

	private final RandomAccessFile raf;
	private final long fileLength;
	private final ByteArrayOutputStream packetAccum = new ByteArrayOutputStream(4096);

	private boolean hasTargetSerial = false;
	private int targetSerial = 0;

	private int[] pageSegments = new int[0];
	private byte[] pageBody = new byte[0];
	private int segIndex = 0;
	private int bodyOffset = 0;
	private boolean skipContinuedPacket = false;

	public OggPacketReader(Path path) throws IOException {
		this.raf = new RandomAccessFile(path.toFile(), "r");
		this.fileLength = raf.length();
	}

	public long fileLength() {
		return fileLength;
	}

	public long currentOffset() throws IOException {
		return raf.getFilePointer();
	}

	public int streamSerial() {
		return targetSerial;
	}

	/**
	 * Seeks to an arbitrary byte offset in the Ogg file and resynchronizes cleanly
	 * to the next valid Ogg page boundary, discarding any partial continued packet.
	 */
	public void seekAndResync(long targetByteOffset) throws IOException {
		long clamped = Math.max(0L, Math.min(fileLength, targetByteOffset));
		raf.seek(clamped);
		packetAccum.reset();
		pageSegments = new int[0];
		pageBody = new byte[0];
		segIndex = 0;
		bodyOffset = 0;
		skipContinuedPacket = true;
	}

	/**
	 * Reads and reassembles the next complete Ogg packet from the primary logical bitstream,
	 * or returns null at end-of-file.
	 */
	public byte[] readNextPacket() throws IOException {
		while (true) {
			while (segIndex < pageSegments.length) {
				int segLen = pageSegments[segIndex++];
				if (bodyOffset + segLen > pageBody.length) {
					// Truncated page body; discard partial packet and advance to next page
					packetAccum.reset();
					segIndex = pageSegments.length;
					break;
				}

				if (skipContinuedPacket) {
					bodyOffset += segLen;
					if (segLen < 255) {
						skipContinuedPacket = false;
					}
					continue;
				}

				if (segLen > 0) {
					if (packetAccum.size() + segLen > MAX_PACKET_BYTES) {
						packetAccum.reset();
						skipContinuedPacket = (segLen == 255);
						bodyOffset += segLen;
						continue;
					}
					packetAccum.write(pageBody, bodyOffset, segLen);
					bodyOffset += segLen;
				}

				if (segLen < 255) {
					byte[] complete = packetAccum.toByteArray();
					packetAccum.reset();
					return complete;
				}
			}

			if (!readNextMatchingPage()) {
				return null;
			}
		}
	}

	private boolean readNextMatchingPage() throws IOException {
		byte[] hdr = new byte[27];
		while (true) {
			long syncPos = syncToNextOggMagic();
			if (syncPos < 0 || syncPos + 27 > fileLength) {
				return false;
			}
			raf.seek(syncPos);
			raf.readFully(hdr);

			int version = hdr[4] & 0xFF;
			if (version != 0) {
				raf.seek(syncPos + 1);
				continue;
			}

			int headerType = hdr[5] & 0xFF;
			int serial = readInt32LE(hdr, 14);
			int numSegments = hdr[26] & 0xFF;
			if (syncPos + 27L + numSegments > fileLength) {
				return false;
			}

			byte[] segTable = new byte[numSegments];
			raf.readFully(segTable);

			int totalBody = 0;
			int[] segs = new int[numSegments];
			for (int i = 0; i < numSegments; i++) {
				int s = segTable[i] & 0xFF;
				segs[i] = s;
				totalBody += s;
			}

			long bodyStart = raf.getFilePointer();
			if (bodyStart + totalBody > fileLength) {
				return false;
			}

			if (!hasTargetSerial) {
				hasTargetSerial = true;
				targetSerial = serial;
			} else if (serial != targetSerial) {
				raf.seek(bodyStart + totalBody);
				continue;
			}

			byte[] body = new byte[totalBody];
			raf.readFully(body);

			boolean pageContinues = (headerType & 0x01) != 0;
			if (skipContinuedPacket) {
				if (!pageContinues) {
					// Fresh packet starts right at segment 0 of this page!
					skipContinuedPacket = false;
					packetAccum.reset();
				}
			} else if (pageContinues && packetAccum.size() == 0) {
				// Unexpected continuation without a leading segment: skip until packet boundary
				skipContinuedPacket = true;
			} else if (!pageContinues && packetAccum.size() > 0) {
				// Previous packet wasn't terminated properly before a fresh packet began
				packetAccum.reset();
			}

			this.pageSegments = segs;
			this.pageBody = body;
			this.segIndex = 0;
			this.bodyOffset = 0;
			return true;
		}
	}

	private long syncToNextOggMagic() throws IOException {
		long pos = raf.getFilePointer();
		if (pos + 4 > fileLength) {
			return -1L;
		}
		byte[] buf = new byte[8192];
		while (pos + 4 <= fileLength) {
			raf.seek(pos);
			int read = raf.read(buf);
			if (read < 4) {
				return -1L;
			}
			for (int i = 0; i + 4 <= read; i++) {
				if (buf[i] == 'O' && buf[i + 1] == 'g' && buf[i + 2] == 'g' && buf[i + 3] == 'S') {
					return pos + i;
				}
			}
			pos += (read - 3);
		}
		return -1L;
	}

	/**
	 * Scans the trailing 64 KB of an Ogg file backwards to retrieve the last valid 64-bit granule_position.
	 */
	public long findLastGranulePosition() throws IOException {
		if (fileLength < 27) {
			return -1L;
		}
		int window = (int) Math.min(65_536L, fileLength);
		long start = fileLength - window;
		byte[] tail = new byte[window];
		long savedPos = raf.getFilePointer();
		try {
			raf.seek(start);
			raf.readFully(tail);
		} finally {
			raf.seek(savedPos);
		}

		for (int i = window - 27; i >= 0; i--) {
			if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S' && tail[i + 4] == 0) {
				int serial = readInt32LE(tail, i + 14);
				if (hasTargetSerial && serial != targetSerial) {
					continue;
				}
				long granule = readInt64LE(tail, i + 6);
				if (granule > 0L) {
					return granule;
				}
			}
		}
		return -1L;
	}

	/**
	 * Parses TITLE and ARTIST from a Vorbis Comment block (used by both Ogg Vorbis and Ogg Opus).
	 */
	public static String[] parseVorbisComments(byte[] packet, int commentHeaderOffset) {
		String title = null;
		String artist = null;
		int pos = commentHeaderOffset;
		if (packet == null || pos + 8 > packet.length) {
			return new String[]{null, null};
		}

		int vendorLen = readInt32LE(packet, pos);
		pos += 4;
		if (vendorLen < 0 || pos + vendorLen + 4 > packet.length) {
			return new String[]{null, null};
		}
		pos += vendorLen;

		int commentCount = readInt32LE(packet, pos);
		pos += 4;
		if (commentCount < 0) {
			return new String[]{null, null};
		}

		int maxComments = Math.min(commentCount, 512);
		for (int i = 0; i < maxComments && pos + 4 <= packet.length; i++) {
			int len = readInt32LE(packet, pos);
			pos += 4;
			if (len < 0 || pos + len > packet.length) {
				break;
			}
			String entry = new String(packet, pos, len, StandardCharsets.UTF_8);
			pos += len;

			int eq = entry.indexOf('=');
			if (eq > 0) {
				String key = entry.substring(0, eq).trim().toUpperCase(Locale.ROOT);
				String val = entry.substring(eq + 1).replaceAll("\\p{Cntrl}", "").trim();
				if (!val.isEmpty()) {
					if ("TITLE".equals(key) && title == null) {
						title = val;
					} else if ("ARTIST".equals(key) && artist == null) {
						artist = val;
					} else if ("ALBUMARTIST".equals(key) && artist == null) {
						artist = val;
					}
				}
			}
			if (title != null && artist != null) {
				break;
			}
		}

		return new String[]{title, artist};
	}

	public static int readUInt16LE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	public static short readInt16LE(byte[] b, int off) {
		return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
	}

	public static int readInt32LE(byte[] b, int off) {
		return (b[off] & 0xFF)
				| ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16)
				| ((b[off + 3] & 0xFF) << 24);
	}

	public static long readInt64LE(byte[] b, int off) {
		return ((long) (b[off] & 0xFF))
				| ((long) (b[off + 1] & 0xFF) << 8)
				| ((long) (b[off + 2] & 0xFF) << 16)
				| ((long) (b[off + 3] & 0xFF) << 24)
				| ((long) (b[off + 4] & 0xFF) << 32)
				| ((long) (b[off + 5] & 0xFF) << 40)
				| ((long) (b[off + 6] & 0xFF) << 48)
				| ((long) (b[off + 7] & 0xFF) << 56);
	}

	@Override
	public void close() throws IOException {
		raf.close();
	}
}


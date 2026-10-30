package de.vibecoded.mp3player.mp3;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.MP4InputStream;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.MetaData;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;
import net.sourceforge.jaad.mp4.od.DecoderSpecificInfo;
import org.jflac.FLACDecoder;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.StreamInfo;
import org.jflac.metadata.VorbisComment;

/**
 * Multi-format metadata, duration, and stream-parameter inspector for:
 * MP3, M4A (MP4/AAC), OPUS (Ogg Opus), WAV (PCM / IEEE Float), FLAC, and OGG (Ogg Vorbis / Ogg Opus).
 */
public final class AudioMetadataParsers {
	private AudioMetadataParsers() {
	}

	public static Mp3TrackInfo parse(Path path) throws IOException {
		long fileSize = Files.size(path);
		if (fileSize < 28) {
			throw new IOException("Audio file too small: " + path);
		}

		byte[] magic = new byte[(int) Math.min(64L, fileSize)];
		long postId3Offset = 0L;
		byte[] postId3Magic = null;

		try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
			raf.readFully(magic);
			if (magic.length >= 10 && magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') {
				int flags = magic[5] & 0xFF;
				int tagSize = ((magic[6] & 0x7F) << 21)
						| ((magic[7] & 0x7F) << 14)
						| ((magic[8] & 0x7F) << 7)
						| (magic[9] & 0x7F);
				postId3Offset = 10L + tagSize + ((flags & 0x10) != 0 ? 10L : 0L);
				if (postId3Offset + 12 <= fileSize) {
					postId3Magic = new byte[12];
					raf.seek(postId3Offset);
					raf.readFully(postId3Magic);
				}
			}
		}

		// 1. Magic-byte detection (handles misnamed extensions like Opus inside .ogg or FLAC with ID3v2)
		if (isRiffWave(magic)) {
			return parseWav(path, fileSize);
		}
		if (isFlac(magic) || (postId3Magic != null && isFlac(postId3Magic))) {
			return parseFlac(path, fileSize);
		}
		if (isOgg(magic)) {
			return parseOggOrOpus(path, fileSize);
		}
		if (isMp4Box(magic)) {
			return parseM4a(path, fileSize);
		}

		// 2. Extension-based dispatch fallback
		AudioFormatType extType = AudioFormatType.fromPath(path);
		if (extType != null) {
			return switch (extType) {
				case WAV -> parseWav(path, fileSize);
				case FLAC -> parseFlac(path, fileSize);
				case OPUS, OGG -> parseOggOrOpus(path, fileSize);
				case M4A -> parseM4a(path, fileSize);
				case MP3 -> Mp3MetadataParser.parse(path);
			};
		}

		return Mp3MetadataParser.parse(path);
	}

	private static Mp3TrackInfo parseWav(Path path, long fileSize) throws IOException {
		WavHeaderInfo wav = WavHeaderInfo.parse(path);
		return new Mp3TrackInfo(
				path,
				path.getFileName().toString(),
				AudioFormatType.WAV,
				wav.title(),
				wav.artist(),
				fileSize,
				wav.dataStartOffset(),
				wav.dataByteLength(),
				wav.durationSeconds(),
				wav.bitrateKbps(),
				wav.sampleRate()
		);
	}

	private static Mp3TrackInfo parseFlac(Path path, long fileSize) throws IOException {
		String title = null;
		String artist = null;
		StreamInfo streamInfo = null;
		long audioStart = 0L;

		try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 16_384)) {
			FLACDecoder decoder = new FLACDecoder(in);
			Metadata[] metas = decoder.readMetadata();
			audioStart = Math.max(0L, Math.min(fileSize, decoder.getTotalBytesRead()));
			streamInfo = decoder.getStreamInfo();

			if (metas != null) {
				for (Metadata meta : metas) {
					if (meta instanceof StreamInfo si && streamInfo == null) {
						streamInfo = si;
					} else if (meta instanceof VorbisComment vc) {
						int count = vc.getNumComments();
						for (int i = 0; i < count; i++) {
							String entry = vc.getComment(i).toString();
							int eq = entry.indexOf('=');
							if (eq > 0) {
								String key = entry.substring(0, eq).trim().toUpperCase(Locale.ROOT);
								String val = cleanText(entry.substring(eq + 1));
								if (val != null) {
									if ("TITLE".equals(key) && title == null) {
										title = val;
									} else if ("ARTIST".equals(key) && artist == null) {
										artist = val;
									} else if ("ALBUMARTIST".equals(key) && artist == null) {
										artist = val;
									}
								}
							}
						}
					}
				}
			}
		}

		if (streamInfo == null || streamInfo.getSampleRate() <= 0 || streamInfo.getChannels() <= 0) {
			throw new IOException("Invalid FLAC StreamInfo in " + path);
		}

		int sampleRate = streamInfo.getSampleRate();
		long totalSamples = streamInfo.getTotalSamples();
		long audioLen = Math.max(0L, fileSize - audioStart);
		int durationSeconds = totalSamples > 0
				? (int) Math.max(1L, Math.round(totalSamples / (double) sampleRate))
				: 0;
		int bitrateKbps = durationSeconds > 0
				? (int) ((audioLen * 8L) / (durationSeconds * 1000L))
				: 0;

		return new Mp3TrackInfo(
				path,
				path.getFileName().toString(),
				AudioFormatType.FLAC,
				title,
				artist,
				fileSize,
				audioStart,
				audioLen,
				durationSeconds,
				bitrateKbps,
				sampleRate
		);
	}

	private static Mp3TrackInfo parseOggOrOpus(Path path, long fileSize) throws IOException {
		try (OggPacketReader ogg = new OggPacketReader(path)) {
			byte[] pkt1 = ogg.readNextPacket();
			if (pkt1 == null || pkt1.length < 7) {
				throw new IOException("Empty or truncated Ogg stream: " + path);
			}

			// Case A: Ogg Opus ("OpusHead")
			if (pkt1.length >= 19 && startsWithAscii(pkt1, "OpusHead")) {
				int channels = pkt1[9] & 0xFF;
				if (channels < 1) {
					throw new IOException("Invalid Opus channel count: " + channels);
				}
				int preSkip = OggPacketReader.readUInt16LE(pkt1, 10);

				String title = null;
				String artist = null;
				byte[] pkt2 = ogg.readNextPacket();
				if (pkt2 != null && pkt2.length >= 16 && startsWithAscii(pkt2, "OpusTags")) {
					String[] comments = OggPacketReader.parseVorbisComments(pkt2, 8);
					title = comments[0];
					artist = comments[1];
				}

				long audioStart = Math.min(fileSize, ogg.currentOffset());
				long audioLen = Math.max(0L, fileSize - audioStart);
				long lastGranule = ogg.findLastGranulePosition();
				int durationSeconds = 0;
				if (lastGranule > preSkip) {
					durationSeconds = (int) Math.max(1L, Math.round((lastGranule - preSkip) / 48000.0));
				}
				int bitrateKbps = durationSeconds > 0
						? (int) ((fileSize * 8L) / (durationSeconds * 1000L))
						: 96;

				return new Mp3TrackInfo(
						path,
						path.getFileName().toString(),
						AudioFormatType.OPUS,
						title,
						artist,
						fileSize,
						audioStart,
						audioLen,
						durationSeconds,
						bitrateKbps,
						48000
				);
			}

			// Case B: Ogg Vorbis (0x01 + "vorbis")
			if (pkt1.length >= 28 && (pkt1[0] & 0xFF) == 1 && matchesAsciiAt(pkt1, 1, "vorbis")) {
				int channels = pkt1[11] & 0xFF;
				int sampleRate = OggPacketReader.readInt32LE(pkt1, 12);
				int bitrateNominal = OggPacketReader.readInt32LE(pkt1, 20);
				if (channels < 1 || sampleRate <= 0) {
					throw new IOException("Invalid Vorbis identification header");
				}

				String title = null;
				String artist = null;
				byte[] pkt2 = ogg.readNextPacket();
				if (pkt2 != null && pkt2.length >= 15 && (pkt2[0] & 0xFF) == 3 && matchesAsciiAt(pkt2, 1, "vorbis")) {
					String[] comments = OggPacketReader.parseVorbisComments(pkt2, 7);
					title = comments[0];
					artist = comments[1];
				}

				// Advance past setup packet (header 3) to record accurate audioStartOffset
				ogg.readNextPacket();
				long audioStart = Math.min(fileSize, ogg.currentOffset());
				long audioLen = Math.max(0L, fileSize - audioStart);

				long lastGranule = ogg.findLastGranulePosition();
				int durationSeconds = (lastGranule > 0L)
						? (int) Math.max(1L, Math.round(lastGranule / (double) sampleRate))
						: 0;
				int bitrateKbps;
				if (durationSeconds > 0) {
					bitrateKbps = (int) ((fileSize * 8L) / (durationSeconds * 1000L));
				} else if (bitrateNominal > 0) {
					bitrateKbps = bitrateNominal / 1000;
				} else {
					bitrateKbps = 160;
				}

				return new Mp3TrackInfo(
						path,
						path.getFileName().toString(),
						AudioFormatType.OGG,
						title,
						artist,
						fileSize,
						audioStart,
						audioLen,
						durationSeconds,
						bitrateKbps,
						sampleRate
				);
			}

			throw new IOException("Unsupported Ogg codec (expected Vorbis or Opus) in " + path);
		}
	}

	private static Mp3TrackInfo parseM4a(Path path, long fileSize) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
			 MP4InputStream mp4In = MP4InputStream.open(raf)) {

			MP4Container container = new MP4Container(mp4In);
			Movie movie = container.getMovie();
			if (movie == null) {
				throw new IOException("M4A container has no Movie box: " + path);
			}

			List<Track> aacTracks = movie.getTracks(AudioTrack.AudioCodec.AAC);
			if (aacTracks == null || aacTracks.isEmpty()) {
				throw new IOException("M4A file does not contain a supported AAC audio track: " + path);
			}

			AudioTrack audioTrack = (AudioTrack) aacTracks.getFirst();
			int sampleRate = audioTrack.getSampleRate();
			DecoderSpecificInfo dsi = audioTrack.getDecoderSpecificInfo();
			if (dsi != null && dsi.getData() != null && dsi.getData().length > 0) {
				Decoder dec = Decoder.create(dsi.getData());
				int outRate = (int) dec.getAudioFormat().getSampleRate();
				if (outRate > 0) {
					sampleRate = outRate;
				}
			}
			if (sampleRate <= 0) {
				sampleRate = 44100;
			}

			String title = null;
			String artist = null;
			double durationSec = movie.getDuration();

			if (movie.containsMetaData()) {
				MetaData meta = movie.getMetaData();
				if (meta != null) {
					title = cleanText(meta.get(MetaData.Field.TITLE));
					artist = cleanText(meta.get(MetaData.Field.ARTIST));
					if (artist == null) {
						artist = cleanText(meta.get(MetaData.Field.ALBUM_ARTIST));
					}
					if (durationSec <= 0.0) {
						Integer lenMs = meta.get(MetaData.Field.LENGTH_IN_MILLISECONDS);
						if (lenMs != null && lenMs > 0) {
							durationSec = lenMs / 1000.0;
						}
					}
				}
			}

			int durationSeconds = durationSec > 0.0
					? (int) Math.max(1L, Math.round(durationSec))
					: 0;
			int bitrateKbps = durationSeconds > 0
					? (int) ((fileSize * 8L) / (durationSeconds * 1000L))
					: 128;

			return new Mp3TrackInfo(
					path,
					path.getFileName().toString(),
					AudioFormatType.M4A,
					title,
					artist,
					fileSize,
					0L,
					fileSize,
					durationSeconds,
					bitrateKbps,
					sampleRate
			);
		}
	}

	private static boolean isRiffWave(byte[] b) {
		return b.length >= 12
				&& b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
				&& b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
	}

	private static boolean isFlac(byte[] b) {
		return b.length >= 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C';
	}

	private static boolean isOgg(byte[] b) {
		return b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
	}

	private static boolean isMp4Box(byte[] b) {
		if (b.length < 8) {
			return false;
		}
		String box = new String(b, 4, 4, StandardCharsets.ISO_8859_1);
		return "ftyp".equals(box) || "moov".equals(box) || "mdat".equals(box)
				|| "free".equals(box) || "skip".equals(box) || "wide".equals(box);
	}

	private static boolean startsWithAscii(byte[] b, String ascii) {
		return matchesAsciiAt(b, 0, ascii);
	}

	private static boolean matchesAsciiAt(byte[] b, int offset, String ascii) {
		if (b.length < offset + ascii.length()) {
			return false;
		}
		for (int i = 0; i < ascii.length(); i++) {
			if ((b[offset + i] & 0xFF) != ascii.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static String cleanText(String s) {
		if (s == null) {
			return null;
		}
		int nul = s.indexOf('\0');
		if (nul >= 0) {
			s = s.substring(0, nul);
		}
		String trimmed = s.replaceAll("\\p{Cntrl}", "").trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}


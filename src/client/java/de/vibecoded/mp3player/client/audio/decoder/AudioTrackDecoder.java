package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.AudioFormatType;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;

/**
 * Common streaming PCM decoder interface across all 6 supported audio formats:
 * MP3, M4A (AAC), OPUS, WAV, FLAC, and OGG (Vorbis / Opus).
 */
public interface AudioTrackDecoder extends AutoCloseable {
	record DecodedPcm(short[] samples, int length, int sampleRate, int channels) {
	}

	/**
	 * Decodes and returns the next block of interleaved 16-bit signed PCM samples (1 or 2 channels),
	 * or null when end-of-stream is reached.
	 */
	DecodedPcm decodeNext() throws Exception;

	@Override
	void close();

	static AudioTrackDecoder open(Mp3TrackInfo track, double seekRatio) throws IOException {
		double clampedRatio = Math.max(0.0, Math.min(0.995, seekRatio));
		AudioFormatType type = track.format() != null ? track.format() : AudioFormatType.MP3;
		return switch (type) {
			case MP3 -> new Mp3FormatDecoder(track, clampedRatio);
			case WAV -> new WavFormatDecoder(track, clampedRatio);
			case FLAC -> new FlacFormatDecoder(track, clampedRatio);
			case OPUS -> new OpusFormatDecoder(track, clampedRatio);
			case OGG -> OggVorbisFormatDecoder.openOggOrOpus(track, clampedRatio);
			case M4A -> new M4aFormatDecoder(track, clampedRatio);
		};
	}
}


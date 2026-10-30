package de.vibecoded.mp3player.mp3;

import java.nio.file.Path;
import java.util.Locale;

/**
 * All supported audio container/codec formats in v1.1.0.
 */
public enum AudioFormatType {
	MP3("MP3", ".mp3"),
	M4A("M4A", ".m4a"),
	OPUS("OPUS", ".opus"),
	WAV("WAV", ".wav"),
	FLAC("FLAC", ".flac"),
	OGG("OGG", ".ogg");

	private final String badge;
	private final String extension;

	AudioFormatType(String badge, String extension) {
		this.badge = badge;
		this.extension = extension;
	}

	public String badge() {
		return badge;
	}

	public String extension() {
		return extension;
	}

	public static AudioFormatType fromPath(Path path) {
		if (path == null || path.getFileName() == null) {
			return null;
		}
		return fromFileName(path.getFileName().toString());
	}

	public static AudioFormatType fromFileName(String fileName) {
		if (fileName == null) {
			return null;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		for (AudioFormatType type : values()) {
			if (lower.endsWith(type.extension)) {
				return type;
			}
		}
		return null;
	}

	public static boolean isSupportedFile(Path path) {
		return fromPath(path) != null;
	}
}


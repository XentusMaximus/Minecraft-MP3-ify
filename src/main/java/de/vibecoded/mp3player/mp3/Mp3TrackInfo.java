package de.vibecoded.mp3player.mp3;

import java.nio.file.Path;
import java.util.Locale;

public record Mp3TrackInfo(
		Path file,
		String fileName,
		AudioFormatType format,
		String title,
		String artist,
		long fileSize,
		long audioStartOffset,
		long audioByteLength,
		int durationSeconds,
		int bitrateKbps,
		int sampleRate
) {
	public Mp3TrackInfo(
			Path file,
			String fileName,
			String title,
			String artist,
			long fileSize,
			long audioStartOffset,
			long audioByteLength,
			int durationSeconds,
			int bitrateKbps,
			int sampleRate
	) {
		this(
				file,
				fileName,
				AudioFormatType.fromFileName(fileName) != null ? AudioFormatType.fromFileName(fileName) : AudioFormatType.MP3,
				title,
				artist,
				fileSize,
				audioStartOffset,
				audioByteLength,
				durationSeconds,
				bitrateKbps,
				sampleRate
		);
	}

	public String formatBadge() {
		return format != null ? format.badge() : "MP3";
	}

	public String displayTitle() {
		if (title != null && !title.isBlank()) {
			return title;
		}
		String base = fileName;
		int dot = base.lastIndexOf('.');
		return dot > 0 ? base.substring(0, dot) : base;
	}

	public String displaySubtitle() {
		if (artist != null && !artist.isBlank()) {
			return artist;
		}
		return bitrateKbps > 0 ? (formatBadge() + " · " + bitrateKbps + " kbps") : formatBadge();
	}

	public String fullDisplayLabel() {
		if (artist != null && !artist.isBlank()) {
			return artist + " - " + displayTitle();
		}
		return displayTitle();
	}

	public String formattedDuration() {
		return formatSeconds(durationSeconds);
	}

	public boolean matchesQuery(String query) {
		if (query == null || query.isBlank()) {
			return true;
		}
		String q = query.toLowerCase(Locale.ROOT).trim();
		if (displayTitle().toLowerCase(Locale.ROOT).contains(q)) {
			return true;
		}
		if (artist != null && artist.toLowerCase(Locale.ROOT).contains(q)) {
			return true;
		}
		if (formatBadge().toLowerCase(Locale.ROOT).contains(q)) {
			return true;
		}
		return fileName.toLowerCase(Locale.ROOT).contains(q);
	}

	public static String formatSeconds(int totalSeconds) {
		if (totalSeconds <= 0) {
			return "0:00";
		}
		int hours = totalSeconds / 3600;
		int minutes = (totalSeconds % 3600) / 60;
		int seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}
}

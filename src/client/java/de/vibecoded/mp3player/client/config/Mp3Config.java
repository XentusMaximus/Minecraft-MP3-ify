package de.vibecoded.mp3player.client.config;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.network.Mp3Payloads;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class Mp3Config {
	public enum LoopMode {
		OFF,
		ALL,
		ONE;

		public LoopMode next() {
			return switch (this) {
				case OFF -> ALL;
				case ALL -> ONE;
				case ONE -> OFF;
			};
		}
	}

	/**
	 * Represents a user-linked Speaker block with its dimension, coordinates, enabled state,
	 * and individual volume multiplier (0.0 .. 1.0).
	 */
	public record LinkedSpeaker(
			ResourceKey<Level> dimension,
			BlockPos pos,
			boolean enabled,
			float volume
	) {
		public LinkedSpeaker(ResourceKey<Level> dimension, BlockPos pos, boolean enabled, float volume) {
			this.dimension = dimension;
			this.pos = pos.immutable();
			this.enabled = enabled;
			this.volume = clamp01(volume);
		}
	}

	private final Path configFile;
	private float volume = 0.80f;
	private float optionsVolume = 1.00f;
	private boolean speaker3dMode = false;
	private boolean shuffle = false;
	private LoopMode loopMode = LoopMode.OFF;
	private boolean muteOthers = false;
	private boolean linkedSpeakersEnabled = true;
	private final List<LinkedSpeaker> linkedSpeakers = new ArrayList<>();

	// Spotify Account Link & Playlist Configuration
	private String spotifyClientId = "";
	private String spotifyAccessToken = "";
	private String spotifyRefreshToken = "";
	private long spotifyTokenExpiresAt = 0L;
	private String spotifyAccountName = "";
	private final List<String> spotifySavedPlaylists = new ArrayList<>();

	// YouTube Music Account Link & Playlist Configuration
	private String ytMusicAccountName = "";
	private String ytMusicCookieBrowser = "";
	private final List<String> ytMusicSavedPlaylists = new ArrayList<>();

	public Mp3Config() {
		this.configFile = FabricLoader.getInstance().getConfigDir().resolve("mp3player.properties");
		load();
	}

	public float volume() {
		return volume;
	}

	public void setVolume(float volume) {
		this.volume = clamp01(volume);
	}

	/**
	 * Global MP3 & Boombox music category volume configured in Minecraft's
	 * Music & Sounds Options screen (0.0 .. 1.0).
	 */
	public float optionsVolume() {
		return optionsVolume;
	}

	public void setOptionsVolume(float optionsVolume) {
		this.optionsVolume = clamp01(optionsVolume);
	}

	public boolean speaker3dMode() {
		return speaker3dMode;
	}

	public void setSpeaker3dMode(boolean speaker3dMode) {
		this.speaker3dMode = speaker3dMode;
	}

	public boolean shuffle() {
		return shuffle;
	}

	public void setShuffle(boolean shuffle) {
		this.shuffle = shuffle;
	}

	public LoopMode loopMode() {
		return loopMode;
	}

	public void setLoopMode(LoopMode loopMode) {
		this.loopMode = loopMode != null ? loopMode : LoopMode.OFF;
	}

	public boolean muteOthers() {
		return muteOthers;
	}

	public void setMuteOthers(boolean muteOthers) {
		this.muteOthers = muteOthers;
	}

	public boolean linkedSpeakersEnabled() {
		return linkedSpeakersEnabled;
	}

	public void setLinkedSpeakersEnabled(boolean linkedSpeakersEnabled) {
		this.linkedSpeakersEnabled = linkedSpeakersEnabled;
	}

	public List<LinkedSpeaker> linkedSpeakers() {
		return Collections.unmodifiableList(linkedSpeakers);
	}

	public void setLinkedSpeakers(List<LinkedSpeaker> speakers) {
		linkedSpeakers.clear();
		if (speakers != null) {
			for (LinkedSpeaker ls : speakers) {
				if (ls != null && ls.dimension() != null && ls.pos() != null) {
					linkedSpeakers.add(ls);
					if (linkedSpeakers.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
						break;
					}
				}
			}
		}
	}

	public String spotifyClientId() {
		return spotifyClientId;
	}

	public void setSpotifyClientId(String spotifyClientId) {
		this.spotifyClientId = spotifyClientId != null ? spotifyClientId.trim() : "";
	}

	public String spotifyAccessToken() {
		return spotifyAccessToken;
	}

	public void setSpotifyAccessToken(String spotifyAccessToken) {
		this.spotifyAccessToken = spotifyAccessToken != null ? spotifyAccessToken.trim() : "";
	}

	public String spotifyRefreshToken() {
		return spotifyRefreshToken;
	}

	public void setSpotifyRefreshToken(String spotifyRefreshToken) {
		this.spotifyRefreshToken = spotifyRefreshToken != null ? spotifyRefreshToken.trim() : "";
	}

	public long spotifyTokenExpiresAt() {
		return spotifyTokenExpiresAt;
	}

	public void setSpotifyTokenExpiresAt(long spotifyTokenExpiresAt) {
		this.spotifyTokenExpiresAt = spotifyTokenExpiresAt;
	}

	public String spotifyAccountName() {
		return spotifyAccountName;
	}

	public void setSpotifyAccountName(String spotifyAccountName) {
		this.spotifyAccountName = spotifyAccountName != null ? spotifyAccountName.trim() : "";
	}

	public boolean isSpotifyLinked() {
		return !spotifyAccountName.isBlank() || !spotifyAccessToken.isBlank() || !spotifyRefreshToken.isBlank();
	}

	public List<String> spotifySavedPlaylists() {
		return Collections.unmodifiableList(spotifySavedPlaylists);
	}

	public void setSpotifySavedPlaylists(List<String> playlists) {
		spotifySavedPlaylists.clear();
		if (playlists != null) {
			for (String id : playlists) {
				if (id != null && !id.isBlank() && !spotifySavedPlaylists.contains(id.trim())) {
					spotifySavedPlaylists.add(id.trim());
				}
			}
		}
	}

	public void clearSpotifyAccount() {
		this.spotifyAccessToken = "";
		this.spotifyRefreshToken = "";
		this.spotifyTokenExpiresAt = 0L;
		this.spotifyAccountName = "";
		this.spotifySavedPlaylists.clear();
	}

	public String ytMusicAccountName() {
		return ytMusicAccountName;
	}

	public void setYtMusicAccountName(String ytMusicAccountName) {
		this.ytMusicAccountName = ytMusicAccountName != null ? ytMusicAccountName.trim() : "";
	}

	public String ytMusicCookieBrowser() {
		return ytMusicCookieBrowser;
	}

	public void setYtMusicCookieBrowser(String ytMusicCookieBrowser) {
		this.ytMusicCookieBrowser = ytMusicCookieBrowser != null ? ytMusicCookieBrowser.trim() : "";
	}

	public boolean isYtMusicLinked() {
		return !ytMusicAccountName.isBlank() || !ytMusicCookieBrowser.isBlank() || !ytMusicSavedPlaylists.isEmpty();
	}

	public List<String> ytMusicSavedPlaylists() {
		return Collections.unmodifiableList(ytMusicSavedPlaylists);
	}

	public void setYtMusicSavedPlaylists(List<String> playlists) {
		ytMusicSavedPlaylists.clear();
		if (playlists != null) {
			for (String id : playlists) {
				if (id != null && !id.isBlank() && !ytMusicSavedPlaylists.contains(id.trim())) {
					ytMusicSavedPlaylists.add(id.trim());
				}
			}
		}
	}

	public void clearYtMusicAccount() {
		this.ytMusicAccountName = "";
		this.ytMusicCookieBrowser = "";
		this.ytMusicSavedPlaylists.clear();
	}

	public void load() {
		if (!Files.isRegularFile(configFile)) {
			save();
			return;
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(configFile)) {
			props.load(in);
			this.volume = clamp01(Float.parseFloat(props.getProperty("volume", "0.80")));
			this.optionsVolume = clamp01(Float.parseFloat(props.getProperty("optionsVolume", "1.00")));
			this.speaker3dMode = Boolean.parseBoolean(props.getProperty("speaker3dMode", "false"));
			this.shuffle = Boolean.parseBoolean(props.getProperty("shuffle", "false"));
			this.muteOthers = Boolean.parseBoolean(props.getProperty("muteOthers", "false"));
			this.linkedSpeakersEnabled = Boolean.parseBoolean(props.getProperty("linkedSpeakersEnabled", "true"));
			try {
				this.loopMode = LoopMode.valueOf(props.getProperty("loopMode", "OFF"));
			} catch (IllegalArgumentException ignored) {
				this.loopMode = LoopMode.OFF;
			}
			this.spotifyClientId = props.getProperty("spotifyClientId", "").trim();
			this.spotifyAccessToken = props.getProperty("spotifyAccessToken", "").trim();
			this.spotifyRefreshToken = props.getProperty("spotifyRefreshToken", "").trim();
			try {
				this.spotifyTokenExpiresAt = Long.parseLong(props.getProperty("spotifyTokenExpiresAt", "0").trim());
			} catch (NumberFormatException ignored) {
				this.spotifyTokenExpiresAt = 0L;
			}
			this.spotifyAccountName = props.getProperty("spotifyAccountName", "").trim();
			this.spotifySavedPlaylists.clear();
			String rawPlaylists = props.getProperty("spotifySavedPlaylists", "").trim();
			if (!rawPlaylists.isEmpty()) {
				for (String p : rawPlaylists.split(";")) {
					String trimmed = p.trim();
					if (!trimmed.isEmpty() && !spotifySavedPlaylists.contains(trimmed)) {
						spotifySavedPlaylists.add(trimmed);
					}
				}
			}
			this.ytMusicAccountName = props.getProperty("ytMusicAccountName", "").trim();
			this.ytMusicCookieBrowser = props.getProperty("ytMusicCookieBrowser", "").trim();
			this.ytMusicSavedPlaylists.clear();
			String rawYtmPlaylists = props.getProperty("ytMusicSavedPlaylists", "").trim();
			if (!rawYtmPlaylists.isEmpty()) {
				for (String p : rawYtmPlaylists.split(";")) {
					String trimmed = p.trim();
					if (!trimmed.isEmpty() && !ytMusicSavedPlaylists.contains(trimmed)) {
						ytMusicSavedPlaylists.add(trimmed);
					}
				}
			}
			// Linked speakers are strictly per-session and reset on server join/leave
			this.linkedSpeakers.clear();
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Failed to load mp3player.properties, using defaults", e);
		}
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("volume", Float.toString(volume));
		props.setProperty("optionsVolume", Float.toString(optionsVolume));
		props.setProperty("speaker3dMode", Boolean.toString(speaker3dMode));
		props.setProperty("shuffle", Boolean.toString(shuffle));
		props.setProperty("loopMode", loopMode.name());
		props.setProperty("muteOthers", Boolean.toString(muteOthers));
		props.setProperty("linkedSpeakersEnabled", Boolean.toString(linkedSpeakersEnabled));
		props.setProperty("spotifyClientId", spotifyClientId != null ? spotifyClientId : "");
		props.setProperty("spotifyAccessToken", spotifyAccessToken != null ? spotifyAccessToken : "");
		props.setProperty("spotifyRefreshToken", spotifyRefreshToken != null ? spotifyRefreshToken : "");
		props.setProperty("spotifyTokenExpiresAt", Long.toString(spotifyTokenExpiresAt));
		props.setProperty("spotifyAccountName", spotifyAccountName != null ? spotifyAccountName : "");
		props.setProperty("spotifySavedPlaylists", String.join(";", spotifySavedPlaylists));
		props.setProperty("ytMusicAccountName", ytMusicAccountName != null ? ytMusicAccountName : "");
		props.setProperty("ytMusicCookieBrowser", ytMusicCookieBrowser != null ? ytMusicCookieBrowser : "");
		props.setProperty("ytMusicSavedPlaylists", String.join(";", ytMusicSavedPlaylists));

		try {
			Files.createDirectories(configFile.getParent());
			try (OutputStream out = Files.newOutputStream(configFile)) {
				props.store(out, "MP3-Player, Boombox & Linked Speakers Mod Configuration");
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.warn("Failed to save mp3player.properties", e);
		}
	}

	public static float clamp01(float val) {
		if (Float.isNaN(val)) {
			return 1.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, val));
	}
}

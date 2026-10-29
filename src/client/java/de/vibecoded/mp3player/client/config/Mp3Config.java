package de.vibecoded.mp3player.client.config;

import de.vibecoded.mp3player.Mp3PlayerMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

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

	private final Path configFile;
	private float volume = 0.80f;
	private boolean speaker3dMode = false;
	private boolean shuffle = false;
	private LoopMode loopMode = LoopMode.OFF;
	private boolean muteOthers = false;

	public Mp3Config() {
		this.configFile = FabricLoader.getInstance().getConfigDir().resolve("mp3player.properties");
		load();
	}

	public float volume() {
		return volume;
	}

	public void setVolume(float volume) {
		this.volume = Math.max(0.0f, Math.min(1.0f, volume));
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

	public void load() {
		if (!Files.isRegularFile(configFile)) {
			save();
			return;
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(configFile)) {
			props.load(in);
			this.volume = Math.max(0.0f, Math.min(1.0f, Float.parseFloat(props.getProperty("volume", "0.80"))));
			this.speaker3dMode = Boolean.parseBoolean(props.getProperty("speaker3dMode", "false"));
			this.shuffle = Boolean.parseBoolean(props.getProperty("shuffle", "false"));
			this.muteOthers = Boolean.parseBoolean(props.getProperty("muteOthers", "false"));
			try {
				this.loopMode = LoopMode.valueOf(props.getProperty("loopMode", "OFF"));
			} catch (IllegalArgumentException ignored) {
				this.loopMode = LoopMode.OFF;
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Failed to load mp3player.properties, using defaults", e);
		}
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("volume", Float.toString(volume));
		props.setProperty("speaker3dMode", Boolean.toString(speaker3dMode));
		props.setProperty("shuffle", Boolean.toString(shuffle));
		props.setProperty("loopMode", loopMode.name());
		props.setProperty("muteOthers", Boolean.toString(muteOthers));

		try {
			Files.createDirectories(configFile.getParent());
			try (OutputStream out = Files.newOutputStream(configFile)) {
				props.store(out, "MP3-Player Mod Configuration");
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.warn("Failed to save mp3player.properties", e);
		}
	}
}


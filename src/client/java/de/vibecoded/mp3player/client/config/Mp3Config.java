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
			parseLinkedSpeakers(props.getProperty("linkedSpeakers", ""));
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
		props.setProperty("linkedSpeakers", serializeLinkedSpeakers());

		try {
			Files.createDirectories(configFile.getParent());
			try (OutputStream out = Files.newOutputStream(configFile)) {
				props.store(out, "MP3-Player, Boombox & Linked Speakers Mod Configuration");
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.warn("Failed to save mp3player.properties", e);
		}
	}

	private String serializeLinkedSpeakers() {
		if (linkedSpeakers.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < linkedSpeakers.size(); i++) {
			LinkedSpeaker ls = linkedSpeakers.get(i);
			if (i > 0) {
				sb.append(';');
			}
			sb.append(ls.dimension().identifier().toString())
					.append('@')
					.append(ls.pos().getX()).append(',').append(ls.pos().getY()).append(',').append(ls.pos().getZ())
					.append('@')
					.append(ls.enabled())
					.append('@')
					.append(ls.volume());
		}
		return sb.toString();
	}

	private void parseLinkedSpeakers(String raw) {
		linkedSpeakers.clear();
		if (raw == null || raw.isBlank()) {
			return;
		}
		String[] entries = raw.split(";");
		for (String entry : entries) {
			if (entry.isBlank()) {
				continue;
			}
			try {
				String[] parts = entry.trim().split("@");
				if (parts.length < 2) {
					continue;
				}
				Identifier dimId = Identifier.tryParse(parts[0]);
				if (dimId == null) {
					continue;
				}
				ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
				String[] coords = parts[1].split(",");
				if (coords.length != 3) {
					continue;
				}
				int x = Integer.parseInt(coords[0].trim());
				int y = Integer.parseInt(coords[1].trim());
				int z = Integer.parseInt(coords[2].trim());
				boolean enabled = parts.length < 3 || Boolean.parseBoolean(parts[2].trim());
				float vol = parts.length >= 4 ? clamp01(Float.parseFloat(parts[3].trim())) : 1.0f;
				BlockPos pos = new BlockPos(x, y, z);

				boolean duplicate = false;
				for (LinkedSpeaker existing : linkedSpeakers) {
					if (existing.dimension().equals(dimKey) && existing.pos().equals(pos)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate && linkedSpeakers.size() < Mp3Payloads.MAX_LINKED_SPEAKERS) {
					linkedSpeakers.add(new LinkedSpeaker(dimKey, pos, enabled, vol));
				}
			} catch (Exception ignored) {
			}
		}
	}

	public static float clamp01(float val) {
		if (Float.isNaN(val)) {
			return 1.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, val));
	}
}

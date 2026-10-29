package de.vibecoded.mp3player.client.library;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.mp3.Mp3MetadataParser;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

public final class Mp3LibraryManager {
	private record CacheEntry(long lastModifiedMillis, long size, Mp3TrackInfo info) {
	}

	private final Path mp3Directory;
	private final Map<Path, CacheEntry> metadataCache = new HashMap<>();
	private List<Mp3TrackInfo> tracks = Collections.emptyList();

	public Mp3LibraryManager() {
		this.mp3Directory = FabricLoader.getInstance().getGameDir().resolve("mp3s");
		ensureDirectoryExists();
		refresh();
	}

	public Path mp3Directory() {
		return mp3Directory;
	}

	public List<Mp3TrackInfo> tracks() {
		return tracks;
	}

	public List<Mp3TrackInfo> filter(String query) {
		if (query == null || query.isBlank()) {
			return tracks;
		}
		List<Mp3TrackInfo> matched = new ArrayList<>();
		for (Mp3TrackInfo t : tracks) {
			if (t.matchesQuery(query)) {
				matched.add(t);
			}
		}
		return matched;
	}

	public synchronized void ensureDirectoryExists() {
		try {
			Files.createDirectories(mp3Directory);
			Path readme = mp3Directory.resolve("LIESMICH.txt");
			if (!Files.exists(readme)) {
				String help = """
						=== Minecraft 26.2 MP3-Player Ordner ===
						
						Lege einfach deine .mp3-Dateien in diesen Ordner (oder in Unterordner).
						Tipp: Du kannst .mp3-Dateien auch direkt per Drag & Drop auf das geöffnete
						MP3-Player-Fenster in Minecraft ziehen!
						""";
				Files.writeString(readme, help, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.error("Could not create mp3s directory at {}", mp3Directory, e);
		}
	}

	public synchronized void refresh() {
		ensureDirectoryExists();
		List<Mp3TrackInfo> loaded = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(mp3Directory, 3)) {
			List<Path> mp3Files = walk
					.filter(Files::isRegularFile)
					.filter(Mp3LibraryManager::isMp3File)
					.toList();

			for (Path file : mp3Files) {
				try {
					long mtime = Files.getLastModifiedTime(file).toMillis();
					long size = Files.size(file);
					if (size < 128) {
						continue;
					}
					CacheEntry cached = metadataCache.get(file);
					if (cached != null && cached.lastModifiedMillis() == mtime && cached.size() == size) {
						loaded.add(cached.info());
					} else {
						Mp3TrackInfo parsed = Mp3MetadataParser.parse(file);
						metadataCache.put(file, new CacheEntry(mtime, size, parsed));
						loaded.add(parsed);
					}
				} catch (Exception e) {
					Mp3PlayerMod.LOGGER.warn("Skipping unreadable MP3 file {}", file, e);
				}
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.error("Failed to scan mp3s folder", e);
		}

		loaded.sort(Comparator.comparing(t -> t.displayTitle().toLowerCase(Locale.ROOT)));
		this.tracks = Collections.unmodifiableList(loaded);
	}

	public synchronized int importFiles(List<Path> droppedPaths) {
		ensureDirectoryExists();
		int imported = 0;
		for (Path p : droppedPaths) {
			if (p == null || !Files.exists(p)) {
				continue;
			}
			if (Files.isDirectory(p)) {
				try (Stream<Path> sub = Files.walk(p, 2)) {
					for (Path child : sub.filter(Files::isRegularFile).filter(Mp3LibraryManager::isMp3File).toList()) {
						if (copyIntoLibrary(child)) {
							imported++;
						}
					}
				} catch (IOException e) {
					Mp3PlayerMod.LOGGER.warn("Failed reading dropped folder {}", p, e);
				}
			} else if (Files.isRegularFile(p) && isMp3File(p)) {
				if (copyIntoLibrary(p)) {
					imported++;
				}
			}
		}
		if (imported > 0) {
			refresh();
		}
		return imported;
	}

	private boolean copyIntoLibrary(Path source) {
		try {
			Path normalizedSource = source.toAbsolutePath().normalize();
			Path target = mp3Directory.resolve(source.getFileName().toString()).toAbsolutePath().normalize();
			if (normalizedSource.equals(target)) {
				return true;
			}
			Files.copy(normalizedSource, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.warn("Could not copy MP3 {} into library", source, e);
			return false;
		}
	}

	private static boolean isMp3File(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".mp3");
	}
}


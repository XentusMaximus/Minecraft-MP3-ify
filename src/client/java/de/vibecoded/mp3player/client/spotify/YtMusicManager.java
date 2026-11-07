package de.vibecoded.mp3player.client.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.mp3.AudioMetadataParsers;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Manages YouTube Music (YT Music) account linking (Local Browser Portal, Channel/Playlist linking,
 * and optional Browser Cookie Sync for Liked Music / private playlists), full playlist loading
 * without 100-song caps via yt-dlp, live YT Music search, and exact videoId 3D audio caching.
 */
public final class YtMusicManager {
	public static final String LIKED_MUSIC_ID = "LM";
	public static final String SEARCH_PLAYLIST_PREFIX = "__ytm_search__:";
	public static final String YTMUSIC_QUEUE_PREFIX = "ytmusic:";
	private static final int DEFAULT_PORTAL_PORT = 8889;

	private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile(
			"(?:[?&]list=|music\\.youtube\\.com/browse/VL|^(?:PL|OLAK5uy_|RDCLAK5uy_|RDAMPL|UU|FL|LL|LM))([a-zA-Z0-9_-]+)"
	);
	private static final Pattern BROWSE_ALBUM_PATTERN = Pattern.compile(
			"music\\.youtube\\.com/browse/(MPREb_[a-zA-Z0-9_-]+)"
	);
	private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
			"(?:(?:music\\.|www\\.)?youtube\\.com/watch\\?[^\\s]*v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"
	);
	private static final Pattern HANDLE_PATTERN = Pattern.compile(
			"(?:youtube\\.com/(?:@|channel/|user/|c/))([a-zA-Z0-9._-]+)"
	);

	public record YtMusicPlaylist(
			String id,
			String name,
			String owner,
			int trackCount,
			boolean searchResult
	) {
	}

	public record YtMusicTrack(
			String id,
			String title,
			String artist,
			int durationSeconds
	) {
		public String displayTitle() {
			return (title != null && !title.isBlank()) ? title : id;
		}

		public String displayArtist() {
			return (artist != null && !artist.isBlank()) ? artist : "YouTube Music";
		}

		public String fullDisplayLabel() {
			if (artist != null && !artist.isBlank()) {
				return artist + " - " + displayTitle();
			}
			return displayTitle();
		}

		public String formattedDuration() {
			return Mp3TrackInfo.formatSeconds(durationSeconds);
		}

		public String toQueueTrackId() {
			String safeId = id != null ? id.replace('|', '_') : "track";
			String safeArtist = displayArtist().replace('|', ' ');
			String safeTitle = displayTitle().replace('|', ' ');
			return YTMUSIC_QUEUE_PREFIX + safeId + "|" + durationSeconds + "|" + safeArtist + "|" + safeTitle;
		}

		public static boolean isYtMusicQueueTrackId(String queueTrackId) {
			return queueTrackId != null && queueTrackId.startsWith(YTMUSIC_QUEUE_PREFIX);
		}

		public static YtMusicTrack fromQueueTrackId(String queueTrackId, String fallbackTitle, int defaultDuration) {
			if (!isYtMusicQueueTrackId(queueTrackId)) {
				return null;
			}
			String rest = queueTrackId.substring(YTMUSIC_QUEUE_PREFIX.length());
			String[] parts = rest.split("\\|", 4);
			if (parts.length >= 4) {
				int dur = defaultDuration;
				try {
					dur = Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) {
				}
				return new YtMusicTrack(parts[0], parts[3], parts[2], Math.max(1, dur));
			}
			return new YtMusicTrack(rest, fallbackTitle != null ? fallbackTitle : rest, "YouTube Music", Math.max(1, defaultDuration));
		}
	}

	private final Mp3Config config;
	private final SpotifyManager spotifyManager;
	private final ExecutorService executor;

	private volatile List<YtMusicPlaylist> playlists = Collections.emptyList();
	private final Map<String, List<YtMusicTrack>> playlistTracksCache = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Mp3TrackInfo>> activeDownloads = new ConcurrentHashMap<>();
	private final Set<String> downloadingTrackIds = ConcurrentHashMap.newKeySet();

	private volatile boolean loadingPlaylists = false;
	private volatile boolean loadingTracks = false;
	private volatile String statusMessage = null;
	private volatile long statusExpireMs = 0L;

	private HttpServer portalServer;
	private int activePortalPort = DEFAULT_PORTAL_PORT;

	public YtMusicManager(Mp3Config config, SpotifyManager spotifyManager) {
		this.config = config;
		this.spotifyManager = spotifyManager;
		this.executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "MP3-YTMusic-Worker");
			t.setDaemon(true);
			return t;
		});

		if (config.isYtMusicLinked()) {
			refreshPlaylistsAsync(null);
		}
	}

	public boolean isAccountLinked() {
		return config.isYtMusicLinked();
	}

	public String accountName() {
		String name = config.ytMusicAccountName();
		if (name != null && !name.isBlank()) {
			return name;
		}
		String browser = config.ytMusicCookieBrowser();
		if (browser != null && !browser.isBlank()) {
			return "YT Music (" + browser + ")";
		}
		return "YT Music Nutzer";
	}

	public List<YtMusicPlaylist> playlists() {
		return playlists;
	}

	public List<YtMusicTrack> tracksForPlaylist(YtMusicPlaylist playlist) {
		if (playlist == null) {
			return Collections.emptyList();
		}
		return playlistTracksCache.getOrDefault(playlist.id(), Collections.emptyList());
	}

	public boolean isLoadingPlaylists() {
		return loadingPlaylists;
	}

	public boolean isLoadingTracks() {
		return loadingTracks;
	}

	public boolean isBusy() {
		return loadingPlaylists || loadingTracks || !downloadingTrackIds.isEmpty();
	}

	public boolean isTrackDownloading(String trackId) {
		return trackId != null && downloadingTrackIds.contains(trackId);
	}

	public boolean isTrackCached(String trackId) {
		return findCachedFile(trackId) != null;
	}

	public String statusMessage() {
		if (statusMessage != null && System.currentTimeMillis() < statusExpireMs) {
			return statusMessage;
		}
		return null;
	}

	public void setStatusMessage(String message, long durationMs) {
		this.statusMessage = message;
		this.statusExpireMs = System.currentTimeMillis() + durationMs;
	}

	public String portalUrl() {
		return "http://127.0.0.1:" + activePortalPort + "/ytmusic";
	}

	public void unlinkAccount() {
		config.clearYtMusicAccount();
		config.save();
		this.playlists = Collections.emptyList();
		this.playlistTracksCache.clear();
		stopPortalServer();
	}

	public void openBrowserLinkingPage() {
		ensurePortalServerRunning();
		setStatusMessage(
				SpotifyManager.isGermanLocale()
						? "YouTube Music Portal im Browser geöffnet..."
						: "Opened YouTube Music portal in browser...",
				12_000L
		);
		Util.getPlatform().openUri(URI.create(portalUrl()));
	}

	public boolean linkAccountFromInput(String rawInput) {
		if (rawInput == null || rawInput.isBlank()) {
			openBrowserLinkingPage();
			return false;
		}
		String input = rawInput.trim();

		// 1. Check for browser cookie sync syntax: "browser:firefox", "browser:edge", "browser:chrome", etc.
		if (input.toLowerCase(Locale.ROOT).startsWith("browser:")) {
			String browser = input.substring("browser:".length()).trim().toLowerCase(Locale.ROOT);
			if (!browser.isEmpty()) {
				config.setYtMusicCookieBrowser(browser);
				if (config.ytMusicAccountName().isBlank()) {
					config.setYtMusicAccountName("YT Music (" + browser + ")");
				}
				addSavedPlaylistEntry(LIKED_MUSIC_ID);
				config.save();
				refreshPlaylistsAsync(null);
				return true;
			}
		}

		// 2. Check if input is a YouTube Music / YouTube Playlist URL or ID
		String plId = extractPlaylistId(input);
		if (plId != null) {
			if (config.ytMusicAccountName().isBlank()) {
				config.setYtMusicAccountName("YouTube Music");
			}
			addSavedPlaylistEntry(plId);
			config.save();
			refreshPlaylistsAsync(null);
			return true;
		}

		// 3. Check if input is a YouTube Channel / @Handle URL
		Matcher hm = HANDLE_PATTERN.matcher(input);
		if (hm.find()) {
			String handle = URLDecoder.decode(hm.group(1), StandardCharsets.UTF_8);
			config.setYtMusicAccountName(handle);
			config.save();
			refreshPlaylistsAsync(null);
			return true;
		}

		// 4. Otherwise treat as YouTube Music Account / Display Name
		config.setYtMusicAccountName(input);
		config.save();
		refreshPlaylistsAsync(null);
		return true;
	}

	/**
	 * Adds a YouTube Music playlist URL/ID, single song URL, or runs a live YouTube Music search query!
	 */
	public YtMusicPlaylist addPlaylistOrSearchFromInput(String rawInput) {
		if (rawInput == null || rawInput.isBlank()) {
			return null;
		}
		String input = rawInput.trim();

		// 1. Check if it's a playlist URL or playlist ID
		String plId = extractPlaylistId(input);
		if (plId != null) {
			addSavedPlaylistEntry(plId);
			config.save();
			for (YtMusicPlaylist existing : playlists) {
				if (plId.equals(existing.id())) {
					loadPlaylistTracks(existing, true);
					return existing;
				}
			}
			YtMusicPlaylist placeholder = new YtMusicPlaylist(plId, "YT Music Playlist", accountName(), 0, false);
			List<YtMusicPlaylist> mod = new ArrayList<>(this.playlists);
			mod.add(placeholder);
			this.playlists = Collections.unmodifiableList(mod);
			loadPlaylistTracks(placeholder, true);
			return placeholder;
		}

		// 2. Otherwise treat as a live YouTube Music Search query (or single video URL)!
		String searchId = SEARCH_PLAYLIST_PREFIX + input;
		String prefix = SpotifyManager.isGermanLocale() ? "Suche: " : "Search: ";
		String displayTitle = input.startsWith("http") ? "YT Music Link" : (prefix + input);
		YtMusicPlaylist searchPl = new YtMusicPlaylist(searchId, displayTitle, "YouTube Music", 0, true);
		List<YtMusicPlaylist> mod = new ArrayList<>(this.playlists);
		mod.removeIf(p -> searchId.equals(p.id()));
		mod.addFirst(searchPl);
		this.playlists = Collections.unmodifiableList(mod);
		loadPlaylistTracks(searchPl, true);
		return searchPl;
	}

	public static String extractPlaylistId(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String trimmed = input.trim();
		if (trimmed.equalsIgnoreCase("LM") || trimmed.equalsIgnoreCase("LL")) {
			return LIKED_MUSIC_ID;
		}
		Matcher bm = BROWSE_ALBUM_PATTERN.matcher(trimmed);
		if (bm.find()) {
			return "browse:" + bm.group(1);
		}
		Matcher m = PLAYLIST_ID_PATTERN.matcher(trimmed);
		if (m.find()) {
			String fullMatch = m.group(0);
			if (fullMatch.contains("list=") || fullMatch.contains("browse/VL")) {
				return m.group(1);
			}
			return fullMatch;
		}
		return null;
	}

	public void refreshUserPlaylists() {
		refreshPlaylistsAsync(null);
	}

	public void refreshPlaylistsAsync(Runnable onCompleteClientThread) {
		if (!isAccountLinked()) {
			this.playlists = Collections.emptyList();
			return;
		}
		this.loadingPlaylists = true;
		executor.submit(() -> {
			try {
				Map<String, YtMusicPlaylist> loaded = new LinkedHashMap<>();

				// Keep any active search result playlists at the top
				for (YtMusicPlaylist existing : this.playlists) {
					if (existing.searchResult()) {
						loaded.put(existing.id(), existing);
					}
				}

				// 1. Always add "Liked Music" (LM) first if saved or if Browser / cookies.txt is active
				boolean hasCookieAuth = (config.ytMusicCookieBrowser() != null && !config.ytMusicCookieBrowser().isBlank())
						|| resolveCookiesFile() != null;
				if (hasCookieAuth || config.ytMusicSavedPlaylists().contains(LIKED_MUSIC_ID)) {
					List<YtMusicTrack> existingLm = playlistTracksCache.get(LIKED_MUSIC_ID);
					loaded.put(LIKED_MUSIC_ID, new YtMusicPlaylist(
							LIKED_MUSIC_ID,
							SpotifyManager.isGermanLocale() ? "Mag ich (Liked Music)" : "Liked Music",
							accountName(),
							existingLm != null ? existingLm.size() : 0,
							false
					));
				}

				// 2. If Browser Cookie Sync or cookies.txt is configured, automatically discover all saved playlists
				//    in the user's YouTube Library (including playlists created by other users!)
				if (hasCookieAuth) {
					discoverLibraryPlaylists("https://www.youtube.com/feed/playlists", true, loaded);
				}

				// 3. If user linked a @Handle or channel name, also discover playlists from their /playlists tab
				String acc = config.ytMusicAccountName();
				if (acc != null && !acc.isBlank() && !acc.contains(" ")) {
					String handle = acc.startsWith("@") ? acc : ("@" + acc);
					discoverLibraryPlaylists("https://www.youtube.com/" + handle + "/playlists", false, loaded);
				}

				// 4. Add all explicitly saved playlists immediately so the UI list is never blocked by large playlists,
				//    then load their metadata/tracks!
				for (String savedId : config.ytMusicSavedPlaylists()) {
					if (savedId == null || savedId.isBlank() || loaded.containsKey(savedId)) {
						continue;
					}
					List<YtMusicTrack> existingTracks = playlistTracksCache.get(savedId);
					loaded.put(savedId, new YtMusicPlaylist(
							savedId,
							"Playlist " + savedId,
							accountName(),
							existingTracks != null ? existingTracks.size() : 0,
							false
					));
				}

				this.playlists = List.copyOf(loaded.values());

				// 5. Fetch metadata & full track lists in background for saved playlists (including 800+ song Liked Music)
				for (String savedId : config.ytMusicSavedPlaylists()) {
					if (savedId == null || savedId.isBlank()) {
						continue;
					}
					List<YtMusicTrack> existingTracks = playlistTracksCache.get(savedId);
					if (existingTracks == null || existingTracks.isEmpty()) {
						YtMusicPlaylist fetched = fetchPlaylistMetadataAndTracks(savedId);
						if (fetched != null) {
							loaded.put(savedId, fetched);
							this.playlists = List.copyOf(loaded.values());
						}
					}
				}
			} catch (Exception e) {
				Mp3PlayerMod.LOGGER.warn("Failed to refresh YouTube Music playlists", e);
			} finally {
				this.loadingPlaylists = false;
				if (onCompleteClientThread != null) {
					Minecraft mc = Minecraft.getInstance();
					if (mc != null) {
						mc.execute(onCompleteClientThread);
					}
				}
			}
		});
	}

	private void discoverLibraryPlaylists(
			String tabUrl,
			boolean useCookies,
			Map<String, YtMusicPlaylist> loaded
	) {
		Path ytDlp = spotifyManager.findOrProvisionYtDlp();
		if (ytDlp == null) {
			return;
		}
		JsonObject root = runYtDlpDumpCommand(ytDlp, tabUrl, useCookies ? config.ytMusicCookieBrowser() : null);
		if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
			return;
		}
		JsonArray entries = root.getAsJsonArray("entries");
		for (JsonElement el : entries) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject obj = el.getAsJsonObject();
			String id = getJsonString(obj, "id");
			if ("WL".equalsIgnoreCase(id) || "LL".equalsIgnoreCase(id) || "LM".equalsIgnoreCase(id)) {
				continue;
			}
			String url = getJsonString(obj, "url");
			String plId = extractPlaylistId(id != null ? id : url);
			if (plId == null && url != null) {
				plId = extractPlaylistId(url);
			}
			String title = getJsonString(obj, "title");
			if (plId != null && !LIKED_MUSIC_ID.equalsIgnoreCase(plId) && title != null && !title.isBlank() && !loaded.containsKey(plId)) {
				String owner = getJsonString(obj, "uploader");
				if (owner == null || owner.isBlank() || "Playlist".equalsIgnoreCase(owner) || "View full playlist".equalsIgnoreCase(owner)) {
					owner = accountName();
				}
				loaded.put(plId, new YtMusicPlaylist(plId, title, cleanArtistName(owner), 0, false));
			}
		}
	}

	public void removePlaylist(String playlistId) {
		if (playlistId == null) {
			return;
		}
		List<String> updated = new ArrayList<>(config.ytMusicSavedPlaylists());
		if (updated.removeIf(s -> s.equals(playlistId))) {
			config.setYtMusicSavedPlaylists(updated);
			config.save();
		}
		playlistTracksCache.remove(playlistId);
		List<YtMusicPlaylist> mod = new ArrayList<>(this.playlists);
		mod.removeIf(p -> playlistId.equals(p.id()));
		this.playlists = Collections.unmodifiableList(mod);
	}

	public void loadPlaylistTracks(YtMusicPlaylist playlist) {
		loadPlaylistTracks(playlist, false);
	}

	public void loadPlaylistTracks(YtMusicPlaylist playlist, boolean forceRefresh) {
		if (playlist == null) {
			return;
		}
		if (!forceRefresh) {
			List<YtMusicTrack> cached = playlistTracksCache.get(playlist.id());
			if (cached != null && !cached.isEmpty()) {
				return;
			}
		}

		this.loadingTracks = true;
		executor.submit(() -> {
			try {
				if (playlist.id().startsWith(SEARCH_PLAYLIST_PREFIX)) {
					String query = playlist.id().substring(SEARCH_PLAYLIST_PREFIX.length()).trim();
					List<YtMusicTrack> results = runYtMusicQueryTracks(query);
					playlistTracksCache.put(playlist.id(), List.copyOf(results));
					updatePlaylistEntryInList(new YtMusicPlaylist(
							playlist.id(),
							playlist.name(),
							playlist.owner(),
							results.size(),
							true
					));
				} else {
					YtMusicPlaylist updated = fetchPlaylistMetadataAndTracks(playlist.id());
					if (updated != null) {
						updatePlaylistEntryInList(updated);
					}
				}
			} catch (Exception e) {
				Mp3PlayerMod.LOGGER.warn("Failed to load YouTube Music tracks for {}", playlist.name(), e);
			} finally {
				this.loadingTracks = false;
			}
		});
	}

	private void updatePlaylistEntryInList(YtMusicPlaylist updated) {
		if (updated == null) {
			return;
		}
		List<YtMusicPlaylist> mod = new ArrayList<>(this.playlists);
		for (int i = 0; i < mod.size(); i++) {
			if (mod.get(i).id().equals(updated.id())) {
				mod.set(i, updated);
				this.playlists = Collections.unmodifiableList(mod);
				return;
			}
		}
	}

	private static final String[] CANDIDATE_BROWSERS = {
			"firefox", "edge", "chrome", "brave", "opera", "opera:Opera GX Stable", "vivaldi"
	};

	private YtMusicPlaylist fetchPlaylistMetadataAndTracks(String playlistId) {
		Path ytDlp = spotifyManager.findOrProvisionYtDlp();
		if (ytDlp == null) {
			return null;
		}
		boolean de = SpotifyManager.isGermanLocale();
		boolean isLikedMusic = LIKED_MUSIC_ID.equalsIgnoreCase(playlistId) || "LL".equalsIgnoreCase(playlistId);
		setStatusMessage(
				de ? "Lade YouTube Music Songs (auch große Listen mit 800+ Songs)..."
						: "Loading YouTube Music tracks (supports 800+ track playlists)...",
				35_000L
		);

		List<YtMusicTrack> tracks = new ArrayList<>();
		String[] metaHolder = new String[2]; // [0]=title, [1]=uploader

		if (isLikedMusic) {
			// Try YouTube Music's "LM" (Liked Music) and YouTube's "LL" (Liked videos),
			// streaming tracks page-by-page so 800+ song lists appear immediately without blocking!
			Map<String, YtMusicTrack> merged = new LinkedHashMap<>();
			streamPlaylistTracksWithCookieFallback(
					ytDlp,
					playlistId,
					"https://music.youtube.com/playlist?list=LM",
					true,
					merged,
					metaHolder
			);
			streamPlaylistTracksWithCookieFallback(
					ytDlp,
					playlistId,
					"https://www.youtube.com/playlist?list=LL",
					true,
					merged,
					metaHolder
			);
			tracks.addAll(merged.values());
			if (tracks.isEmpty()) {
				setStatusMessage(
						de ? "Für 'Liked Music' bitte Firefox nutzen oder cookies.txt im Web-Portal einfügen (Chrome/Edge sperren Cookies)!"
								: "For 'Liked Music', please use Firefox or paste cookies.txt in the Web Portal (Chrome/Edge lock cookies)!",
						12_000L
				);
				return null;
			}
		} else {
			String url = playlistId.startsWith("browse:")
					? ("https://music.youtube.com/browse/" + playlistId.substring("browse:".length()))
					: ("https://music.youtube.com/playlist?list=" + playlistId);
			Map<String, YtMusicTrack> merged = new LinkedHashMap<>();
			streamPlaylistTracksWithCookieFallback(ytDlp, playlistId, url, false, merged, metaHolder);
			if (merged.isEmpty() && !playlistId.startsWith("browse:")) {
				streamPlaylistTracksWithCookieFallback(
						ytDlp,
						playlistId,
						"https://www.youtube.com/playlist?list=" + playlistId,
						false,
						merged,
						metaHolder
				);
			}
			tracks.addAll(merged.values());
			if (tracks.isEmpty()) {
				return null;
			}
		}

		String title = metaHolder[0];
		if (isLikedMusic || title == null || title.isBlank() || "Liked videos".equalsIgnoreCase(title)) {
			if (isLikedMusic) {
				title = de ? "Mag ich (Liked Music)" : "Liked Music";
			} else if (title == null || title.isBlank()) {
				title = "Playlist " + playlistId;
			}
		}
		String uploader = metaHolder[1];
		if (uploader == null || uploader.isBlank()) {
			uploader = accountName();
		}

		playlistTracksCache.put(playlistId, List.copyOf(tracks));
		YtMusicPlaylist finalPl = new YtMusicPlaylist(
				playlistId,
				title,
				cleanArtistName(uploader),
				tracks.size(),
				false
		);
		updatePlaylistEntryInList(finalPl);
		setStatusMessage(
				de ? ("Playlist geladen (" + tracks.size() + " Songs)") : ("Loaded playlist (" + tracks.size() + " tracks)"),
				3500L
		);
		return finalPl;
	}

	private void streamPlaylistTracksWithCookieFallback(
			Path ytDlp,
			String playlistId,
			String url,
			boolean requireAuth,
			Map<String, YtMusicTrack> outTracks,
			String[] metaHolder
	) {
		int before = outTracks.size();
		Path cookiesFile = resolveCookiesFile();
		if (cookiesFile != null) {
			streamPlaylistTracksFromYtDlp(ytDlp, playlistId, url, null, cookiesFile, outTracks, metaHolder);
			if (outTracks.size() > before) {
				return;
			}
		}

		String configuredBrowser = config.ytMusicCookieBrowser();
		if (configuredBrowser != null && !configuredBrowser.isBlank()) {
			streamPlaylistTracksFromYtDlp(ytDlp, playlistId, url, configuredBrowser, null, outTracks, metaHolder);
			if (outTracks.size() > before) {
				return;
			}
		}

		if (!requireAuth) {
			streamPlaylistTracksFromYtDlp(ytDlp, playlistId, url, null, null, outTracks, metaHolder);
			if (outTracks.size() > before) {
				return;
			}
		}

		for (String candidate : CANDIDATE_BROWSERS) {
			if (candidate.equalsIgnoreCase(configuredBrowser)) {
				continue;
			}
			streamPlaylistTracksFromYtDlp(ytDlp, playlistId, url, candidate, null, outTracks, metaHolder);
			if (outTracks.size() > before) {
				config.setYtMusicCookieBrowser(candidate);
				config.save();
				return;
			}
		}
	}

	/**
	 * Streams flat-playlist entries line-by-line (-j) with stderr discarded at the OS level so that
	 * large playlists (e.g. 813+ Liked Music tracks across 9+ pages) never deadlock on stderr pipe buffers
	 * and populate the UI progressively every 25 tracks as each page arrives!
	 */
	private void streamPlaylistTracksFromYtDlp(
			Path ytDlp,
			String playlistId,
			String url,
			String cookieBrowser,
			Path cookiesFile,
			Map<String, YtMusicTrack> outTracks,
			String[] metaHolder
	) {
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add(ytDlp.toString());
			cmd.add("--flat-playlist");
			cmd.add("-j");
			cmd.add("--no-warnings");
			cmd.add("--extractor-args");
			cmd.add("youtubetab:skip=authcheck");
			if (cookiesFile != null && Files.isRegularFile(cookiesFile)) {
				cmd.add("--cookies");
				cmd.add(cookiesFile.toString());
			} else if (cookieBrowser != null && !cookieBrowser.isBlank()) {
				cmd.add("--cookies-from-browser");
				cmd.add(cookieBrowser);
			}
			cmd.add(url);

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process proc = pb.start();
			int addedSinceLastFlush = 0;

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.charAt(0) != '{') {
						continue;
					}
					try {
						JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
						if (metaHolder[0] == null) {
							String plTitle = getJsonString(obj, "playlist_title");
							if (plTitle != null && !plTitle.isBlank()) {
								metaHolder[0] = plTitle;
							}
						}
						if (metaHolder[1] == null) {
							String plUploader = getJsonString(obj, "playlist_uploader");
							if (plUploader != null && !plUploader.isBlank()) {
								metaHolder[1] = plUploader;
							}
						}
						YtMusicTrack track = parseSingleEntry(obj);
						if (track != null && !outTracks.containsKey(track.id())) {
							outTracks.put(track.id(), track);
							addedSinceLastFlush++;
							if (addedSinceLastFlush >= 25) {
								addedSinceLastFlush = 0;
								List<YtMusicTrack> snapshot = List.copyOf(outTracks.values());
								playlistTracksCache.put(playlistId, snapshot);
								String displayTitle = LIKED_MUSIC_ID.equalsIgnoreCase(playlistId)
										? (SpotifyManager.isGermanLocale() ? "Mag ich (Liked Music)" : "Liked Music")
										: (metaHolder[0] != null ? metaHolder[0] : ("Playlist " + playlistId));
								updatePlaylistEntryInList(new YtMusicPlaylist(
										playlistId,
										displayTitle,
										metaHolder[1] != null ? cleanArtistName(metaHolder[1]) : accountName(),
										snapshot.size(),
										false
								));
							}
						}
					} catch (Exception ignored) {
					}
				}
			}
			proc.waitFor();
			if (!outTracks.isEmpty()) {
				playlistTracksCache.put(playlistId, List.copyOf(outTracks.values()));
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("yt-dlp stream failed for {}: {}", url, e.getMessage());
		}
	}

	private Path resolveCookiesFile() {
		Path binCookies = spotifyManager.cacheDirectory().getParent().resolve(".bin").resolve("ytm_cookies.txt");
		try {
			if (Files.isRegularFile(binCookies) && Files.size(binCookies) > 32L) {
				return binCookies;
			}
			Path rootCookies = spotifyManager.cacheDirectory().getParent().resolve("cookies.txt");
			if (Files.isRegularFile(rootCookies) && Files.size(rootCookies) > 32L) {
				return rootCookies;
			}
		} catch (IOException ignored) {
		}
		return null;
	}

	private List<YtMusicTrack> runYtMusicQueryTracks(String query) {
		Path ytDlp = spotifyManager.findOrProvisionYtDlp();
		if (ytDlp == null || query == null || query.isBlank()) {
			return Collections.emptyList();
		}
		boolean de = SpotifyManager.isGermanLocale();
		setStatusMessage(
				(de ? "Suche auf YouTube Music: " : "Searching YouTube Music: ") + query + " ...",
				15_000L
		);

		Matcher vm = VIDEO_ID_PATTERN.matcher(query);
		String targetSpec;
		if (vm.find()) {
			targetSpec = "https://www.youtube.com/watch?v=" + vm.group(1);
		} else if (query.startsWith("http://") || query.startsWith("https://")) {
			targetSpec = query;
		} else {
			targetSpec = "ytsearch15:" + query;
		}

		JsonObject root = executeYtDlpJsonDump(ytDlp, targetSpec, false);
		if (root == null) {
			return Collections.emptyList();
		}
		if (root.has("entries") && root.get("entries").isJsonArray()) {
			List<YtMusicTrack> list = parseEntriesArray(root);
			setStatusMessage(
					de ? (list.size() + " Treffer gefunden") : ("Found " + list.size() + " results"),
					3000L
			);
			return list;
		}
		YtMusicTrack single = parseSingleEntry(root);
		if (single != null) {
			return List.of(single);
		}
		return Collections.emptyList();
	}

	private JsonObject executeYtDlpJsonDump(Path ytDlp, String urlOrSearch, boolean allowBrowserCookies) {
		String cookieBrowser = config.ytMusicCookieBrowser();
		if (allowBrowserCookies && cookieBrowser != null && !cookieBrowser.isBlank()) {
			JsonObject withCookies = runYtDlpDumpCommand(ytDlp, urlOrSearch, cookieBrowser);
			if (hasValidEntries(withCookies)) {
				return withCookies;
			}
		}
		JsonObject noCookies = runYtDlpDumpCommand(ytDlp, urlOrSearch, null);
		if (hasValidEntries(noCookies)) {
			return noCookies;
		}
		if (allowBrowserCookies) {
			return probeBrowsersForJsonDump(ytDlp, urlOrSearch, cookieBrowser);
		}
		return noCookies;
	}

	private JsonObject executeYtDlpJsonDumpRequireCookies(Path ytDlp, String url) {
		String cookieBrowser = config.ytMusicCookieBrowser();
		if (cookieBrowser != null && !cookieBrowser.isBlank()) {
			JsonObject withCookies = runYtDlpDumpCommand(ytDlp, url, cookieBrowser);
			if (hasValidEntries(withCookies)) {
				return withCookies;
			}
		}
		return probeBrowsersForJsonDump(ytDlp, url, cookieBrowser);
	}

	private JsonObject probeBrowsersForJsonDump(Path ytDlp, String url, String alreadyTried) {
		for (String candidate : CANDIDATE_BROWSERS) {
			if (candidate.equalsIgnoreCase(alreadyTried)) {
				continue;
			}
			JsonObject res = runYtDlpDumpCommand(ytDlp, url, candidate);
			if (hasValidEntries(res)) {
				config.setYtMusicCookieBrowser(candidate);
				config.save();
				return res;
			}
		}
		return null;
	}

	private static boolean hasValidEntries(JsonObject root) {
		if (root == null) {
			return false;
		}
		if (root.has("entries") && root.get("entries").isJsonArray()) {
			return !root.getAsJsonArray("entries").isEmpty();
		}
		return root.has("id") && root.has("title");
	}

	private JsonObject runYtDlpDumpCommand(Path ytDlp, String urlOrSearch, String cookieBrowser) {
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add(ytDlp.toString());
			cmd.add("--flat-playlist");
			cmd.add("--dump-single-json");
			cmd.add("--no-warnings");
			cmd.add("--extractor-args");
			cmd.add("youtubetab:skip=authcheck");
			Path cookiesFile = resolveCookiesFile();
			if (cookiesFile != null) {
				cmd.add("--cookies");
				cmd.add(cookiesFile.toString());
			} else if (cookieBrowser != null && !cookieBrowser.isBlank()) {
				cmd.add("--cookies-from-browser");
				cmd.add(cookieBrowser);
			}
			cmd.add(urlOrSearch);

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process proc = pb.start();
			String stdout;
			try (InputStream in = proc.getInputStream()) {
				stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			}
			proc.waitFor();
			if (stdout.isEmpty()) {
				return null;
			}
			int firstBrace = stdout.indexOf('{');
			if (firstBrace > 0) {
				stdout = stdout.substring(firstBrace);
			}
			return JsonParser.parseString(stdout).getAsJsonObject();
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("yt-dlp JSON dump failed for {}: {}", urlOrSearch, e.getMessage());
			return null;
		}
	}

	private static List<YtMusicTrack> parseEntriesArray(JsonObject root) {
		if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
			return Collections.emptyList();
		}
		List<YtMusicTrack> tracks = new ArrayList<>();
		JsonArray arr = root.getAsJsonArray("entries");
		for (JsonElement el : arr) {
			if (!el.isJsonObject()) {
				continue;
			}
			YtMusicTrack track = parseSingleEntry(el.getAsJsonObject());
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private static YtMusicTrack parseSingleEntry(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		String id = getJsonString(obj, "id");
		String rawTitle = getJsonString(obj, "title");
		if (id == null || id.isBlank() || rawTitle == null || rawTitle.isBlank()) {
			return null;
		}
		if ("[Private video]".equalsIgnoreCase(rawTitle) || "[Deleted video]".equalsIgnoreCase(rawTitle)) {
			return null;
		}

		String artist = getJsonString(obj, "artist");
		if (artist == null || artist.isBlank()) {
			artist = getJsonString(obj, "channel");
		}
		if (artist == null || artist.isBlank()) {
			artist = getJsonString(obj, "uploader");
		}
		artist = cleanArtistName(artist);

		String title = rawTitle.trim();
		// If title is "Artist - Song Title" and channel is not a Topic channel, split cleanly
		int dashIdx = title.indexOf(" - ");
		if (dashIdx > 1 && dashIdx + 3 < title.length() && (artist.isEmpty() || !rawTitle.startsWith(artist))) {
			String leftArtist = title.substring(0, dashIdx).trim();
			String rightTitle = title.substring(dashIdx + 3).trim();
			if (!rightTitle.isEmpty()) {
				artist = leftArtist;
				title = rightTitle;
			}
		}

		int durationSec = 0;
		if (obj.has("duration") && !obj.get("duration").isJsonNull()) {
			try {
				durationSec = Math.max(1, (int) Math.round(obj.get("duration").getAsDouble()));
			} catch (Exception ignored) {
			}
		}
		return new YtMusicTrack(id, title, artist, durationSec > 0 ? durationSec : 180);
	}

	private static String cleanArtistName(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String s = raw.trim();
		if (s.endsWith(" - Topic")) {
			s = s.substring(0, s.length() - " - Topic".length()).trim();
		}
		return s;
	}

	// ===================== EXACT VIDEO-ID AUDIO RESOLUTION & CACHING =====================

	public void preCacheTrack(YtMusicTrack track) {
		if (track == null || findCachedFile(track.id()) != null) {
			return;
		}
		resolveTrackAsync(track, null, null);
	}

	public Mp3TrackInfo findCachedTrack(YtMusicTrack track) {
		if (track == null) {
			return null;
		}
		Path cachedFile = findCachedFile(track.id());
		if (cachedFile != null) {
			try {
				Mp3TrackInfo parsed = AudioMetadataParsers.parse(cachedFile);
				return overrideYtMusicMetadata(parsed, track);
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	public CompletableFuture<Mp3TrackInfo> resolveOrDownloadTrack(YtMusicTrack track) {
		CompletableFuture<Mp3TrackInfo> result = new CompletableFuture<>();
		resolveTrackAsync(track, result::complete, err -> result.complete(null));
		return result;
	}

	public void resolveTrackAsync(
			YtMusicTrack track,
			Consumer<Mp3TrackInfo> onReadyClientThread,
			Consumer<String> onErrorClientThread
	) {
		if (track == null) {
			return;
		}
		Mp3TrackInfo cached = findCachedTrack(track);
		if (cached != null) {
			if (onReadyClientThread != null) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && !mc.isSameThread()) {
					mc.execute(() -> onReadyClientThread.accept(cached));
				} else {
					onReadyClientThread.accept(cached);
				}
			}
			return;
		}

		downloadingTrackIds.add(track.id());
		boolean de = SpotifyManager.isGermanLocale();
		setStatusMessage(
				(de ? "Lade YT Music Song: " : "Buffering YT Music track: ") + track.fullDisplayLabel() + " ...",
				25_000L
		);

		CompletableFuture<Mp3TrackInfo> future = activeDownloads.computeIfAbsent(
				track.id(),
				k -> CompletableFuture.supplyAsync(() -> downloadAndParseTrack(track), executor)
		);

		future.whenComplete((info, err) -> {
			downloadingTrackIds.remove(track.id());
			activeDownloads.remove(track.id());
			Minecraft mc = Minecraft.getInstance();
			if (info != null && err == null) {
				setStatusMessage((SpotifyManager.isGermanLocale() ? "Bereit: " : "Ready: ") + track.displayTitle(), 2500L);
				if (onReadyClientThread != null && mc != null) {
					mc.execute(() -> onReadyClientThread.accept(info));
				}
			} else {
				String msg = (SpotifyManager.isGermanLocale()
						? "YT Music Stream konnte nicht geladen werden: "
						: "Failed to load YT Music stream: ") + track.displayTitle();
				setStatusMessage(msg, 4500L);
				if (onErrorClientThread != null && mc != null) {
					mc.execute(() -> onErrorClientThread.accept(msg));
				}
			}
		});
	}

	private Mp3TrackInfo downloadAndParseTrack(YtMusicTrack track) {
		try {
			Path cacheDir = spotifyManager.cacheDirectory();
			Files.createDirectories(cacheDir);
			String safeId = track.id().replaceAll("[^a-zA-Z0-9_-]", "");

			Path ytDlp = spotifyManager.findOrProvisionYtDlp();
			if (ytDlp != null) {
				String videoUrl = "https://www.youtube.com/watch?v=" + safeId;
				Path downloaded = runYtDlpDirectVideoDownload(ytDlp, cacheDir, safeId, videoUrl);
				if (downloaded != null && Files.isRegularFile(downloaded) && Files.size(downloaded) > 1024L) {
					Mp3TrackInfo parsed = AudioMetadataParsers.parse(downloaded);
					return overrideYtMusicMetadata(parsed, track);
				}
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Failed to resolve YouTube Music audio for {}", track.fullDisplayLabel(), e);
		}
		throw new RuntimeException("Could not resolve YouTube Music track " + track.fullDisplayLabel());
	}

	private Path runYtDlpDirectVideoDownload(Path ytDlp, Path cacheDir, String safeId, String videoUrl) {
		try {
			String outTemplate = cacheDir.resolve("ytm_" + safeId + ".%(ext)s").toString();
			List<String> cmd = new ArrayList<>();
			cmd.add(ytDlp.toString());
			cmd.add(videoUrl);
			cmd.add("-f");
			cmd.add("bestaudio[ext=m4a]/bestaudio[acodec=opus]/bestaudio");
			cmd.add("-N");
			cmd.add("8");
			cmd.add("--no-playlist");
			cmd.add("--no-warnings");

			Path ffmpeg = spotifyManager.findFfmpegDirectory();
			if (ffmpeg != null) {
				cmd.add("-x");
				cmd.add("--audio-format");
				cmd.add("mp3");
				cmd.add("--ffmpeg-location");
				cmd.add(ffmpeg.toString());
			}

			cmd.add("-o");
			cmd.add(outTemplate);

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			try (InputStream in = proc.getInputStream()) {
				in.readAllBytes();
			}
			proc.waitFor();
			return findCachedFile(safeId);
		} catch (Exception e) {
			return null;
		}
	}

	private Path findCachedFile(String trackId) {
		Path cacheDir = spotifyManager.cacheDirectory();
		if (trackId == null || trackId.isBlank() || !Files.isDirectory(cacheDir)) {
			return null;
		}
		String safeId = trackId.replaceAll("[^a-zA-Z0-9_-]", "");
		String[] exts = {".mp3", ".m4a", ".opus", ".ogg", ".wav", ".flac"};
		for (String ext : exts) {
			Path candidate = cacheDir.resolve("ytm_" + safeId + ext);
			try {
				if (Files.isRegularFile(candidate) && Files.size(candidate) > 1024L) {
					return candidate;
				}
			} catch (IOException ignored) {
			}
		}
		return null;
	}

	private static Mp3TrackInfo overrideYtMusicMetadata(Mp3TrackInfo parsed, YtMusicTrack track) {
		return new Mp3TrackInfo(
				parsed.file(),
				parsed.fileName(),
				parsed.format(),
				track.displayTitle(),
				track.displayArtist(),
				parsed.fileSize(),
				parsed.audioStartOffset(),
				parsed.audioByteLength(),
				parsed.durationSeconds() > 0 ? parsed.durationSeconds() : track.durationSeconds(),
				parsed.bitrateKbps(),
				parsed.sampleRate()
		);
	}

	private void addSavedPlaylistEntry(String playlistId) {
		if (playlistId == null || playlistId.isBlank()) {
			return;
		}
		List<String> list = new ArrayList<>(config.ytMusicSavedPlaylists());
		if (!list.contains(playlistId)) {
			list.add(playlistId);
			config.setYtMusicSavedPlaylists(list);
		}
	}

	// ===================== LOCAL BROWSER LINKING PORTAL =====================

	private synchronized void ensurePortalServerRunning() {
		if (portalServer != null) {
			return;
		}
		int[] candidatePorts = {DEFAULT_PORTAL_PORT, 27847, 27848};
		for (int port : candidatePorts) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
				this.activePortalPort = port;
				server.createContext("/ytmusic", this::handlePortalPage);
				server.createContext("/link", this::handlePortalSubmit);
				server.setExecutor(executor);
				server.start();
				this.portalServer = server;
				return;
			} catch (IOException ignored) {
			}
		}
	}

	public synchronized void stopPortalServer() {
		if (portalServer != null) {
			try {
				portalServer.stop(0);
			} catch (Exception ignored) {
			}
			portalServer = null;
		}
	}

	private void handlePortalPage(HttpExchange exchange) throws IOException {
		String savedAccount = escapeHtml(config.ytMusicAccountName());
		String defaultLang = SpotifyManager.isGermanLocale() ? "de" : "en";
		String html = """
				<!DOCTYPE html>
				<html lang="%DEFAULT_LANG%">
				<head>
				<meta charset="UTF-8">
				<title>Minecraft MP3 Player – Link YouTube Music</title>
				<style>
				  body { background: #0b1120; color: #f8fafc; font-family: system-ui, -apple-system, sans-serif; display: flex; justify-content: center; padding: 36px 16px; margin: 0; }
				  .card { background: #111827; border: 1px solid #ff0033; border-radius: 12px; max-width: 660px; width: 100%; padding: 28px; box-shadow: 0 12px 32px rgba(0,0,0,0.5); position: relative; }
				  .lang-switch { position: absolute; top: 22px; right: 24px; display: flex; gap: 6px; }
				  .lang-btn { background: #1e293b; color: #94a3b8; border: 1px solid #334155; padding: 4px 10px; border-radius: 5px; cursor: pointer; font-size: 12px; font-weight: 700; width: auto; }
				  .lang-btn.active { background: #ff0033; color: #ffffff; border-color: #ff4e6a; }
				  h1 { color: #ff4e6a; margin: 0 0 8px 0; font-size: 22px; padding-right: 90px; }
				  h2 { color: #e2e8f0; font-size: 16px; margin: 24px 0 10px 0; border-bottom: 1px solid #1e293b; padding-bottom: 6px; }
				  p, li { color: #cbd5e1; font-size: 13.5px; line-height: 1.6; }
				  input, textarea, select { width: 100%; box-sizing: border-box; background: #070b14; border: 1px solid #334155; color: #f8fafc; padding: 10px 12px; border-radius: 6px; margin-top: 6px; margin-bottom: 12px; font-size: 14px; }
				  button.primary { background: #ff0033; color: #ffffff; font-weight: 700; border: none; padding: 10px 18px; border-radius: 6px; cursor: pointer; font-size: 14px; width: 100%; }
				  button.primary:hover { background: #ff2a55; }
				  code { background: #070b14; color: #38bdf8; padding: 2px 6px; border-radius: 4px; }
				</style>
				<script>
				  function setLang(lang) {
				    document.getElementById('view-en').style.display = (lang === 'en') ? 'block' : 'none';
				    document.getElementById('view-de').style.display = (lang === 'de') ? 'block' : 'none';
				    document.getElementById('btn-en').className = (lang === 'en') ? 'lang-btn active' : 'lang-btn';
				    document.getElementById('btn-de').className = (lang === 'de') ? 'lang-btn active' : 'lang-btn';
				  }
				  window.onload = function() { setLang('%DEFAULT_LANG%'); };
				</script>
				</head>
				<body>
				<div class="card">
				  <div class="lang-switch">
				    <button type="button" id="btn-en" class="lang-btn" onclick="setLang('en')">EN</button>
				    <button type="button" id="btn-de" class="lang-btn" onclick="setLang('de')">DE</button>
				  </div>

				  <!-- ENGLISH VIEW -->
				  <div id="view-en">
				    <h1>Link YouTube Music with Minecraft</h1>
				    <p>Link your YouTube Music account to browse and play your full playlists (no 100-song cap) and use live search directly inside Minecraft on Headphones, Boomboxes, and Speaker Networks:</p>

				    <form action="/link" method="POST">
				      <h2>1. Your YouTube Music Account / Channel Name</h2>
				      <label><b>Your Name, @Handle, or YouTube Channel URL:</b></label>
				      <input type="text" name="account" value="%ACCOUNT%" placeholder="e.g. YourName or https://www.youtube.com/@YourHandle" required />

				      <h2>2. Your YouTube Music Playlists (Optional – you can also add more in-game anytime)</h2>
				      <label><b>YouTube Music / YouTube Playlist URLs (one per line):</b></label>
				      <textarea name="playlists" rows="3" placeholder="https://music.youtube.com/playlist?list=PL..."></textarea>

				      <h2>3. Optional: Browser Session / cookies.txt for Private Playlists & "Liked Music" (800+ Songs)</h2>
				      <label><b>Select Browser (Firefox works directly; Chrome/Edge/Brave/Opera on Windows lock cookies via DPAPI):</b></label>
				      <select name="browser">
				        <option value="">No Browser Cookie Sync (Public / Unlisted Playlists & Live Search only)</option>
				        <option value="firefox">Firefox (Recommended – Direct Auto-Sync)</option>
				        <option value="edge">Microsoft Edge</option>
				        <option value="chrome">Google Chrome</option>
				        <option value="brave">Brave</option>
				        <option value="opera">Opera</option>
				        <option value="opera:Opera GX Stable">Opera GX</option>
				        <option value="vivaldi">Vivaldi</option>
				      </select>
				      <label><b>For Chrome / Edge / Brave / Opera GX Users: Paste Netscape <code>cookies.txt</code> content here (e.g. via "Get cookies.txt LOCALLY" extension on music.youtube.com) to unlock "Liked Music":</b></label>
				      <textarea name="cookies_txt" rows="3" placeholder="# Netscape HTTP Cookie File&#10;.youtube.com	TRUE	/	TRUE	..."></textarea>

				      <button type="submit" class="primary">Link YouTube Music with Minecraft Now</button>
				    </form>
				  </div>

				  <!-- GERMAN VIEW -->
				  <div id="view-de" style="display:none;">
				    <h1>YouTube Music mit Minecraft verknüpfen</h1>
				    <p>Verknüpfe deinen YouTube Music Account, um deine Playlists (ohne 100-Song-Limit) und die Live-Suche direkt in Minecraft über Kopfhörer, Boombox und Lautsprecher-Netzwerke abzuspielen:</p>

				    <form action="/link" method="POST">
				      <h2>1. Dein YouTube Music Account / Kanalname</h2>
				      <label><b>Dein Name, @Handle oder YouTube-Kanal-Link:</b></label>
				      <input type="text" name="account" value="%ACCOUNT%" placeholder="z. B. DeinName oder https://www.youtube.com/@DeinHandle" required />

				      <h2>2. Deine YouTube Music Playlists (Optional – jederzeit auch im Spiel ergänzbar)</h2>
				      <label><b>YouTube Music / YouTube Playlist-Links (einer pro Zeile):</b></label>
				      <textarea name="playlists" rows="3" placeholder="https://music.youtube.com/playlist?list=PL..."></textarea>

				      <h2>3. Optional: Browser-Session / cookies.txt für Private Playlists & „Mag ich"-Songs (800+ Songs)</h2>
				      <label><b>Browser auswählen (Firefox funktioniert direkt; Chrome/Edge/Brave/Opera sperren unter Windows ihre Cookie-Datenbank per DPAPI):</b></label>
				      <select name="browser">
				        <option value="">Kein Browser-Cookie-Sync (Nur öffentliche / nicht gelistete Playlists & Live-Suche)</option>
				        <option value="firefox">Firefox (Empfohlen – Direkter Auto-Sync)</option>
				        <option value="edge">Microsoft Edge</option>
				        <option value="chrome">Google Chrome</option>
				        <option value="brave">Brave</option>
				        <option value="opera">Opera</option>
				        <option value="opera:Opera GX Stable">Opera GX</option>
				        <option value="vivaldi">Vivaldi</option>
				      </select>
				      <label><b>Für Chrome / Edge / Brave / Opera GX Nutzer: Füge hier den Inhalt einer exportierten Netscape <code>cookies.txt</code> ein (z. B. mit der Browser-Erweiterung „Get cookies.txt LOCALLY" auf music.youtube.com), um „Liked Music" freizuschalten:</b></label>
				      <textarea name="cookies_txt" rows="3" placeholder="# Netscape HTTP Cookie File&#10;.youtube.com	TRUE	/	TRUE	..."></textarea>

				      <button type="submit" class="primary">YouTube Music jetzt mit Minecraft verknüpfen</button>
				    </form>
				  </div>
				</div>
				</body>
				</html>
				"""
				.replace("%DEFAULT_LANG%", defaultLang)
				.replace("%ACCOUNT%", savedAccount);

		sendHtmlResponse(exchange, 200, html);
	}

	private void handlePortalSubmit(HttpExchange exchange) throws IOException {
		String rawParams = exchange.getRequestURI().getRawQuery();
		if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			try (InputStream in = exchange.getRequestBody()) {
				String bodyStr = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				if (!bodyStr.isBlank()) {
					rawParams = (rawParams == null || rawParams.isBlank()) ? bodyStr : (rawParams + "&" + bodyStr);
				}
			}
		}
		Map<String, String> params = parseQueryParams(rawParams);
		String account = params.getOrDefault("account", "").trim();
		String rawPlaylists = params.getOrDefault("playlists", "").trim();
		String browser = params.getOrDefault("browser", "").trim();
		String cookiesTxt = params.getOrDefault("cookies_txt", "").trim();

		if (!account.isBlank()) {
			Matcher hm = HANDLE_PATTERN.matcher(account);
			if (hm.find()) {
				config.setYtMusicAccountName(URLDecoder.decode(hm.group(1), StandardCharsets.UTF_8));
			} else {
				config.setYtMusicAccountName(account);
			}
		} else if (config.ytMusicAccountName().isBlank()) {
			config.setYtMusicAccountName("YT Music User");
		}

		if (!cookiesTxt.isBlank()) {
			try {
				Path binDir = spotifyManager.cacheDirectory().getParent().resolve(".bin");
				Files.createDirectories(binDir);
				Files.writeString(binDir.resolve("ytm_cookies.txt"), cookiesTxt + System.lineSeparator(), StandardCharsets.UTF_8);
				addSavedPlaylistEntry(LIKED_MUSIC_ID);
			} catch (IOException ignored) {
			}
		}

		config.setYtMusicCookieBrowser(browser);
		if (!browser.isBlank()) {
			addSavedPlaylistEntry(LIKED_MUSIC_ID);
		}

		if (!rawPlaylists.isBlank()) {
			String[] tokens = rawPlaylists.split("[\\r\\n,;\\s]+");
			for (String tok : tokens) {
				String plId = extractPlaylistId(tok);
				if (plId != null) {
					addSavedPlaylistEntry(plId);
				}
			}
		}
		config.save();

		boolean de = SpotifyManager.isGermanLocale();
		String title = de ? "YouTube Music verknüpft!" : "YouTube Music Successfully Linked!";
		String connectedAs = de ? "Verbunden als:" : "Connected as:";
		String closeHint = de
				? "Du kannst dieses Browser-Fenster jetzt schließen und zu Minecraft zurückkehren."
				: "You can now close this browser tab and return to Minecraft.";
		String okHtml = """
				<!DOCTYPE html>
				<html>
				<head><meta charset="UTF-8"><title>%TITLE%</title>
				<style>
				  body { background: #0b1120; color: #f8fafc; font-family: system-ui, sans-serif; display: flex; justify-content: center; align-items: center; height: 90vh; margin: 0; }
				  .box { background: #111827; border: 1px solid #ff0033; border-radius: 12px; padding: 32px; text-align: center; max-width: 440px; }
				  h1 { color: #ff4e6a; margin-top: 0; font-size: 22px; }
				  p { color: #cbd5e1; }
				</style></head>
				<body><div class="box">
				  <h1>✔ %TITLE%</h1>
				  <p>%CONNECTED_AS% <b>%ACCOUNT%</b></p>
				  <p>%CLOSE_HINT%</p>
				</div></body></html>
				"""
				.replace("%TITLE%", title)
				.replace("%CONNECTED_AS%", connectedAs)
				.replace("%CLOSE_HINT%", closeHint)
				.replace("%ACCOUNT%", escapeHtml(accountName()));

		sendHtmlResponse(exchange, 200, okHtml);
		refreshPlaylistsAsync(null);
	}

	private static Map<String, String> parseQueryParams(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return Collections.emptyMap();
		}
		Map<String, String> map = new LinkedHashMap<>();
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
				String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				map.put(k, v);
			}
		}
		return map;
	}

	private static String getJsonString(JsonObject obj, String key) {
		if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
			return obj.get(key).getAsString();
		}
		return null;
	}

	private static String escapeHtml(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	private static void sendHtmlResponse(HttpExchange exchange, int status, String html) throws IOException {
		byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}

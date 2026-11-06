package de.vibecoded.mp3player.client.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.mp3.AudioFormatType;
import de.vibecoded.mp3player.mp3.AudioMetadataParsers;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Manages Spotify account linking (OAuth 2.0 PKCE & Browser Portal), playlist fetching,
 * track browsing, and automatic audio resolution/caching into local 3D-streamable audio files.
 */
public final class SpotifyManager {
	public static final String LIKED_SONGS_ID = "__liked_songs__";
	public static final String SPOTIFY_QUEUE_PREFIX = "spotify:";
	private static final int DEFAULT_CALLBACK_PORT = 8888;
	private static final String OAUTH_SCOPES =
			"playlist-read-private playlist-read-collaborative user-library-read user-read-private";

	private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile(
			"(?:spotify:(?:playlist|album):|open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(playlist|album)/)([a-zA-Z0-9]{22})"
	);
	private static final Pattern USER_ID_PATTERN = Pattern.compile(
			"(?:spotify:user:|open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?user/)([a-zA-Z0-9._-]+)"
	);
	private static final Pattern NEXT_DATA_PATTERN = Pattern.compile(
			"<script id=\"__NEXT_DATA__\" type=\"application/json\">([^<]+)</script>"
	);

	public record SpotifyPlaylist(
			String id,
			String kind, // "playlist" or "album"
			String name,
			String owner,
			int trackCount,
			boolean likedSongs
	) {
		public String displaySubtitle() {
			if (likedSongs) {
				return "Spotify";
			}
			if (owner != null && !owner.isBlank()) {
				return trackCount > 0 ? (owner + " • " + trackCount + " Songs") : owner;
			}
			return trackCount > 0 ? (trackCount + " Songs") : "Playlist";
		}
	}

	public record SpotifyTrack(
			String id,
			String title,
			String artist,
			int durationSeconds,
			String previewUrl
	) {
		public String displayTitle() {
			return (title != null && !title.isBlank()) ? title : id;
		}

		public String displayArtist() {
			return (artist != null && !artist.isBlank()) ? artist : "Spotify";
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

		public boolean matchesQuery(String query) {
			if (query == null || query.isBlank()) {
				return true;
			}
			String q = query.toLowerCase(Locale.ROOT).trim();
			return displayTitle().toLowerCase(Locale.ROOT).contains(q)
					|| displayArtist().toLowerCase(Locale.ROOT).contains(q)
					|| "spotify".contains(q);
		}

		public String toQueueTrackId() {
			String safeId = id != null ? id.replace('|', '_') : "track";
			String safePrev = previewUrl != null ? previewUrl.replace('|', '_') : "";
			String safeArtist = displayArtist().replace('|', ' ');
			String safeTitle = displayTitle().replace('|', ' ');
			return SPOTIFY_QUEUE_PREFIX + safeId + "|" + durationSeconds + "|" + safePrev + "|" + safeArtist + "|" + safeTitle;
		}

		public static boolean isSpotifyQueueTrackId(String queueTrackId) {
			return queueTrackId != null && queueTrackId.startsWith(SPOTIFY_QUEUE_PREFIX);
		}

		public static SpotifyTrack fromQueueTrackId(String queueTrackId, String fallbackTitle, int defaultDuration) {
			SpotifyTrack parsed = fromQueueTrackId(queueTrackId, fallbackTitle);
			if (parsed != null && parsed.durationSeconds() <= 0 && defaultDuration > 0) {
				return new SpotifyTrack(parsed.id(), parsed.title(), parsed.artist(), defaultDuration, parsed.previewUrl());
			}
			return parsed;
		}

		public static SpotifyTrack fromQueueTrackId(String queueTrackId, String fallbackTitle) {
			if (queueTrackId == null || !queueTrackId.startsWith(SPOTIFY_QUEUE_PREFIX)) {
				return null;
			}
			String rest = queueTrackId.substring(SPOTIFY_QUEUE_PREFIX.length());
			String[] parts = rest.split("\\|", 5);
			if (parts.length >= 5) {
				int dur = 0;
				try {
					dur = Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) {
				}
				return new SpotifyTrack(parts[0], parts[4], parts[3], dur, parts[2]);
			}
			return new SpotifyTrack(rest, fallbackTitle != null ? fallbackTitle : rest, "", 0, "");
		}
	}

	private final Mp3Config config;
	private final Mp3LibraryManager library;
	private final Path spotifyCacheDir;
	private final Path binDir;
	private final HttpClient httpClient;
	private final ExecutorService executor;

	private volatile List<SpotifyPlaylist> playlists = Collections.emptyList();
	private final Map<String, List<SpotifyTrack>> playlistTracksCache = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Mp3TrackInfo>> activeDownloads = new ConcurrentHashMap<>();
	private final Set<String> downloadingTrackIds = ConcurrentHashMap.newKeySet();

	private volatile boolean loadingPlaylists = false;
	private volatile boolean loadingTracks = false;
	private volatile String statusMessage = null;
	private volatile long statusExpireMs = 0L;

	private HttpServer callbackServer;
	private int activePortalPort = DEFAULT_CALLBACK_PORT;
	private volatile String pkceVerifier;
	private volatile Path cachedYtDlpBinary;
	private volatile boolean ytDlpUpdateChecked = false;
	private volatile Path cachedFfmpegDir;

	public SpotifyManager(Mp3Config config, Mp3LibraryManager library) {
		this.config = config;
		this.library = library;
		this.spotifyCacheDir = library.mp3Directory().resolve(".spotify-cache");
		this.binDir = library.mp3Directory().resolve(".bin");
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.build();
		this.executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "MP3-Spotify-Worker");
			t.setDaemon(true);
			return t;
		});

		try {
			library.ensureDirectoryExists();
			Files.createDirectories(spotifyCacheDir);
			Files.createDirectories(binDir);
			purgeLegacyCacheFiles();
		} catch (IOException ignored) {
		}

		// Pre-provision yt-dlp in background so it is ready before the first track click
		this.executor.submit(this::findOrProvisionYtDlp);

		if (config.isSpotifyLinked()) {
			refreshPlaylistsAsync();
		}
	}

	private void purgeLegacyCacheFiles() {
		if (!Files.isDirectory(spotifyCacheDir)) {
			return;
		}
		try (Stream<Path> files = Files.list(spotifyCacheDir)) {
			files.filter(Files::isRegularFile)
					.filter(p -> {
						String fn = p.getFileName().toString();
						return fn.startsWith("spotify_") && !fn.startsWith("spotify_v2_");
					})
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException ignored) {
						}
					});
		} catch (IOException ignored) {
		}
	}

	public boolean isAccountLinked() {
		return config.isSpotifyLinked();
	}

	public String linkedAccountName() {
		String name = config.spotifyAccountName();
		return (name != null && !name.isBlank()) ? name : "Spotify-Nutzer";
	}

	public String accountName() {
		return linkedAccountName();
	}

	public List<SpotifyPlaylist> playlists() {
		return playlists;
	}

	public List<SpotifyTrack> tracksForPlaylist(SpotifyPlaylist playlist) {
		return playlist != null ? getCachedPlaylistTracks(playlist.id()) : Collections.emptyList();
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

	public String currentStatusMessage() {
		if (statusMessage != null && System.currentTimeMillis() < statusExpireMs) {
			return statusMessage;
		}
		return null;
	}

	public String statusMessage() {
		return currentStatusMessage();
	}

	public void setStatusMessage(String message, long durationMs) {
		this.statusMessage = message;
		this.statusExpireMs = System.currentTimeMillis() + durationMs;
	}

	public String callbackUrl() {
		return "http://127.0.0.1:" + activePortalPort + "/callback";
	}

	public String portalUrl() {
		return "http://127.0.0.1:" + activePortalPort + "/spotify";
	}

	public void unlinkAccount() {
		config.clearSpotifyAccount();
		config.save();
		playlists = Collections.emptyList();
		playlistTracksCache.clear();
		stopCallbackServer();
	}

	/**
	 * Handles user input from the in-game Spotify box:
	 * - 32-char hex string -> Spotify Client ID (starts OAuth 2.0 PKCE browser login)
	 * - Spotify Playlist/Album URL -> links account if needed & imports the playlist
	 * - Spotify Profile URL or Username -> links account & opens portal/playlists
	 * - Bearer Access Token -> verifies against /v1/me and loads all user playlists
	 */
	public void handleUserLinkInput(String rawInput, Runnable onUiUpdate) {
		if (rawInput == null || rawInput.isBlank()) {
			openBrowserAuthPortal(onUiUpdate);
			return;
		}
		String input = rawInput.trim();

		// 1. Check if it's a 32-char hex Spotify Client ID
		if (input.matches("^[0-9a-fA-F]{32}$")) {
			config.setSpotifyClientId(input);
			config.save();
			startOAuthPkceBrowserLogin(input, onUiUpdate);
			return;
		}

		// 2. Check if it's a Spotify Playlist or Album URL/URI
		Matcher plMatcher = PLAYLIST_ID_PATTERN.matcher(input);
		if (plMatcher.find()) {
			String kind = plMatcher.group(1) != null ? plMatcher.group(1) : "playlist";
			String plId = plMatcher.group(2);
			if (config.spotifyAccountName().isBlank()) {
				config.setSpotifyAccountName("Spotify");
			}
			addSavedPlaylistEntry(kind + ":" + plId);
			config.save();
			refreshPlaylistsAsync(onUiUpdate);
			return;
		}

		// 3. Check if it's a Spotify User Profile URL/URI
		Matcher userMatcher = USER_ID_PATTERN.matcher(input);
		if (userMatcher.find()) {
			String userId = URLDecoder.decode(userMatcher.group(1), StandardCharsets.UTF_8);
			config.setSpotifyAccountName(userId);
			config.save();
			refreshPlaylistsAsync(onUiUpdate);
			return;
		}

		// 4. Check if it looks like a Spotify OAuth Access Token (starts with BQ and length > 60)
		if (input.startsWith("BQ") && input.length() > 60 && !input.contains(" ")) {
			config.setSpotifyAccessToken(input);
			config.setSpotifyTokenExpiresAt(System.currentTimeMillis() + 3600_000L);
			if (config.spotifyAccountName().isBlank()) {
				config.setSpotifyAccountName("Spotify-Account");
			}
			config.save();
			refreshPlaylistsAsync(onUiUpdate);
			return;
		}

		// 5. Otherwise treat as Spotify Display / Account Name
		config.setSpotifyAccountName(input);
		config.save();
		refreshPlaylistsAsync(onUiUpdate);
	}

	public void startOAuthPkceBrowserLogin(String clientId, Runnable onUiUpdate) {
		ensureCallbackServerRunning(onUiUpdate);
		this.pkceVerifier = generatePkceVerifier();
		String challenge = computePkceChallenge(this.pkceVerifier);
		String redirectUri = callbackUrl();

		String authUrl = "https://accounts.spotify.com/authorize"
				+ "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
				+ "&response_type=code"
				+ "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + URLEncoder.encode(challenge, StandardCharsets.UTF_8)
				+ "&scope=" + URLEncoder.encode(OAUTH_SCOPES, StandardCharsets.UTF_8);

		setStatusMessage("Browser geöffnet – bitte bei Spotify anmelden...", 15_000L);
		Util.getPlatform().openUri(URI.create(authUrl));
	}

	public void openBrowserLinkingPage() {
		openBrowserAuthPortal(null);
	}

	public boolean linkAccountFromInput(String rawInput) {
		if (rawInput == null || rawInput.isBlank()) {
			openBrowserAuthPortal(null);
			return false;
		}
		handleUserLinkInput(rawInput, null);
		return true;
	}

	public SpotifyPlaylist addPlaylistFromInput(String rawInput) {
		if (rawInput == null || rawInput.isBlank()) {
			return null;
		}
		String input = rawInput.trim();
		String kind = "playlist";
		String plId = null;
		Matcher plMatcher = PLAYLIST_ID_PATTERN.matcher(input);
		if (plMatcher.find()) {
			if (plMatcher.group(1) != null) {
				kind = plMatcher.group(1);
			}
			plId = plMatcher.group(2);
		} else if (input.matches("^[a-zA-Z0-9]{22}$")) {
			plId = input;
		}
		if (plId == null) {
			return null;
		}
		addSavedPlaylistEntry(kind + ":" + plId);
		config.save();
		for (SpotifyPlaylist existing : playlists) {
			if (plId.equals(existing.id())) {
				loadPlaylistTracksAsync(existing, null);
				return existing;
			}
		}
		SpotifyPlaylist placeholder = new SpotifyPlaylist(plId, kind, "Spotify Playlist", linkedAccountName(), 0, false);
		List<SpotifyPlaylist> mod = new ArrayList<>(this.playlists);
		mod.add(placeholder);
		this.playlists = Collections.unmodifiableList(mod);
		refreshPlaylistsAsync(null);
		loadPlaylistTracksAsync(placeholder, null);
		return placeholder;
	}

	public void refreshUserPlaylists() {
		refreshPlaylistsAsync(null);
	}

	public void loadPlaylistTracks(SpotifyPlaylist playlist) {
		loadPlaylistTracksAsync(playlist, false, null);
	}

	public void loadPlaylistTracks(SpotifyPlaylist playlist, boolean forceRefresh) {
		loadPlaylistTracksAsync(playlist, forceRefresh, null);
	}

	public void removePlaylist(String playlistId) {
		removeSavedPlaylist(playlistId, null);
	}

	public Mp3TrackInfo findCachedTrack(SpotifyTrack track) {
		return resolveCachedTrackNow(track);
	}

	public CompletableFuture<Mp3TrackInfo> resolveOrDownloadTrack(SpotifyTrack track) {
		CompletableFuture<Mp3TrackInfo> result = new CompletableFuture<>();
		resolveTrackAsync(track, result::complete, err -> result.complete(null));
		return result;
	}

	public void openBrowserAuthPortal(Runnable onUiUpdate) {
		ensureCallbackServerRunning(onUiUpdate);
		if (!config.spotifyClientId().isBlank()) {
			startOAuthPkceBrowserLogin(config.spotifyClientId(), onUiUpdate);
			return;
		}
		setStatusMessage("Spotify-Verknüpfungsportal im Browser geöffnet...", 12_000L);
		Util.getPlatform().openUri(URI.create(portalUrl()));
	}

	private synchronized void ensureCallbackServerRunning(Runnable onUiUpdate) {
		if (callbackServer != null) {
			return;
		}
		int[] candidatePorts = {DEFAULT_CALLBACK_PORT, 27845, 27846};
		for (int port : candidatePorts) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
				this.activePortalPort = port;
				server.createContext("/spotify", exchange -> handlePortalPage(exchange, onUiUpdate));
				server.createContext("/link", exchange -> handleQuickLinkSubmit(exchange, onUiUpdate));
				server.createContext("/oauth", exchange -> handleOAuthStartFromPortal(exchange, onUiUpdate));
				server.createContext("/callback", exchange -> handleOAuthCallback(exchange, onUiUpdate));
				server.setExecutor(executor);
				server.start();
				this.callbackServer = server;
				return;
			} catch (IOException ignored) {
			}
		}
	}

	public synchronized void stopCallbackServer() {
		if (callbackServer != null) {
			try {
				callbackServer.stop(0);
			} catch (Exception ignored) {
			}
			callbackServer = null;
		}
	}

	private void handlePortalPage(HttpExchange exchange, Runnable onUiUpdate) throws IOException {
		String savedClientId = escapeHtml(config.spotifyClientId());
		String cbUrl = callbackUrl();
		String html = """
				<!DOCTYPE html>
				<html lang="de">
				<head>
				<meta charset="UTF-8">
				<title>Minecraft MP3-Player – Spotify verknüpfen</title>
				<style>
				  body { background: #0b1120; color: #f8fafc; font-family: system-ui, -apple-system, sans-serif; display: flex; justify-content: center; padding: 36px 16px; margin: 0; }
				  .card { background: #111827; border: 1px solid #1db954; border-radius: 12px; max-width: 680px; width: 100%; padding: 28px; box-shadow: 0 12px 32px rgba(0,0,0,0.5); }
				  h1 { color: #1ed760; margin: 0 0 8px 0; font-size: 22px; }
				  h2 { color: #e2e8f0; font-size: 16px; margin: 24px 0 10px 0; border-bottom: 1px solid #1e293b; padding-bottom: 6px; }
				  p, li { color: #cbd5e1; font-size: 13.5px; line-height: 1.6; }
				  ol { padding-left: 20px; margin: 10px 0 16px 0; }
				  li { margin-bottom: 8px; }
				  .sublist { list-style-type: disc; padding-left: 20px; margin: 6px 0; }
				  .sublist li { margin-bottom: 4px; color: #94a3b8; }
				  .hint-box { background: #070b14; border-left: 3px solid #38bdf8; padding: 10px 14px; border-radius: 6px; margin: 10px 0 14px 0; font-size: 13px; color: #94a3b8; }
				  .copy-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; }
				  .copy-btn { background: #1e293b; color: #38bdf8; border: 1px solid #334155; padding: 4px 10px; border-radius: 5px; cursor: pointer; font-size: 12px; width: auto; font-weight: 600; }
				  .copy-btn:hover { background: #334155; }
				  input, textarea { width: 100%; box-sizing: border-box; background: #070b14; border: 1px solid #334155; color: #f8fafc; padding: 10px 12px; border-radius: 6px; margin-top: 6px; margin-bottom: 12px; font-size: 14px; }
				  button { background: #1db954; color: #052e16; font-weight: 700; border: none; padding: 10px 18px; border-radius: 6px; cursor: pointer; font-size: 14px; width: 100%; }
				  button:hover { background: #1ed760; }
				  code { background: #070b14; color: #38bdf8; padding: 3px 8px; border-radius: 4px; user-select: all; font-family: ui-monospace, Consolas, monospace; border: 1px solid #1e293b; }
				  a { color: #38bdf8; font-weight: 600; }
				  b { color: #f8fafc; }
				</style>
				</head>
				<body>
				<div class="card">
				  <h1>Spotify-Account mit Minecraft verknüpfen</h1>
				  <p>Wähle eine der beiden Methoden, um deinen Spotify-Account und deine kompletten Playlists (auch große Playlists mit 250+ Songs) in Minecraft freizuschalten:</p>

				  <h2>Option 1: Schnell-Verknüpfung (Ohne Developer-App – Account & Playlist-Links)</h2>
				  <p>Am schnellsten: Gib deinen Spotify-Namen (oder Profil-Link) und deine Playlist-Links ein. Öffentliche Playlists werden sofort vollständig mit allen Songs geladen.</p>
				  <form action="/link" method="GET">
				    <label><b>Dein Spotify-Accountname oder Profil-Link:</b></label>
				    <input type="text" name="account" placeholder="z. B. DeinSpotifyName oder https://open.spotify.com/user/..." required />
				    <label><b>Deine Spotify-Playlists (Links oder URIs, eine pro Zeile – auch später im Spiel erweiterbar):</b></label>
				    <textarea name="playlists" rows="3" placeholder="https://open.spotify.com/playlist/..."></textarea>
				    <button type="submit">Account & Playlists jetzt verknüpfen</button>
				  </form>

				  <h2>Option 2: Offizieller Spotify OAuth 2.0 Login (Alle eigenen Playlists, Private Listen & Lieblingssongs)</h2>
				  <p>Mit einer kostenlosen eigenen Spotify Developer Application lädt der Mod automatisch dein Profil, deine <b>Lieblingssongs</b> und <b>alle deine eigenen/privaten Playlists</b> über die offizielle Spotify Web API. So richtest du die App in 1 Minute ein:</p>
				  <ol>
				    <li>Öffne das <a href="https://developer.spotify.com/dashboard" target="_blank">Spotify Developer Dashboard</a> und melde dich mit deinem normalen Spotify-Account an.</li>
				    <li>Klicke oben rechts auf den blauen Button <b>„Create app"</b>.</li>
				    <li>Fülle das Formular wie folgt aus:
				      <ul class="sublist">
				        <li><b>App name:</b> z. B. <code>Minecraft MP3 Player</code> (frei wählbar)</li>
				        <li><b>App description:</b> z. B. <code>Minecraft Ingame Music Integration</code> (frei wählbar)</li>
				        <li><b>Website:</b> kann leer bleiben</li>
				        <li><b>Redirect URIs:</b> Kopiere exakt diese Adresse hinein und klicke rechts daneben unbedingt auf <b>„Add"</b>:
				          <div class="copy-row">
				            <code id="cbUri">%CALLBACK%</code>
				            <button type="button" class="copy-btn" onclick="navigator.clipboard.writeText(document.getElementById('cbUri').innerText); this.innerText='Kopiert!';">Kopieren</button>
				          </div>
				        </li>
				        <li><b>Which API/SDKs are you planning to use?:</b> Setze den Haken bei <b>„Web API"</b>.</li>
				      </ul>
				    </li>
				    <li>Setze unten den Haken bei den Nutzungsbedingungen (<i>Developer Terms of Service</i>) und klicke auf <b>„Save"</b>.</li>
				    <li>Du landest nun in deiner neuen App: Klicke oben rechts auf <b>„Settings"</b> (oder <b>„Basic Information"</b>).</li>
				    <li>Dort siehst du direkt deine 32-stellige <b>Client ID</b> (z. B. <code>a1b2c3d4e5...</code>). Kopiere diese <b>Client ID</b> in das Feld unten (das <i>Client Secret</i> wird dank PKCE-Verschlüsselung <b>nicht</b> benötigt!).</li>
				  </ol>
				  <div class="hint-box">
				    <b>Wichtig bei „INVALID_CLIENT: Invalid redirect URI":</b> Prüfe in den App-Settings unter <i>Redirect URIs</i>, dass <code>%CALLBACK%</code> exakt eingetragen ist, du auf <b>Add</b> und ganz unten auf <b>Save</b> geklickt hast.
				  </div>
				  <form action="/oauth" method="GET">
				    <label><b>Deine 32-stellige Spotify Client ID aus dem Dashboard:</b></label>
				    <input type="text" name="client_id" value="%CLIENT_ID%" placeholder="z. B. 4f8a9c12e7b04d319a8b7c6d5e4f3a2b" required />
				    <button type="submit">Weiter zum offiziellen Spotify OAuth Login</button>
				  </form>
				</div>
				</body>
				</html>
				"""
				.replace("%CALLBACK%", cbUrl)
				.replace("%CLIENT_ID%", savedClientId);

		sendHtmlResponse(exchange, 200, html);
	}

	private void handleQuickLinkSubmit(HttpExchange exchange, Runnable onUiUpdate) throws IOException {
		Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
		String account = params.getOrDefault("account", "").trim();
		String rawPlaylists = params.getOrDefault("playlists", "").trim();

		if (!account.isBlank()) {
			Matcher um = USER_ID_PATTERN.matcher(account);
			if (um.find()) {
				account = URLDecoder.decode(um.group(1), StandardCharsets.UTF_8);
			}
			config.setSpotifyAccountName(account);
		} else if (config.spotifyAccountName().isBlank()) {
			config.setSpotifyAccountName("Spotify-Nutzer");
		}

		if (!rawPlaylists.isBlank()) {
			String[] lines = rawPlaylists.split("[\\r\\n,;\\s]+");
			for (String line : lines) {
				Matcher m = PLAYLIST_ID_PATTERN.matcher(line.trim());
				if (m.find()) {
					String kind = m.group(1) != null ? m.group(1) : "playlist";
					addSavedPlaylistEntry(kind + ":" + m.group(2));
				}
			}
		}
		config.save();

		sendHtmlResponse(exchange, 200, buildSuccessHtml(config.spotifyAccountName()));
		refreshPlaylistsAsync(onUiUpdate);
	}

	private void handleOAuthStartFromPortal(HttpExchange exchange, Runnable onUiUpdate) throws IOException {
		Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
		String clientId = params.getOrDefault("client_id", "").trim();
		if (clientId.isEmpty()) {
			sendHtmlResponse(exchange, 400, "<h1>Fehlende Client ID</h1>");
			return;
		}
		config.setSpotifyClientId(clientId);
		config.save();

		this.pkceVerifier = generatePkceVerifier();
		String challenge = computePkceChallenge(this.pkceVerifier);
		String authUrl = "https://accounts.spotify.com/authorize"
				+ "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
				+ "&response_type=code"
				+ "&redirect_uri=" + URLEncoder.encode(callbackUrl(), StandardCharsets.UTF_8)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + URLEncoder.encode(challenge, StandardCharsets.UTF_8)
				+ "&scope=" + URLEncoder.encode(OAUTH_SCOPES, StandardCharsets.UTF_8);

		exchange.getResponseHeaders().set("Location", authUrl);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private void handleOAuthCallback(HttpExchange exchange, Runnable onUiUpdate) throws IOException {
		Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
		String code = params.get("code");
		String error = params.get("error");

		if (error != null || code == null || code.isBlank()) {
			sendHtmlResponse(exchange, 400, "<h1>Spotify-Anmeldung abgebrochen (" + escapeHtml(String.valueOf(error)) + ")</h1>");
			return;
		}

		try {
			boolean ok = exchangeAuthorizationCode(code);
			if (ok) {
				fetchCurrentUserProfile();
				config.save();
				sendHtmlResponse(exchange, 200, buildSuccessHtml(config.spotifyAccountName()));
				refreshPlaylistsAsync(onUiUpdate);
			} else {
				sendHtmlResponse(exchange, 500, "<h1>Fehler beim Token-Austausch mit Spotify. Bitte prüfe deine Client ID.</h1>");
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Spotify OAuth callback failed", e);
			sendHtmlResponse(exchange, 500, "<h1>Fehler: " + escapeHtml(e.getMessage()) + "</h1>");
		}
	}

	private boolean exchangeAuthorizationCode(String code) throws Exception {
		String body = "client_id=" + URLEncoder.encode(config.spotifyClientId(), StandardCharsets.UTF_8)
				+ "&grant_type=authorization_code"
				+ "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
				+ "&redirect_uri=" + URLEncoder.encode(callbackUrl(), StandardCharsets.UTF_8)
				+ "&code_verifier=" + URLEncoder.encode(pkceVerifier != null ? pkceVerifier : "", StandardCharsets.UTF_8);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create("https://accounts.spotify.com/api/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() != 200) {
			return false;
		}
		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		String accessToken = getJsonString(json, "access_token");
		String refreshToken = getJsonString(json, "refresh_token");
		int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;

		if (accessToken == null || accessToken.isBlank()) {
			return false;
		}
		config.setSpotifyAccessToken(accessToken);
		if (refreshToken != null && !refreshToken.isBlank()) {
			config.setSpotifyRefreshToken(refreshToken);
		}
		config.setSpotifyTokenExpiresAt(System.currentTimeMillis() + expiresIn * 1000L);
		return true;
	}

	private synchronized boolean refreshAccessTokenIfNeeded() {
		String access = config.spotifyAccessToken();
		if (access == null || access.isBlank()) {
			return false;
		}
		if (System.currentTimeMillis() < config.spotifyTokenExpiresAt() - 30_000L) {
			return true;
		}
		String refresh = config.spotifyRefreshToken();
		String clientId = config.spotifyClientId();
		if (refresh == null || refresh.isBlank() || clientId == null || clientId.isBlank()) {
			return true;
		}
		try {
			String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
					+ "&grant_type=refresh_token"
					+ "&refresh_token=" + URLEncoder.encode(refresh, StandardCharsets.UTF_8);

			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://accounts.spotify.com/api/token"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();

			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() == 200) {
				JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
				String newAccess = getJsonString(json, "access_token");
				String newRefresh = getJsonString(json, "refresh_token");
				int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
				if (newAccess != null && !newAccess.isBlank()) {
					config.setSpotifyAccessToken(newAccess);
					if (newRefresh != null && !newRefresh.isBlank()) {
						config.setSpotifyRefreshToken(newRefresh);
					}
					config.setSpotifyTokenExpiresAt(System.currentTimeMillis() + expiresIn * 1000L);
					config.save();
					return true;
				}
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("Spotify token refresh failed: {}", e.getMessage());
		}
		return !config.spotifyAccessToken().isBlank();
	}

	private void fetchCurrentUserProfile() {
		try {
			JsonObject me = sendSpotifyApiGet("https://api.spotify.com/v1/me");
			if (me != null) {
				String displayName = getJsonString(me, "display_name");
				String id = getJsonString(me, "id");
				String name = (displayName != null && !displayName.isBlank()) ? displayName : id;
				if (name != null && !name.isBlank()) {
					config.setSpotifyAccountName(name);
				}
			}
		} catch (Exception ignored) {
		}
		if (config.spotifyAccountName().isBlank()) {
			config.setSpotifyAccountName("Spotify-Account");
		}
	}

	public void refreshPlaylistsAsync() {
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
				Map<String, SpotifyPlaylist> loaded = new LinkedHashMap<>();

				// 1. If OAuth token is present, load user profile, Liked Songs & user's playlists via Web API
				if (refreshAccessTokenIfNeeded()) {
					fetchCurrentUserProfile();

					JsonObject likedResp = sendSpotifyApiGet("https://api.spotify.com/v1/me/tracks?limit=1");
					if (likedResp != null && likedResp.has("total")) {
						int totalLiked = likedResp.get("total").getAsInt();
						if (totalLiked > 0) {
							loaded.put(LIKED_SONGS_ID, new SpotifyPlaylist(
									LIKED_SONGS_ID,
									"playlist",
									"Lieblingssongs",
									config.spotifyAccountName(),
									totalLiked,
									true
							));
						}
					}

					String nextPlUrl = "https://api.spotify.com/v1/me/playlists?limit=50";
					int plPages = 0;
					while (nextPlUrl != null && plPages++ < 10) {
						JsonObject plResp = sendSpotifyApiGet(nextPlUrl);
						if (plResp == null || !plResp.has("items") || !plResp.get("items").isJsonArray()) {
							break;
						}
						JsonArray items = plResp.getAsJsonArray("items");
						for (JsonElement el : items) {
							if (!el.isJsonObject()) {
								continue;
							}
							JsonObject obj = el.getAsJsonObject();
							String id = getJsonString(obj, "id");
							String name = getJsonString(obj, "name");
							String ownerName = "Spotify";
							if (obj.has("owner") && obj.get("owner").isJsonObject()) {
								String dn = getJsonString(obj.getAsJsonObject("owner"), "display_name");
								if (dn != null && !dn.isBlank()) {
									ownerName = dn;
								}
							}
							int total = 0;
							if (obj.has("tracks") && obj.get("tracks").isJsonObject()) {
								JsonObject trObj = obj.getAsJsonObject("tracks");
								if (trObj.has("total") && !trObj.get("total").isJsonNull()) {
									total = trObj.get("total").getAsInt();
								}
							}
							if (id != null && name != null) {
								loaded.put(id, new SpotifyPlaylist(id, "playlist", name, ownerName, total, false));
							}
						}
						nextPlUrl = getJsonString(plResp, "next");
					}
				}

				// 2. Load any saved playlist/album links via Embed JSON
				for (String saved : config.spotifySavedPlaylists()) {
					String kind = "playlist";
					String plId = saved;
					int colon = saved.indexOf(':');
					if (colon > 0) {
						kind = saved.substring(0, colon);
						plId = saved.substring(colon + 1);
					}
					if (!loaded.containsKey(plId)) {
						SpotifyPlaylist embedPl = fetchEmbedPlaylistAndCacheTracks(kind, plId);
						if (embedPl != null) {
							loaded.put(plId, embedPl);
						}
					}
				}

				this.playlists = List.copyOf(loaded.values());
			} catch (Exception e) {
				Mp3PlayerMod.LOGGER.warn("Failed to refresh Spotify playlists", e);
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

	public void removeSavedPlaylist(String playlistId, Runnable onCompleteClientThread) {
		if (playlistId == null) {
			return;
		}
		List<String> updated = new ArrayList<>(config.spotifySavedPlaylists());
		boolean changed = updated.removeIf(s -> s.equals(playlistId) || s.endsWith(":" + playlistId));
		if (changed) {
			config.setSpotifySavedPlaylists(updated);
			config.save();
		}
		playlistTracksCache.remove(playlistId);
		List<SpotifyPlaylist> modList = new ArrayList<>(this.playlists);
		modList.removeIf(p -> playlistId.equals(p.id()));
		this.playlists = Collections.unmodifiableList(modList);
		if (onCompleteClientThread != null) {
			onCompleteClientThread.run();
		}
	}

	public List<SpotifyTrack> getCachedPlaylistTracks(String playlistId) {
		if (playlistId == null) {
			return Collections.emptyList();
		}
		return playlistTracksCache.getOrDefault(playlistId, Collections.emptyList());
	}

	public void loadPlaylistTracksAsync(SpotifyPlaylist playlist, Runnable onCompleteClientThread) {
		loadPlaylistTracksAsync(playlist, false, onCompleteClientThread);
	}

	public void loadPlaylistTracksAsync(SpotifyPlaylist playlist, boolean forceRefresh, Runnable onCompleteClientThread) {
		if (playlist == null) {
			return;
		}
		if (!forceRefresh) {
			List<SpotifyTrack> cached = playlistTracksCache.get(playlist.id());
			if (cached != null && !cached.isEmpty() && (playlist.trackCount() <= 0 || cached.size() >= playlist.trackCount())) {
				if (onCompleteClientThread != null) {
					onCompleteClientThread.run();
				}
				return;
			}
		}

		this.loadingTracks = true;
		executor.submit(() -> {
			try {
				List<SpotifyTrack> tracks = new ArrayList<>();

				// 1. Try Spotify Web API with full pagination if OAuth token is active
				if (refreshAccessTokenIfNeeded()) {
					String nextUrl = playlist.likedSongs()
							? "https://api.spotify.com/v1/me/tracks?limit=50"
							: ("album".equals(playlist.kind())
							? "https://api.spotify.com/v1/albums/" + playlist.id() + "/tracks?limit=50"
							: "https://api.spotify.com/v1/playlists/" + playlist.id() + "/tracks?limit=100");

					int pageCount = 0;
					while (nextUrl != null && pageCount++ < 50) {
						JsonObject json = sendSpotifyApiGet(nextUrl);
						if (json == null || !json.has("items") || !json.get("items").isJsonArray()) {
							break;
						}
						JsonArray items = json.getAsJsonArray("items");
						for (JsonElement el : items) {
							if (!el.isJsonObject()) {
								continue;
							}
							JsonObject itemObj = el.getAsJsonObject();
							JsonObject trackObj = itemObj.has("track") && itemObj.get("track").isJsonObject()
									? itemObj.getAsJsonObject("track")
									: itemObj;
							SpotifyTrack parsed = parseWebApiTrack(trackObj);
							if (parsed != null) {
								tracks.add(parsed);
							}
						}
						if (!tracks.isEmpty()) {
							playlistTracksCache.put(playlist.id(), List.copyOf(tracks));
						}
						nextUrl = getJsonString(json, "next");
					}
				}

				// 2. Fallback to Spotify Embed + Partner GraphQL pagination if Web API returned empty (or if linked without OAuth)
				if (tracks.isEmpty() && !playlist.likedSongs()) {
					SpotifyPlaylist updatedPl = fetchEmbedPlaylistAndCacheTracks(playlist.kind(), playlist.id());
					if (updatedPl != null) {
						updatePlaylistEntryInList(updatedPl);
					}
				} else if (!tracks.isEmpty()) {
					playlistTracksCache.put(playlist.id(), List.copyOf(tracks));
					updatePlaylistEntryInList(new SpotifyPlaylist(
							playlist.id(),
							playlist.kind(),
							playlist.name(),
							playlist.owner(),
							tracks.size(),
							playlist.likedSongs()
					));
				}
			} catch (Exception e) {
				Mp3PlayerMod.LOGGER.warn("Failed to load tracks for Spotify playlist {}", playlist.name(), e);
			} finally {
				this.loadingTracks = false;
				if (onCompleteClientThread != null) {
					Minecraft mc = Minecraft.getInstance();
					if (mc != null) {
						mc.execute(onCompleteClientThread);
					}
				}
			}
		});
	}

	private void updatePlaylistEntryInList(SpotifyPlaylist updated) {
		if (updated == null) {
			return;
		}
		List<SpotifyPlaylist> mod = new ArrayList<>(this.playlists);
		for (int i = 0; i < mod.size(); i++) {
			if (mod.get(i).id().equals(updated.id())) {
				mod.set(i, updated);
				this.playlists = Collections.unmodifiableList(mod);
				return;
			}
		}
	}

	private SpotifyPlaylist fetchEmbedPlaylistAndCacheTracks(String kind, String playlistId) {
		try {
			String safeKind = ("album".equalsIgnoreCase(kind)) ? "album" : "playlist";
			String url = "https://open.spotify.com/embed/" + safeKind + "/" + playlistId;
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
					.GET()
					.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() != 200) {
				return null;
			}

			Matcher m = NEXT_DATA_PATTERN.matcher(resp.body());
			if (!m.find()) {
				return null;
			}
			JsonObject root = JsonParser.parseString(m.group(1)).getAsJsonObject();
			JsonObject stateObj = root
					.getAsJsonObject("props")
					.getAsJsonObject("pageProps")
					.getAsJsonObject("state");
			JsonObject entity = stateObj
					.getAsJsonObject("data")
					.getAsJsonObject("entity");

			String sessionAccessToken = null;
			if (stateObj.has("settings") && stateObj.get("settings").isJsonObject()) {
				JsonObject settings = stateObj.getAsJsonObject("settings");
				if (settings.has("session") && settings.get("session").isJsonObject()) {
					sessionAccessToken = getJsonString(settings.getAsJsonObject("session"), "accessToken");
				}
			}

			String name = getJsonString(entity, "name");
			if (name == null || name.isBlank()) {
				name = getJsonString(entity, "title");
			}
			String subtitle = getJsonString(entity, "subtitle");
			List<SpotifyTrack> tracks = new ArrayList<>();

			if (entity.has("trackList") && entity.get("trackList").isJsonArray()) {
				JsonArray arr = entity.getAsJsonArray("trackList");
				for (JsonElement el : arr) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject tObj = el.getAsJsonObject();
					String uri = getJsonString(tObj, "uri");
					String tId = uri != null && uri.contains(":")
							? uri.substring(uri.lastIndexOf(':') + 1)
							: getJsonString(tObj, "uid");
					String title = getJsonString(tObj, "title");
					String artist = getJsonString(tObj, "subtitle");
					int durationMs = tObj.has("duration") && !tObj.get("duration").isJsonNull()
							? tObj.get("duration").getAsInt()
							: 0;
					String previewUrl = "";
					if (tObj.has("audioPreview") && tObj.get("audioPreview").isJsonObject()) {
						String pUrl = getJsonString(tObj.getAsJsonObject("audioPreview"), "url");
						if (pUrl != null) {
							previewUrl = pUrl;
						}
					}
					if (tId != null && title != null) {
						tracks.add(new SpotifyTrack(
								tId,
								title,
								artist != null ? artist : "",
								Math.max(1, (int) Math.round(durationMs / 1000.0)),
								previewUrl
						));
					}
				}
			}

			// Embed HTML only includes the first 100 tracks. If there are 100 tracks (or 0),
			// fetch all remaining tracks (e.g. 101..253+) via Spotify's Partner Pathfinder API!
			if ("playlist".equals(safeKind)
					&& sessionAccessToken != null
					&& !sessionAccessToken.isBlank()
					&& (tracks.size() >= 100 || tracks.isEmpty())) {
				fetchRemainingPlaylistTracksViaPartnerApi(playlistId, sessionAccessToken, tracks);
			}

			playlistTracksCache.put(playlistId, List.copyOf(tracks));
			return new SpotifyPlaylist(
					playlistId,
					safeKind,
					name != null ? name : ("Playlist " + playlistId),
					subtitle != null ? subtitle : "Spotify",
					tracks.size(),
					false
			);
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("Embed playlist fetch failed for {}: {}", playlistId, e.getMessage());
			return null;
		}
	}

	/**
	 * Fetches all tracks beyond the 100-track Embed limit using Spotify's Partner Pathfinder GraphQL API
	 * (100 tracks per page, rate-limit free for normal playlist sizes like 253+ songs).
	 */
	private void fetchRemainingPlaylistTracksViaPartnerApi(
			String playlistId,
			String bearerToken,
			List<SpotifyTrack> tracks
	) {
		try {
			String extensionsJson = "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"cd2275433b29f7316176e7b5b5e098ae7744724e1a52d63549c76636b3257749\"}}";
			int offset = tracks.size();
			int pageLimit = 100;
			int maxPages = 50; // Up to 5,000 tracks

			for (int page = 0; page < maxPages; page++) {
				String variablesJson = "{\"uri\":\"spotify:playlist:" + playlistId + "\",\"offset\":" + offset + ",\"limit\":" + pageLimit + "}";
				String queryUrl = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=fetchPlaylistContents"
						+ "&variables=" + URLEncoder.encode(variablesJson, StandardCharsets.UTF_8)
						+ "&extensions=" + URLEncoder.encode(extensionsJson, StandardCharsets.UTF_8);

				HttpRequest req = HttpRequest.newBuilder()
						.uri(URI.create(queryUrl))
						.header("Authorization", "Bearer " + bearerToken)
						.header("Accept", "application/json")
						.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
						.GET()
						.build();

				HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (resp.statusCode() == 429) {
					int retrySec = resp.headers().firstValue("Retry-After")
							.map(v -> {
								try {
									return Math.min(5, Math.max(1, Integer.parseInt(v.trim())));
								} catch (NumberFormatException e) {
									return 2;
								}
							}).orElse(2);
					Thread.sleep(retrySec * 1000L);
					resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				}
				if (resp.statusCode() != 200) {
					break;
				}

				JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
				if (!root.has("data") || !root.get("data").isJsonObject()) {
					break;
				}
				JsonObject dataObj = root.getAsJsonObject("data");
				if (!dataObj.has("playlistV2") || !dataObj.get("playlistV2").isJsonObject()) {
					break;
				}
				JsonObject contentObj = dataObj.getAsJsonObject("playlistV2").getAsJsonObject("content");
				if (contentObj == null || !contentObj.has("items") || !contentObj.get("items").isJsonArray()) {
					break;
				}

				int totalCount = contentObj.has("totalCount") && !contentObj.get("totalCount").isJsonNull()
						? contentObj.get("totalCount").getAsInt()
						: -1;
				JsonArray items = contentObj.getAsJsonArray("items");
				if (items.isEmpty()) {
					break;
				}

				int addedInPage = 0;
				for (JsonElement el : items) {
					if (!el.isJsonObject()) {
						continue;
					}
					SpotifyTrack parsed = parsePartnerPlaylistItem(el.getAsJsonObject());
					if (parsed != null) {
						tracks.add(parsed);
						addedInPage++;
					}
				}

				playlistTracksCache.put(playlistId, List.copyOf(tracks));
				offset += items.size();

				if (addedInPage == 0 || (totalCount > 0 && offset >= totalCount) || items.size() < pageLimit) {
					break;
				}
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("Partner API pagination stopped for {}: {}", playlistId, e.getMessage());
		}
	}

	private static SpotifyTrack parsePartnerPlaylistItem(JsonObject itemRow) {
		if (itemRow == null || !itemRow.has("itemV2") || !itemRow.get("itemV2").isJsonObject()) {
			return null;
		}
		JsonObject itemV2 = itemRow.getAsJsonObject("itemV2");
		if (!itemV2.has("data") || !itemV2.get("data").isJsonObject()) {
			return null;
		}
		JsonObject trackData = itemV2.getAsJsonObject("data");
		String uri = getJsonString(trackData, "uri");
		String id = (uri != null && uri.contains(":"))
				? uri.substring(uri.lastIndexOf(':') + 1)
				: getJsonString(itemRow, "uid");
		String name = getJsonString(trackData, "name");
		if (id == null || name == null || name.isBlank()) {
			return null;
		}

		StringBuilder artists = new StringBuilder();
		JsonObject artistsContainer = null;
		if (trackData.has("artists") && trackData.get("artists").isJsonObject()) {
			artistsContainer = trackData.getAsJsonObject("artists");
		} else if (trackData.has("albumOfTrack") && trackData.get("albumOfTrack").isJsonObject()) {
			JsonObject album = trackData.getAsJsonObject("albumOfTrack");
			if (album.has("artists") && album.get("artists").isJsonObject()) {
				artistsContainer = album.getAsJsonObject("artists");
			}
		}
		if (artistsContainer != null && artistsContainer.has("items") && artistsContainer.get("items").isJsonArray()) {
			for (JsonElement aEl : artistsContainer.getAsJsonArray("items")) {
				if (aEl.isJsonObject()) {
					JsonObject aObj = aEl.getAsJsonObject();
					String aName = null;
					if (aObj.has("profile") && aObj.get("profile").isJsonObject()) {
						aName = getJsonString(aObj.getAsJsonObject("profile"), "name");
					}
					if (aName != null && !aName.isBlank()) {
						if (!artists.isEmpty()) {
							artists.append(", ");
						}
						artists.append(aName);
					}
				}
			}
		}

		int durationMs = 0;
		if (trackData.has("trackDuration") && trackData.get("trackDuration").isJsonObject()) {
			JsonObject td = trackData.getAsJsonObject("trackDuration");
			if (td.has("totalMilliseconds") && !td.get("totalMilliseconds").isJsonNull()) {
				durationMs = td.get("totalMilliseconds").getAsInt();
			}
		}
		return new SpotifyTrack(
				id,
				name,
				artists.toString(),
				Math.max(1, (int) Math.round(durationMs / 1000.0)),
				""
		);
	}

	private static SpotifyTrack parseWebApiTrack(JsonObject trackObj) {
		if (trackObj == null) {
			return null;
		}
		String id = getJsonString(trackObj, "id");
		String name = getJsonString(trackObj, "name");
		if (id == null || name == null) {
			return null;
		}
		StringBuilder artists = new StringBuilder();
		if (trackObj.has("artists") && trackObj.get("artists").isJsonArray()) {
			JsonArray arr = trackObj.getAsJsonArray("artists");
			for (JsonElement aEl : arr) {
				if (aEl.isJsonObject()) {
					String aName = getJsonString(aEl.getAsJsonObject(), "name");
					if (aName != null && !aName.isBlank()) {
						if (!artists.isEmpty()) {
							artists.append(", ");
						}
						artists.append(aName);
					}
				}
			}
		}
		int durationMs = trackObj.has("duration_ms") && !trackObj.get("duration_ms").isJsonNull()
				? trackObj.get("duration_ms").getAsInt()
				: 0;
		String previewUrl = getJsonString(trackObj, "preview_url");
		return new SpotifyTrack(
				id,
				name,
				artists.toString(),
				Math.max(1, (int) Math.round(durationMs / 1000.0)),
				previewUrl != null ? previewUrl : ""
		);
	}

	// ===================== AUDIO STREAM RESOLUTION & CACHING =====================

	public void preCacheTrack(SpotifyTrack track) {
		if (track == null || findCachedFile(track.id()) != null) {
			return;
		}
		resolveTrackAsync(track, null, null);
	}

	public void resolveTrackAsync(
			SpotifyTrack track,
			Consumer<Mp3TrackInfo> onReadyClientThread,
			Consumer<String> onErrorClientThread
	) {
		if (track == null) {
			return;
		}

		// Fast path: already cached on disk
		Mp3TrackInfo cached = resolveCachedTrackNow(track);
		if (cached != null) {
			if (onReadyClientThread != null) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && !mc.isSameThread()) {
					mc.execute(() -> onReadyClientThread.accept(cached));
				} else if (onReadyClientThread != null) {
					onReadyClientThread.accept(cached);
				}
			}
			return;
		}

		downloadingTrackIds.add(track.id());
		setStatusMessage("Lade Spotify-Song: " + track.fullDisplayLabel() + " ...", 25_000L);

		CompletableFuture<Mp3TrackInfo> future = activeDownloads.computeIfAbsent(
				track.id(),
				k -> CompletableFuture.supplyAsync(() -> downloadAndParseTrack(track), executor)
		);

		future.whenComplete((info, err) -> {
			downloadingTrackIds.remove(track.id());
			activeDownloads.remove(track.id());
			Minecraft mc = Minecraft.getInstance();
			if (info != null && err == null) {
				setStatusMessage("Bereit: " + track.displayTitle(), 2500L);
				if (onReadyClientThread != null && mc != null) {
					mc.execute(() -> onReadyClientThread.accept(info));
				}
			} else {
				String msg = "Spotify-Stream konnte nicht geladen werden: " + track.displayTitle();
				setStatusMessage(msg, 4500L);
				if (onErrorClientThread != null && mc != null) {
					mc.execute(() -> onErrorClientThread.accept(msg));
				}
			}
		});
	}

	public Mp3TrackInfo resolveCachedTrackNow(SpotifyTrack track) {
		if (track == null) {
			return null;
		}
		// 1. Check .spotify-cache directory
		Path cachedFile = findCachedFile(track.id());
		if (cachedFile != null) {
			try {
				Mp3TrackInfo parsed = AudioMetadataParsers.parse(cachedFile);
				return overrideSpotifyMetadata(parsed, track);
			} catch (Exception ignored) {
			}
		}

		// 2. Check if user already has an identical song in their local mp3s/ library
		String targetTitle = track.displayTitle().toLowerCase(Locale.ROOT);
		String targetArtist = track.displayArtist().toLowerCase(Locale.ROOT);
		for (Mp3TrackInfo local : library.tracks()) {
			String lTitle = local.displayTitle().toLowerCase(Locale.ROOT);
			String lArtist = local.artist() != null ? local.artist().toLowerCase(Locale.ROOT) : "";
			if (lTitle.equals(targetTitle) && (targetArtist.isEmpty() || lArtist.contains(targetArtist) || lTitle.contains(targetArtist))) {
				return overrideSpotifyMetadata(local, track);
			}
		}
		return null;
	}

	private Mp3TrackInfo downloadAndParseTrack(SpotifyTrack track) {
		try {
			Files.createDirectories(spotifyCacheDir);
			String safeId = track.id().replaceAll("[^a-zA-Z0-9_-]", "");
			if (safeId.isEmpty()) {
				safeId = Integer.toHexString(track.fullDisplayLabel().hashCode());
			}

			// 1. Studio-accurate resolution via yt-dlp:
			//    Priority 1: YouTube Topic (official distributor master, exact duration ±8s)
			//    Priority 2: YouTube Official Audio (exact duration ±12s)
			//    Priority 3: YouTube general search (duration ±20s)
			//    Priority 4: SoundCloud full-track (strictly matching duration ±8s, no previews/remixes)
			Path ytDlp = findOrProvisionYtDlp();
			if (ytDlp != null) {
				String query = track.fullDisplayLabel();
				int targetDur = track.durationSeconds();
				String strictTitleExclusions = buildTitleFilterClause(track, true);
				String relaxedTitleExclusions = buildTitleFilterClause(track, false);

				Path downloaded = runYtDlpSearchDownload(
						ytDlp,
						safeId,
						"ytsearch5:" + query + " Topic",
						buildMatchFilter(targetDur, 8, false, strictTitleExclusions)
				);
				if (downloaded == null) {
					downloaded = runYtDlpSearchDownload(
							ytDlp,
							safeId,
							"ytsearch5:" + query + " Official Audio",
							buildMatchFilter(targetDur, 12, false, strictTitleExclusions)
					);
				}
				if (downloaded == null) {
					downloaded = runYtDlpSearchDownload(
							ytDlp,
							safeId,
							"ytsearch5:" + query,
							buildMatchFilter(targetDur, 20, false, relaxedTitleExclusions)
					);
				}
				if (downloaded == null) {
					downloaded = runYtDlpSearchDownload(
							ytDlp,
							safeId,
							"scsearch5:" + query,
							buildMatchFilter(targetDur, 8, true, strictTitleExclusions)
					);
				}
				if (downloaded != null && Files.isRegularFile(downloaded) && Files.size(downloaded) > 1024) {
					Mp3TrackInfo parsed = AudioMetadataParsers.parse(downloaded);
					return overrideSpotifyMetadata(parsed, track);
				}
			}

			// 2. Fallback: Direct Spotify MP3 preview stream URL (if available or fetched via Embed API)
			String previewUrl = track.previewUrl();
			if (previewUrl == null || previewUrl.isBlank()) {
				previewUrl = fetchTrackPreviewUrlFromEmbed(track.id());
			}
			if (previewUrl != null && !previewUrl.isBlank()) {
				Path targetMp3 = spotifyCacheDir.resolve("spotify_v2_" + safeId + ".mp3");
				HttpRequest req = HttpRequest.newBuilder()
						.uri(URI.create(previewUrl))
						.header("User-Agent", "Mozilla/5.0")
						.GET()
						.build();
				HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
				if (resp.statusCode() == 200) {
					try (InputStream in = resp.body()) {
						Files.copy(in, targetMp3, StandardCopyOption.REPLACE_EXISTING);
					}
					if (Files.size(targetMp3) > 1024) {
						Mp3TrackInfo parsed = AudioMetadataParsers.parse(targetMp3);
						return overrideSpotifyMetadata(parsed, track);
					}
				}
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Failed to resolve Spotify audio for {}", track.fullDisplayLabel(), e);
		}
		throw new RuntimeException("Could not resolve Spotify track " + track.fullDisplayLabel());
	}

	private static String buildTitleFilterClause(SpotifyTrack track, boolean strict) {
		String lower = (track.displayTitle() + " " + track.displayArtist()).toLowerCase(Locale.ROOT);
		List<String> banned = new ArrayList<>();
		if (!lower.contains("slowed")) {
			banned.add("slowed");
		}
		if (!lower.contains("sped up") && !lower.contains("speed up")) {
			banned.add("sped up");
			banned.add("speed up");
		}
		if (!lower.contains("nightcore")) {
			banned.add("nightcore");
		}
		if (!lower.contains("8d")) {
			banned.add("8d audio");
		}
		if (!lower.contains("karaoke")) {
			banned.add("karaoke");
		}
		if (!lower.contains("instrumental")) {
			banned.add("instrumental");
		}
		if (!lower.contains("cover")) {
			banned.add("cover");
		}
		if (strict) {
			if (!lower.contains("remix")) {
				banned.add("remix");
			}
			if (!lower.contains("live")) {
				banned.add("live at");
				banned.add("live from");
			}
			if (!lower.contains("mashup")) {
				banned.add("mashup");
			}
			if (!lower.contains("bootleg")) {
				banned.add("bootleg");
			}
		}
		if (banned.isEmpty()) {
			return "";
		}
		return "title !~= '(?i)(" + String.join("|", banned) + ")'";
	}

	private static String buildMatchFilter(
			int targetDurationSec,
			int toleranceSec,
			boolean excludePreviewFormat,
			String titleClause
	) {
		List<String> parts = new ArrayList<>();
		if (excludePreviewFormat) {
			parts.add("format_id !*= preview");
		}
		if (targetDurationSec > 20 && toleranceSec > 0) {
			int minDur = Math.max(10, targetDurationSec - toleranceSec);
			int maxDur = targetDurationSec + toleranceSec;
			parts.add("duration >= " + minDur);
			parts.add("duration <= " + maxDur);
		}
		if (titleClause != null && !titleClause.isBlank()) {
			parts.add(titleClause);
		}
		return String.join(" & ", parts);
	}

	private Path runYtDlpSearchDownload(Path ytDlp, String safeId, String searchSpec, String matchFilter) {
		try {
			String outTemplate = spotifyCacheDir.resolve("spotify_v2_" + safeId + ".%(ext)s").toString();
			List<String> cmd = new ArrayList<>();
			cmd.add(ytDlp.toString());
			cmd.add(searchSpec);
			cmd.add("-f");
			cmd.add("bestaudio[ext=m4a]/http_mp3_1_0/hls_mp3_1_0/bestaudio");
			if (matchFilter != null && !matchFilter.isBlank()) {
				cmd.add("--match-filter");
				cmd.add(matchFilter);
			}
			cmd.add("--max-downloads");
			cmd.add("1");
			cmd.add("-N");
			cmd.add("8");
			cmd.add("--no-playlist");
			cmd.add("--no-warnings");

			Path ffmpeg = findFfmpegDirectory();
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

	private String fetchTrackPreviewUrlFromEmbed(String trackId) {
		if (trackId == null || trackId.isBlank()) {
			return "";
		}
		try {
			String url = "https://open.spotify.com/embed/track/" + trackId;
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() == 200) {
				Matcher m = NEXT_DATA_PATTERN.matcher(resp.body());
				if (m.find()) {
					JsonObject root = JsonParser.parseString(m.group(1)).getAsJsonObject();
					JsonObject entity = root
							.getAsJsonObject("props")
							.getAsJsonObject("pageProps")
							.getAsJsonObject("state")
							.getAsJsonObject("data")
							.getAsJsonObject("entity");
					if (entity.has("audioPreview") && entity.get("audioPreview").isJsonObject()) {
						String pUrl = getJsonString(entity.getAsJsonObject("audioPreview"), "url");
						if (pUrl != null) {
							return pUrl;
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
		return "";
	}

	private Path findCachedFile(String trackId) {
		if (trackId == null || trackId.isBlank() || !Files.isDirectory(spotifyCacheDir)) {
			return null;
		}
		String safeId = trackId.replaceAll("[^a-zA-Z0-9_-]", "");
		String[] exts = {".mp3", ".m4a", ".opus", ".ogg", ".wav", ".flac"};
		for (String ext : exts) {
			Path candidate = spotifyCacheDir.resolve("spotify_v2_" + safeId + ext);
			try {
				if (Files.isRegularFile(candidate) && Files.size(candidate) > 1024L) {
					return candidate;
				}
			} catch (IOException ignored) {
			}
		}
		return null;
	}

	private synchronized Path findOrProvisionYtDlp() {
		if (cachedYtDlpBinary != null && Files.isRegularFile(cachedYtDlpBinary)) {
			ensureYtDlpUpdatedOnce(cachedYtDlpBinary);
			return cachedYtDlpBinary;
		}
		boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String exeName = isWindows ? "yt-dlp.exe" : "yt-dlp";

		// 1. Check pre-extracted mp3s/.bin/yt-dlp-win/yt-dlp.exe (includes _internal DLLs, no %TEMP% extraction)
		if (isWindows) {
			Path unpackedWinBin = binDir.resolve("yt-dlp-win").resolve("yt-dlp.exe");
			if (Files.isRegularFile(unpackedWinBin) && verifyYtDlpWorks(unpackedWinBin)) {
				cachedYtDlpBinary = unpackedWinBin;
				ensureYtDlpUpdatedOnce(unpackedWinBin);
				return unpackedWinBin;
			}
		}

		// 2. Check standalone mp3s/.bin/yt-dlp(.exe)
		Path localBin = binDir.resolve(exeName);
		if (isValidStandaloneYtDlp(localBin, isWindows) && verifyYtDlpWorks(localBin)) {
			cachedYtDlpBinary = localBin;
			ensureYtDlpUpdatedOnce(localBin);
			return localBin;
		}

		// 3. Auto-download official pre-extracted yt-dlp_win.zip on Windows (bundles vcruntime140.dll & python DLLs
		//    in _internal/ and avoids PyInstaller %TEMP% self-extraction blocks), with fallback to standalone yt-dlp
		try {
			Files.createDirectories(binDir);
			setStatusMessage("Lade Audio-Engine (yt-dlp) herunter (einmalig ~23 MB)...", 60_000L);
			Mp3PlayerMod.LOGGER.info("Auto-downloading yt-dlp into {} ...", binDir);

			if (isWindows) {
				Path zipTargetDir = binDir.resolve("yt-dlp-win");
				Path unpackedExe = zipTargetDir.resolve("yt-dlp.exe");
				Path tempZip = binDir.resolve("yt-dlp_win.zip.part");
				Files.deleteIfExists(tempZip);

				if (downloadBinaryWithFallback(
						"https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_win.zip",
						tempZip,
						10_000_000L
				)) {
					extractZipArchive(tempZip, zipTargetDir);
					Files.deleteIfExists(tempZip);
					if (Files.isRegularFile(unpackedExe) && verifyYtDlpWorks(unpackedExe)) {
						Mp3PlayerMod.LOGGER.info("Successfully provisioned yt-dlp at {}", unpackedExe);
						this.ytDlpUpdateChecked = true;
						this.cachedYtDlpBinary = unpackedExe;
						return unpackedExe;
					}
				}
				Files.deleteIfExists(tempZip);
			}

			Files.deleteIfExists(localBin);
			Path tempBin = binDir.resolve(exeName + ".part");
			Files.deleteIfExists(tempBin);

			String downloadUrl = isWindows
					? "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
					: "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
			long minBytes = isWindows ? 12_000_000L : 1_000_000L;
			if (downloadBinaryWithFallback(downloadUrl, tempBin, minBytes)) {
				Files.move(tempBin, localBin, StandardCopyOption.REPLACE_EXISTING);
				localBin.toFile().setExecutable(true);
				if (verifyYtDlpWorks(localBin)) {
					Mp3PlayerMod.LOGGER.info("Successfully provisioned standalone yt-dlp at {}", localBin);
					this.ytDlpUpdateChecked = true;
					this.cachedYtDlpBinary = localBin;
					return localBin;
				}
			}
			Files.deleteIfExists(tempBin);
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Could not auto-download yt-dlp into mp3s/.bin: {}", e.getMessage());
		}

		// 4. Fallback: Check WinGet Links, WinGet Packages & system PATH if offline
		String localAppData = System.getenv("LOCALAPPDATA");
		if (isWindows && localAppData != null) {
			Path wingetLink = Path.of(localAppData, "Microsoft", "WinGet", "Links", "yt-dlp.exe");
			if (isValidStandaloneYtDlp(wingetLink, true) && verifyYtDlpWorks(wingetLink)) {
				cachedYtDlpBinary = wingetLink;
				ensureYtDlpUpdatedOnce(wingetLink);
				return wingetLink;
			}
			Path wingetPkgs = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
			if (Files.isDirectory(wingetPkgs)) {
				try (Stream<Path> walk = Files.walk(wingetPkgs, 4)) {
					Path found = walk
							.filter(p -> isValidStandaloneYtDlp(p, true))
							.filter(p -> p.getFileName().toString().equalsIgnoreCase("yt-dlp.exe"))
							.findFirst()
							.orElse(null);
					if (found != null && verifyYtDlpWorks(found)) {
						cachedYtDlpBinary = found;
						ensureYtDlpUpdatedOnce(found);
						return found;
					}
				} catch (IOException ignored) {
				}
			}
		}

		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
				if (entry == null || entry.isBlank()) {
					continue;
				}
				try {
					Path candidate = Path.of(entry.trim()).resolve(exeName);
					if (Files.isRegularFile(candidate) && verifyYtDlpWorks(candidate)) {
						cachedYtDlpBinary = candidate;
						ensureYtDlpUpdatedOnce(candidate);
						return candidate;
					}
				} catch (Exception ignored) {
				}
			}
		}
		return null;
	}

	private boolean downloadBinaryWithFallback(String url, Path destFile, long minExpectedBytes) {
		// Attempt 1: Java HttpClient
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofSeconds(90))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
					.GET()
					.build();
			HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() == 200) {
				try (InputStream in = resp.body()) {
					Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
				}
				if (Files.isRegularFile(destFile) && Files.size(destFile) >= minExpectedBytes) {
					return true;
				}
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("Primary HttpClient download failed for {}: {}", url, e.getMessage());
		}

		// Attempt 2: Classic HttpURLConnection (HTTP/1.1 with manual redirect handling)
		try {
			Files.deleteIfExists(destFile);
			String currentUrl = url;
			for (int redirects = 0; redirects < 6; redirects++) {
				java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
				conn.setInstanceFollowRedirects(false);
				conn.setConnectTimeout(15_000);
				conn.setReadTimeout(90_000);
				conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
				int code = conn.getResponseCode();
				if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
					String loc = conn.getHeaderField("Location");
					conn.disconnect();
					if (loc == null || loc.isBlank()) {
						break;
					}
					currentUrl = loc;
					continue;
				}
				if (code == 200) {
					try (InputStream in = conn.getInputStream()) {
						Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
					}
					conn.disconnect();
					if (Files.isRegularFile(destFile) && Files.size(destFile) >= minExpectedBytes) {
						return true;
					}
				}
				conn.disconnect();
				break;
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.debug("Fallback HttpURLConnection download failed for {}: {}", url, e.getMessage());
		}
		return false;
	}

	private static void extractZipArchive(Path zipFile, Path targetDir) throws IOException {
		Files.createDirectories(targetDir);
		try (InputStream fis = Files.newInputStream(zipFile);
			 ZipInputStream zis = new ZipInputStream(fis)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				Path outPath = targetDir.resolve(entry.getName()).normalize();
				if (!outPath.startsWith(targetDir)) {
					continue;
				}
				if (entry.isDirectory()) {
					Files.createDirectories(outPath);
				} else {
					if (outPath.getParent() != null) {
						Files.createDirectories(outPath.getParent());
					}
					Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
				}
				zis.closeEntry();
			}
		}
	}

	private static boolean isValidStandaloneYtDlp(Path path, boolean isWindows) {
		try {
			long minBytes = isWindows ? 12_000_000L : 1_000_000L;
			return path != null && Files.isRegularFile(path) && Files.size(path) >= minBytes;
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean verifyYtDlpWorks(Path ytDlpBin) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				ProcessBuilder pb = new ProcessBuilder(ytDlpBin.toString(), "--version");
				pb.redirectErrorStream(true);
				Process p = pb.start();
				String out;
				try (InputStream in = p.getInputStream()) {
					out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				}
				int exit = p.waitFor();
				if (exit == 0 && !out.isEmpty()) {
					return true;
				}
			} catch (Exception e) {
				try {
					Thread.sleep(600L);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return false;
	}

	private void ensureYtDlpUpdatedOnce(Path ytDlpBin) {
		if (ytDlpUpdateChecked || ytDlpBin == null) {
			return;
		}
		ytDlpUpdateChecked = true;
		try {
			ProcessBuilder pb = new ProcessBuilder(ytDlpBin.toString(), "-U");
			pb.redirectErrorStream(true);
			Process p = pb.start();
			try (InputStream in = p.getInputStream()) {
				in.readAllBytes();
			}
			p.waitFor();
		} catch (Exception ignored) {
		}
	}

	private Path findFfmpegDirectory() {
		if (cachedFfmpegDir != null && Files.isDirectory(cachedFfmpegDir)) {
			return cachedFfmpegDir;
		}
		boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String exeName = isWindows ? "ffmpeg.exe" : "ffmpeg";

		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
				if (entry == null || entry.isBlank()) {
					continue;
				}
				try {
					Path dir = Path.of(entry.trim());
					if (Files.isRegularFile(dir.resolve(exeName))) {
						cachedFfmpegDir = dir;
						return dir;
					}
				} catch (Exception ignored) {
				}
			}
		}

		String localAppData = System.getenv("LOCALAPPDATA");
		if (isWindows && localAppData != null) {
			Path wingetPkgs = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
			if (Files.isDirectory(wingetPkgs)) {
				try (Stream<Path> walk = Files.walk(wingetPkgs, 4)) {
					Path found = walk
							.filter(Files::isRegularFile)
							.filter(p -> p.getFileName().toString().equalsIgnoreCase("ffmpeg.exe"))
							.findFirst()
							.orElse(null);
					if (found != null) {
						cachedFfmpegDir = found.getParent();
						return cachedFfmpegDir;
					}
				} catch (IOException ignored) {
				}
			}
		}
		return null;
	}

	private static Mp3TrackInfo overrideSpotifyMetadata(Mp3TrackInfo parsed, SpotifyTrack spotifyTrack) {
		return new Mp3TrackInfo(
				parsed.file(),
				parsed.fileName(),
				parsed.format(),
				spotifyTrack.displayTitle(),
				spotifyTrack.displayArtist(),
				parsed.fileSize(),
				parsed.audioStartOffset(),
				parsed.audioByteLength(),
				parsed.durationSeconds() > 0 ? parsed.durationSeconds() : spotifyTrack.durationSeconds(),
				parsed.bitrateKbps(),
				parsed.sampleRate()
		);
	}

	private void addSavedPlaylistEntry(String entry) {
		List<String> list = new ArrayList<>(config.spotifySavedPlaylists());
		if (!list.contains(entry)) {
			list.add(entry);
			config.setSpotifySavedPlaylists(list);
		}
	}

	private JsonObject sendSpotifyApiGet(String url) throws Exception {
		String token = config.spotifyAccessToken();
		if (token == null || token.isBlank()) {
			return null;
		}
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() == 429) {
			int retrySec = resp.headers().firstValue("Retry-After")
					.map(v -> {
						try {
							return Math.min(5, Math.max(1, Integer.parseInt(v.trim())));
						} catch (NumberFormatException e) {
							return 2;
						}
					}).orElse(2);
			Thread.sleep(retrySec * 1000L);
			resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		if (resp.statusCode() == 200) {
			return JsonParser.parseString(resp.body()).getAsJsonObject();
		}
		return null;
	}

	private static String generatePkceVerifier() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String computePkceChallenge(String verifier) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
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

	private static String buildSuccessHtml(String accountName) {
		return """
				<!DOCTYPE html>
				<html lang="de">
				<head><meta charset="UTF-8"><title>Spotify verknüpft!</title>
				<style>
				  body { background: #0b1120; color: #f8fafc; font-family: system-ui, sans-serif; display: flex; justify-content: center; align-items: center; height: 90vh; margin: 0; }
				  .box { background: #111827; border: 1px solid #1db954; border-radius: 12px; padding: 32px; text-align: center; max-width: 440px; }
				  h1 { color: #1ed760; margin-top: 0; }
				  p { color: #cbd5e1; }
				</style></head>
				<body><div class="box">
				  <h1>✔ Spotify erfolgreich verknüpft!</h1>
				  <p>Verbunden als: <b>%ACCOUNT%</b></p>
				  <p>Du kannst dieses Browser-Fenster jetzt schließen und zu Minecraft zurückkehren.</p>
				</div></body></html>
				""".replace("%ACCOUNT%", escapeHtml(accountName));
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

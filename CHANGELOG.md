# Changelog

All notable changes to the **MP3-Player** mod for Minecraft `26.2` (Fabric) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.3.5] - 2026-11-07

### Added
- **YouTube Music Integration (`ViewTab.YTMUSIC` / `YTM` Tab)**:
  - Added a dedicated `YTM` tab in the MP3 Player, Boombox, and Speaker Controller GUI with account linking via the local browser portal (`http://127.0.0.1:8889/ytmusic`) or direct in-game input (`@Handle`, channel URL, playlist URL, or `browser:<name>`).
  - Full playlist loading without 100-song limits (`--flat-playlist --dump-single-json`), live in-game YouTube Music search (`+ / Search`), and 1:1 exact video ID audio resolution (`ytm_<id>.m4a/.mp3`) across Headphones, 3D Boomboxes, and Linked Speaker Networks (`+` Queue).
- **Saved Third-Party Playlists & Albums Import (YouTube Music & Spotify)**:
  - Automatically discovers playlists created by other users that were saved to the user's YouTube / YouTube Music library (`youtube.com/feed/playlists`, `music.youtube.com/library/playlists`, and `@Handle/playlists`) as well as YouTube Music library browse links (`music.youtube.com/browse/VL...`, `browse/MPREb_...`, `OLAK5uy_...`).
  - Added automatic loading of saved third-party albums from the user's Spotify library (`/v1/me/albums`) alongside saved playlists (`/v1/me/playlists`).
- **Bilingual Browser Linking Portals (`EN | DE`)**:
  - Both the Spotify (`/spotify`) and YouTube Music (`/ytmusic`) browser portals and confirmation pages now automatically detect Minecraft's active language (English or German) and provide a live `EN | DE` language toggle button in the top right corner.

### Changed
- **Expanded GUI Scaling & De-Cluttered Track Rows**:
  - Increased maximum player window dimensions from `368x278` to `448x310` with `24px` row height and inner scissor padding so header playlist titles and two-line track entries have ample breathing room.
  - Replaced repetitive per-row `SPOT` / `YTM` pills on uncached tracks with a compact check indicator only on cached tracks, and separated the YouTube Music vs. Spotify buffering status labels on the bottom deck.

### Fixed
- **YouTube Music `Liked Music` (`LM` / `LL`) & Large `800+` Track Playlist Streaming (Hotfix)**:
  - Switched YouTube Music playlist track loading from buffered `--dump-single-json` to progressive line-by-line JSON streaming (`--flat-playlist -j` with `--extractor-args "youtubetab:skip=authcheck"` and `ProcessBuilder.Redirect.DISCARD` on `stderr`), preventing 4 KB `stderr` pipe buffer deadlocks on large playlists (`800+` songs) and streaming tracks into the UI in real time every 25 songs.
  - Combined `music.youtube.com/playlist?list=LM` and `www.youtube.com/playlist?list=LL` for `Liked Music`, removed the 404-retrying `music.youtube.com/library/playlists` probe, and added `cookies.txt` support (`mp3s/cookies.txt` or direct paste in the `/ytmusic` portal) alongside `Firefox` / `Opera GX` auto-probing for Chromium browsers on Windows that lock cookies via DPAPI v20.

---

## [1.3.4] - 2026-11-06

### Added
- **Spotify Account Linking & Playlist Playback (`ViewTab.SPOTIFY`)**:
  - Added a dedicated `Spot` tab in the MP3 Player, Boombox, and Speaker Controller GUI that requires linking a Spotify account before browsing and playing Spotify playlists.
  - Built-in local loopback OAuth 2.0 PKCE & Account Linking Portal (`http://127.0.0.1:8888/spotify`) with a detailed step-by-step Spotify Developer Application setup guide and 1-click Redirect URI copy button, plus direct in-game support for Spotify Client IDs, Profile URLs, and OAuth tokens persisted in `config/mp3player.properties`.
  - **Full Playlist Pagination (250+ / up to 5,000 Tracks):** Automatically paginates through entire playlists beyond the 100-track Embed limit without hitting rate limits using both the Spotify Web API (`next` pages) and Spotify's Partner Pathfinder GraphQL API (`fetchPlaylistContents`).
  - **Studio-Accurate Audio Resolution & Caching:** Prioritizes official YouTube Topic / Official Audio studio masters verified against the exact Spotify track duration (`±8s` tolerance) while filtering out unofficial `Slowed`, `Sped Up`, `Nightcore`, `8D`, `Karaoke`, `Cover`, and `Remix` uploads, caching streams in `mp3s/.spotify-cache/` (`spotify_v2_<id>.mp3`) for Headphones, 3D Boomboxes, and Linked Speaker Networks (`+` Queue).

### Changed
- **Unlimited Linkable Speakers (`Integer.MAX_VALUE`)**:
  - Raised `Mp3Payloads.MAX_LINKED_SPEAKERS` from `64` to `Integer.MAX_VALUE` (`2,147,483,647`) and removed server speaker control distance caps, allowing an unlimited number of speakers to be linked and streamed simultaneously.

### Fixed
- **Spotify Cache & Automatic `yt-dlp` Provisioning on Clean Systems (Hotfix)**:
  - Automatically downloads and extracts `yt-dlp_win.zip` (bundling all required `_internal` runtime DLLs such as `vcruntime140.dll` and `python314.dll` without `%TEMP%` self-extraction) into `mp3s/.bin/yt-dlp-win/` on startup using a dual `HttpClient` + `HttpURLConnection` fallback and `yt-dlp --version` startup verification for users who do not have `yt-dlp` installed.
  - Added native pure-Java fragmented DASH `.m4a` (`moof`/`traf`/`trun` + `mdat`) frame decoding and O(1) seeking in `M4aFormatDecoder` so cached Spotify `.m4a` streams play seamlessly on systems without `ffmpeg` installed.

---

## [1.3.2] - 2026-11-03

### Added
- **Shared Speaker Playback Queue (`Queue`) & Skip-Vote**:
  - Added a server-synchronized **Speaker Playback Queue** (`ViewTab.QUEUE`) accessible via the header button or inside the Speaker Controller GUI, displaying the currently playing track (`LIVE`) and all upcoming queued tracks along with **which player added each track** (`Added by: <Player>`).
  - Added a `+` queue button to every row in the music library, plus `Skip / Skip-Vote`, per-entry `X` removal, and `Clear` controls in the Queue tab.
  - **Unanimous Skip-Vote for Queue Participants:** When multiple players have added tracks to the queue, skipping the active song requires all players with songs in the queue to agree (`Skip (x/y)`). When only a single player has songs in the queue, clicking `Skip` skips the track immediately.

### Fixed
- **Independent Speaker Networks (`Home-HiFi` vs. `Club`) & Prevented Overlapping Playback**:
  - Scoped speaker queues to independent `96`-block speaker networks (`SpeakerNetworkQueue`) instead of the entire dimension, allowing separate speaker setups outside each other's scan radius to play independently at the same time.
  - When a player connects to a speaker network that already has an active queue, their previous separate speaker network is automatically closed and unlinked.
  - Within the same speaker network, multiple players' tracks are queued sequentially without audio overlap (`S2CQueuePlayTrigger`).
- **Queue UI & Play Button Refinements**:
  - Fixed the `X` remove button outline width in the Queue tab and ensured pressing `Play` only toggles active playback without auto-enqueuing songs (adding songs is done exclusively via the Songs tab).

---

## [1.3.1] - 2026-11-01

### Changed
- **Extended 3D Speaker Range (`±32×32×32`)**:
  - Increased each linked `Speaker` block's 3D OpenAL reference distance (`18m` full-power core) and max distance (`76m` smooth linear attenuation, `80m` server relay radius) to cover a full `±32` block area in `X, Y, Z` (including 3D corners) with strong, clear spatial sound.
- **Session-Scoped Linked Speaker Connections**:
  - Automatically resets and deletes all linked speaker connections on server/world join (`ClientPlayConnectionEvents.JOIN`) and leave/disconnect (`ClientPlayConnectionEvents.DISCONNECT`), keeping the Linked Speaker UI clean across worlds and servers.

### Fixed
- **Sample-Accurate Speaker Synchronization When Linking During Playback**:
  - Newly linked or enabled speakers during active playback now clone the master `OpenAlStreamChannel`'s active buffer queue and immediately snap their hardware `AL_SAMPLE_OFFSET` cursor to match the master stream (with continuous `< 4ms` drift correction), eliminating echo/desync between speakers linked at different times.

---

## [1.3.0] - 2026-11-01

### Added
- **Linkable Full-Block 3D Speakers (`mp3player:speaker`)**:
  - Full `16×16×16` solid directional block (`FACING`, `PLAYING` active cabinet texture) that emits **zero particles** during playback.
  - Streams music from each linked speaker's block position (`x + 0.5, y + 0.5, z + 0.5`) as an **additional synchronized 3D OpenAL stream** to cover large areas in Singleplayer, LAN, Essential World Hosting, and Dedicated Fabric Servers.
- **Speaker Controller Item (`mp3player:speaker_controller`) & Speaker Management GUI**:
  - Right-click any placed `Speaker` block with the `Speaker Controller` to link or unlink it in real time (even mid-song).
  - Right-click in the air to open the **Speaker Management & Playback GUI** (`Speakers` tab), featuring a 96-block `Scan` button, master speaker stream toggle, per-speaker `ON/OFF` mute toggle, per-speaker volume multiplier (`25%` / `50%` / `75%` / `100%`), unlink button, and full playback transport controls.
  - `Shift + Right-Click` in the air with the `Speaker Controller` (or on a `Speaker` block) for instant Play/Pause on the speaker network.

---

## [1.2.0] - 2026-10-30

### Added
- **Placeable 3D Boombox Block & Item (`mp3player:boombox`)**:
  - Craftable Survival stereo Boombox block (`Iron Ingots`, `Redstone`, `Note Blocks`, `Gold Ingot`, `Planks`) available in both the *Tools & Utilities* and *Functional Blocks* Creative Mode Tabs, with a block loot table so breaking drops the Boombox item.
  - Custom 3D stereo cabinet + top carrying handle block model, directional horizontal placement (`FACING`), glowing active LED equalizer blockstate (`PLAYING` with subtle light emission), and floating musical note particles above the Boombox while playing.
  - **World-Anchored 3D Positional Audio:** Plays true 3D directional OpenAL audio (`AL_LINEAR_DISTANCE_CLAMPED` up to 56 blocks) directly from the placed Boombox's block coordinates (`x + 0.5, y + 0.5, z + 0.5`) — audible in 3D **both to the owner and to all nearby players** across Singleplayer, LAN, Essential Mod World Hosting, and Dedicated Fabric Servers, across all 6 audio formats (`.mp3`, `.m4a`, `.opus`, `.wav`, `.flac`, `.ogg`).
  - Right-click any placed Boombox to open the Boombox GUI (select tracks, transfer active playback to/from Headphones, or see who is currently playing on it); `Shift + Right-Click` with an empty hand for quick Play/Pause; breaking the Boombox immediately stops playback for everyone.
- **Dedicated Volume Slider in Minecraft's Music & Sounds Options (`SoundOptionsScreen`)**:
  - Added a native **`MP3 & Boombox`** (`0%` / `OFF` – `100%`) slider directly inside Minecraft's **Options → Music & Sounds...** menu using Fabric `ScreenEvents` (**0 Mixins**).
  - Controls all local MP3-Player, Boombox, and incoming 3D speaker audio volumes independently of `Master Volume` with live real-time gain updates and automatic persistence in `config/mp3player.properties`.

---

## [1.1.0] - 2026-10-30

### Added
- **Multi-Format Audio Support (`.m4a`, `.opus`, `.wav`, `.flac`, `.ogg`, `.mp3`)**:
  - **`.m4a` (MP4 / AAC-LC / HE-AAC / SBR / PS):** Bundled pure-Java `JAAD 0.9.4` decoder with iTunes metadata (`TITLE`, `ARTIST`, `ALBUM_ARTIST`) and O(1) MP4 sample-table seeking.
  - **`.opus` (Ogg Opus):** Bundled pure-Java `Concentus 1.0.1` RFC 7845 decoder with `OpusTags` Vorbis Comment metadata, pre-skip & Q7.8 output-gain handling, trailing granule duration calculation, and O(1) Ogg page-resync seeking.
  - **`.wav` (RIFF/WAVE):** Native pure-Java streaming decoder supporting 8/16/24/32-bit Linear PCM, 32/64-bit IEEE Float, and `WAVE_FORMAT_EXTENSIBLE`, `LIST`/`INFO` (`INAM`/`IART`) metadata, and exact frame-aligned O(1) seeking.
  - **`.flac` (Lossless FLAC):** Bundled pure-Java `jFLAC 1.5.2` streaming decoder supporting 8–32 bit depths, `VorbisComment` metadata, exact `StreamInfo` duration, and instant `SeekTable` / frame-sync seeking.
  - **`.ogg` (Ogg Vorbis & Ogg Opus):** Powered by Minecraft's native `JOrbis` decoder (with automatic codec sniffing that seamlessly delegates Opus-encoded `.ogg` files to the Opus decoder), `VorbisComment` metadata, trailing granule duration calculation, and O(1) Ogg page-resync seeking.
- **Format Badges & Format Search in GUI (`Mp3PlayerScreen`)**:
  - Color-coded format pills (`MP3`, `M4A`, `OPUS`, `WAV`, `FLAC`, `OGG`) next to each track in the playlist.
  - Live search bar now also filters by audio format (e.g., typing `flac` or `opus`).
  - Drag & Drop file/folder import now accepts all 6 audio formats.

### Changed
- **Upgraded 3D Spatial Multiplayer Streaming to 48 kHz Pure-Java Opus (`Opus3dStreamCodec`)**:
  - All 6 local audio formats can now be broadcast live in **3D Speaker Mode** across Dedicated Fabric Servers, LAN, and **Essential Mod** hosted worlds.
  - Decoded local PCM is tapped in 1:1 hardware-clock lockstep with OpenAL, downmixed to mono, continuously resampled to `48 kHz`, and encoded at `64 kbps` (~8 KB/s) into compact `<= 4 KB` `CustomPacketPayload` batches — eliminating clock drift, cutting network bandwidth by up to 95% compared to uncompressed audio, and allowing instant headerless mid-song joining and seeking for any source format.

---

## [1.0.1] - 2026-10-29

### Added
- **In-Game Timeout Simulation Helper (`/mp3timeout`)**: Added a client-side debug command (`TimeoutSimulationHelper`) requiring no server OP permissions or OS firewall rules, designed for reliable timeout testing in Modrinth, Essential World Hosting, LAN, and Multiplayer:
  - `/mp3timeout` / `/mp3timeout now` — Immediately fires a genuine Netty `ReadTimeoutException` (`disconnect.timeout`).
  - `/mp3timeout freeze [1..60]` — Injects a Netty `ChannelDuplexHandler` packet blackhole (preserving underlying AES/CFB8 cipher and compression state) to simulate real pre-timeout packet loss with an on-screen countdown before triggering the timeout.
  - `/mp3timeout cancel` — Removes an active packet freeze and restores normal network traffic without disconnecting.
- Added English (`README.md`, `CHANGELOG.md`) and German (`README.de.md`) repository documentation.

### Fixed
- **Audio Continuing After Network Timeout / Disconnect**: Fixed an issue where local OpenAL MP3 playback continued playing on the `Disconnected` ("Timed out") screen after losing connection to a server.
  - `Mp3AudioController.onClientDisconnect()` now immediately terminates and cleans up both outgoing 3D network streams and the local JLayer decoder / OpenAL audio channel.
  - Added per-tick live connection validation (`Mp3AudioController.hasActiveWorldConnection()`) across both `Mp3AudioController` and `RemoteSpeakerManager` checking `client.level`, `client.player`, and Netty's `Connection.isConnected()` state.

---

## [1.0.0] - 2026-10-28

### Added
- **Portable MP3-Player Survival Item (`mp3player:mp3_player`)**:
  - Custom 16×16 pixel-art item texture and 128×128 mod icon.
  - Survival crafting recipe (`Iron Ingots`, `Glass Pane`, `Note Block`, `Redstone`) and Creative Tab integration (*Tools & Utilities*).
  - Right-click to open the player GUI; `Shift + Right-Click` for instant Play/Pause toggle; configurable hotkey (`M`).
- **Dual Live-Switchable Audio Modes**:
  - **Headphones Mode (Local Stereo):** Private full-stereo OpenAL playback (`AL_FORMAT_STEREO16`) with zero network traffic (works even on unmodded servers).
  - **3D Speaker Mode (Spatial Multiplayer & Essential Mod):** Positional 3D OpenAL audio (`AL_FORMAT_MONO16` downmix with linear distance attenuation up to 56 blocks) and floating musical note particles above the speaker player.
- **Essential-Compatible Real-Time Network Streaming**:
  - Frame-aligned MPEG audio chunking capped at `<= 4 KB` per `CustomPacketPayload` packet over Minecraft's standard play channel (requires no extra UDP ports, avoids packet-size kicks, supports mid-song late-joining listeners, and uses zero Mixins).
- **Modern Hi-Fi In-Game GUI (`Mp3PlayerScreen`)**:
  - Pure-Java ID3v1 / ID3v2.2 / ID3v2.3 / ID3v2.4 metadata parser (Title, Artist, Bitrate, and accurate CBR + Xing/Info/VBRI VBR track duration).
  - Live search bar, scrollable playlist, beat-reactive audio visualizer, clickable/draggable seek bar, Shuffle, Loop (`Off` / `All` / `One`), volume slider, mute-others toggle, one-click folder button, and direct **Drag & Drop** `.mp3` file import from the OS file manager.
  - Persistent client configuration stored in `config/mp3player.properties`.
  - Bundled `JLayer 1.0.1` pure-Java MP3 decoder inside the mod JAR.
  - Full English (`en_us`) and German (`de_de`) localizations.

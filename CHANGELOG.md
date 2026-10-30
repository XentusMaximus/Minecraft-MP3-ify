# Changelog

All notable changes to the **MP3-Player** mod for Minecraft `26.2` (Fabric) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
  - All 6 local audio formats can now be broadcast live in **🔊 3D Speaker Mode** across Dedicated Fabric Servers, LAN, and **Essential Mod** hosted worlds.
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

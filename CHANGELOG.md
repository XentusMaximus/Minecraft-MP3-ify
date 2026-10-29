# Changelog

All notable changes to the **MP3-Player** mod for Minecraft `26.2` (Fabric) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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


# MP3 Player & Boombox (`Minecraft 26.2` · Fabric · `v1.3.1`)

![Version 1.3.1](https://img.shields.io/badge/Version-1.3.1-a855f7?style=flat-square)
![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-2ea44f?style=flat-square)
![Mod Loader](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.20.1-dbd0b4?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25%2B-ed8b00?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-38bdf8?style=flat-square)

**🇺🇸 English** | [🇩🇪 Deutsch](README.de.md) | [📋 Changelog](CHANGELOG.md)

A sleek **in-game multi-format music player & placeable 3D Boombox** mod for **Minecraft 26.2 (Fabric)** — play your own local **`.mp3`**, **`.m4a`**, **`.opus`**, **`.wav`**, **`.flac`**, and **`.ogg`** music collection using a portable **MP3 Player** or place down a **Boombox** in the world for stationary 3D spatial sound, complete with a dedicated volume slider right inside Minecraft's **Music & Sounds Options**!

---

## Features

- **Linkable 3D Speakers (`mp3player:speaker`) & Speaker Controller (`mp3player:speaker_controller`):**
  - **Full 16×16×16 Speaker Blocks with Zero Particles:** Placeable directional full-cube studio speakers that emit **no particles** during playback.
  - **Extended `32×32×32` (`±32` in X, Y, Z) 3D Sound Range & Sample-Accurate Sync:** Every linked speaker plays the music from its exact block coordinates as an **additional sample-synchronized 3D OpenAL stream** (`18m` full-power core, `±32×32×32` coverage up to `76m` max distance / `80m` server relay radius) — audible in 3D to both you and all players in Multiplayer / Essential! Speakers linked mid-song instantly synchronize to the exact active sample frame without echo or delay, and linked speaker connections reset automatically on server join/leave.
  - **Speaker Controller Item (`mp3player:speaker_controller`):** Right-click placed Speaker blocks to link/unlink them, or right-click in the air to open the **Speaker Management & Playback GUI** (featuring **1-click `📡 Scan` within 96 blocks**, per-speaker `ON/OFF` toggle, per-speaker volume `25%–100%`, unlink controls, and full music playback control).
- **Placeable 3D Boombox Block (`mp3player:boombox`, New in `v1.2.0`):**
  - Place a custom 3D stereo Boombox anywhere in your world — music plays in **true 3D directional sound anchored to the Boombox's exact coordinates** (audible in 3D to **both you and all nearby players** within 56 blocks as you walk around or away from it!).
  - Lights up with a glowing LED equalizer display and spawns musical note particles while playing; stops automatically if broken.
  - Seamlessly hand off a currently playing song between **🎧 Headphones** and a placed **📻 Boombox** mid-track!
- **Dedicated Minecraft Options Volume Slider (New in `v1.2.0`):**
  - Native **`MP3 & Boombox`** volume slider (`OFF` – `100%`) directly inside Minecraft's **Options → Music & Sounds...** screen (**0 Mixins**), letting you adjust or mute all mod music independently of `Master Volume`.
- **6 Native Audio Formats Supported Out-of-the-Box:**
  - **`.mp3`** — MPEG-1/2/2.5 Layer I/II/III (`ID3v1` & `ID3v2` tags, CBR + Xing/VBRI VBR duration)
  - **`.m4a`** — MP4 / AAC-LC / HE-AAC (`iTunes` metadata tags & O(1) MP4 sample-table seeking)
  - **`.opus`** — Ogg Opus (`OpusTags` Vorbis Comments, pre-skip & Q7.8 output gain, O(1) page seeking)
  - **`.wav`** — 8/16/24/32-bit Linear PCM & 32/64-bit IEEE Float (`LIST`/`INFO` tags & exact sample seeking)
  - **`.flac`** — Lossless 8–32 bit FLAC (`VorbisComment` metadata & `SeekTable` / frame-sync seeking)
  - **`.ogg`** — Ogg Vorbis & Ogg Opus (`VorbisComment` metadata & O(1) page seeking)
- **Three Live-Switchable Audio Output Modes:**
  - **Headphones (Local):** Full stereo playback at native sample rate exclusively for yourself (`0` network traffic — works on any server, including vanilla servers).
  - **Handheld 3D Speaker (Multiplayer & Essential):** Positional 3D audio that follows your character as you walk around.
  - **Placed Boombox (Stationary 3D Block):** Positional 3D audio anchored to the placed Boombox block in the world.
- **100% Essential Mod Compatible (48 kHz Opus 3D Relay):** Streams live `48 kHz` Opus audio (`~8 KB/s`) in compact `<= 4 KB` packets over Minecraft's standard play networking channel — **works with every supported audio format**, requires **no extra UDP port forwarding**, avoids oversized-payload kicks, supports **instant headerless mid-song late-joining**, and uses **0 Mixins** (conflict-free with Essential, Sodium, Iris, Lithium, etc.).
- **Modern Hi-Fi In-Game GUI:**
  - Live search by song title, artist, filename, or **format badge** (`FLAC`, `OPUS`, `M4A`, `WAV`, `OGG`, `MP3`)
  - Color-coded format badges, beat-reactive equalizer bars, clickable/draggable **seek bar**, **Shuffle**, **Loop** (`Off` / `All` / `One`), **Volume Slider**, **Mute Others**, and **Drag & Drop** file/folder import!
- **All-in-One Pure-Java JAR:** Bundles `JLayer` (MP3), `Concentus` (Opus), `jFLAC` (FLAC), and `JAAD` (M4A/AAC) alongside Minecraft's native `JOrbis` (OGG) and a built-in WAV engine — zero FFmpeg or native DLL installs required; only **Fabric API** is needed.

---

## Installation & Setup

### Requirements
- **Minecraft:** `26.2`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** for `26.2`
- **Java:** `25+`

### Getting Started
1. Drop `mp3player-1.3.1.jar` alongside **Fabric API (`26.2`)** into your Minecraft instance's `mods` folder (e.g., in the Modrinth App).
   > **Essential World Hosting & Multiplayer Note:** To use **3D Speaker Mode**, a placed **Boombox**, or **Linked Speakers** with friends, all players (and the Fabric server, if dedicated) must have `mp3player-1.3.1.jar` installed. Your friends do **not** need your audio files on their PC — audio from any format is Opus-encoded on-the-fly and streamed live in real time!
2. **Add Your Own Audio Files (3 Ways):**
   - **In-Game Button:** Open the MP3 Player or Boombox → Click **"Folder"** *(automatically opens `<minecraft-instance>/mp3s/` in your file manager)*.
   - **Drag & Drop:** Drag `.mp3`, `.m4a`, `.opus`, `.wav`, `.flac`, or `.ogg` files (or folders) directly onto the open player GUI inside Minecraft.
   - **Manual Copy:** Place your audio files inside `<minecraft-instance>/mp3s/` *(subfolders are scanned automatically)*.

---

## Crafting Recipes

### 1. Handheld MP3 Player (`mp3player:mp3_player`)

| | Column 1 | Column 2 | Column 3 |
| :--- | :---: | :---: | :---: |
| **Top** | Iron Ingot | Glass Pane | Iron Ingot |
| **Middle** | Iron Ingot | Note Block | Iron Ingot |
| **Bottom** | Iron Ingot | Redstone Dust | Iron Ingot |

### 2. Placeable 3D Boombox (`mp3player:boombox`)

| | Column 1 | Column 2 | Column 3 |
| :--- | :---: | :---: | :---: |
| **Top** | Iron Ingot | Redstone Dust | Iron Ingot |
| **Middle** | Note Block | Gold Ingot | Note Block |
| **Bottom** | Any Planks | Any Planks | Any Planks |

### 3. Linkable 3D Speaker (`mp3player:speaker`, crafts `2×`)

| | Column 1 | Column 2 | Column 3 |
| :--- | :---: | :---: | :---: |
| **Top** | Any Planks | Any Planks | Any Planks |
| **Middle** | Iron Ingot | Note Block | Iron Ingot |
| **Bottom** | Any Planks | Redstone Dust | Any Planks |

### 4. Speaker Controller (`mp3player:speaker_controller`)

| | Column 1 | Column 2 | Column 3 |
| :--- | :---: | :---: | :---: |
| **Top** | — | Redstone Torch | — |
| **Middle** | Iron Ingot | Glass Pane | Iron Ingot |
| **Bottom** | Copper Ingot | Redstone Dust | Copper Ingot |

*(All items are available in the Creative Inventory under **Tools & Utilities**, and the Boombox, Speaker, and Speaker Controller are also listed under **Functional Blocks**.)*

---

## Controls & Shortcuts

| Action | Function |
| :--- | :--- |
| **Right-Click** *(holding MP3 Player)* | Open the Handheld MP3 Player GUI |
| **Shift + Right-Click** *(holding MP3 Player)* | Quick **Play / Pause** toggle |
| **Right-Click** *(on placed Boombox)* | Open the **Boombox GUI** (plays 3D sound from that block) |
| **Shift + Right-Click** *(on placed Boombox, empty hand)* | Quick **Play / Pause** on that Boombox |
| **Right-Click** *(on Speaker Block with Speaker Controller)* | **Link / Unlink** that Speaker block (`±32×32×32` 3D range) |
| **Right-Click in Air** *(holding Speaker Controller)* | Open the **Speaker Management & Playback GUI** (`96`-block `📡 Scan`, per-speaker volume & mute) |
| **Shift + Right-Click** *(holding Speaker Controller)* | Quick **Play / Pause** across linked Speakers |
| **`M` Key** *(configurable in Controls)* | Open/close the GUI *(requires MP3 Player, Speaker Controller, Boombox, or Speaker in inventory, active playback, or Creative/Spectator)* |
| **Options → Music & Sounds... → `MP3 & Boombox`** | Global music volume slider (`OFF` – `100%`) independent of Master Volume |
| **`Spacebar`** *(inside GUI)* | Play / Pause |
| **`←` / `→` Arrow Keys** *(inside GUI)* | Seek `-5s` / `+5s` |

All user preferences (Minecraft Options music volume, deck volume, audio mode, shuffle, loop mode, mute others) are saved automatically to `config/mp3player.properties`.

### Debug / Timeout Testing Helper (Client Command)

| Command | Effect |
| :--- | :--- |
| `/mp3timeout` *(or `/mp3timeout now`)* | Immediately triggers a genuine Netty `ReadTimeoutException` (`Timed out` disconnect). |
| `/mp3timeout freeze [1..60]` | Drops all inbound & outbound packets in the Netty pipeline (default: `8s`) with an actionbar countdown before firing the real timeout. |
| `/mp3timeout cancel` | Cancels an active `freeze` simulation before the timeout fires and seamlessly restores packet flow. |

---

## Project Structure & Building from Source

```text
src/
├── main/
│   ├── java/de/vibecoded/mp3player/
│   │   ├── Mp3PlayerMod.java            # Item, Boombox & Speaker Block, Creative Tab & Payload registration
│   │   ├── block/                       # BoomboxBlock & full-cube linkable SpeakerBlock (no particles)
│   │   ├── item/                        # Mp3PlayerItem, BoomboxItem, SpeakerItem & SpeakerControllerItem
│   │   ├── mp3/                         # Multi-format metadata parsers, Ogg demuxer & WAV/MP3 headers
│   │   └── network/                     # <= 4 KB CustomPayloads & 3D Speaker/Boombox server relay
│   └── resources/                       # fabric.mod.json, models, recipes, loot_table, textures & lang
└── client/
    └── java/de/vibecoded/mp3player/client/
        ├── Mp3PlayerClient.java         # Client entrypoint, keymapping & lifecycle hooks
        ├── audio/                       # Sample-synced OpenAL channels, 48 kHz Opus 3D codec & decoders
        │   └── decoder/                 # Streaming PCM + seek decoders (MP3, M4A, OPUS, WAV, FLAC, OGG)
        ├── config/Mp3Config.java        # Persistent player & Sound Options preferences
        ├── debug/                       # Client-side Netty timeout simulation helper (/mp3timeout)
        ├── gui/                         # Mp3PlayerScreen (Tracks & Speakers tabs) & SoundOptionsInjector
        └── library/Mp3LibraryManager.java
```

### Build Instructions
Requires **JDK 25**:

```powershell
.\gradlew.bat build
```

The compiled mod JAR will be output to `build/libs/mp3player-1.3.1.jar`.

---

## License & Author

- **Author:** IceKaffee
- **License:** MIT

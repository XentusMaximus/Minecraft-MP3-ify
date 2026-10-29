# 🎧 MP3 Player (`Minecraft 26.2` · Fabric)

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-2ea44f?style=flat-square)
![Mod Loader](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.20.1-dbd0b4?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25%2B-ed8b00?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-38bdf8?style=flat-square)

**🇺🇸 English** | [🇩🇪 Deutsch](README.de.md) | [📋 Changelog](CHANGELOG.md)

A sleek, portable **in-game MP3 Player** mod for **Minecraft 26.2 (Fabric)** — play your own local `.mp3` music collection using a craftable Survival item, featuring instant live switching between private **Stereo Headphones Mode** and **Essential-compatible 3D Spatial Speaker Mode**.

---

## ✨ Features

- **Craftable Survival Item** with custom pixel-art texture & Creative Mode Tab integration (*Tools & Utilities*).
- **Two Live-Switchable Audio Modes** (switchable mid-song with zero playback interruption):
  - 🎧 **Headphones (Local):** Full stereo playback exclusively for yourself (`0` network traffic — works on any server, including vanilla servers).
  - 🔊 **3D Speaker (Multiplayer & Essential):** True OpenAL 3D directional audio with linear distance attenuation (up to 56 blocks) and musical note particles floating above your character.
- **100% Essential Mod Compatible:** Streams pure frame-aligned MPEG audio chunks in compact `<= 4 KB` packets over Minecraft's standard play networking channel — **no extra UDP port forwarding** required, **no oversized-payload kicks**, **late-join listener support**, and **0 Mixins** (conflict-free with Essential, Sodium, Iris, Lithium, etc.).
- **Modern Hi-Fi In-Game GUI:**
  - Live search by song title, artist, or filename
  - Built-in **ID3v1 / ID3v2 Metadata Parser** (Title, Artist, Bitrate, plus accurate CBR & Xing/Info/VBRI VBR track duration)
  - Beat-reactive real-time equalizer bars
  - Clickable & draggable progress bar (**seeking** automatically syncs across nearby 3D listeners)
  - **Shuffle**, **Loop** (`Off` / `All` / `One`), **Volume Slider**, and a **Mute Others** toggle for incoming 3D streams
  - **Drag & Drop Import:** Drag `.mp3` files (or music folders) directly from your OS file manager onto the open Minecraft window!
- **All-in-One JAR:** Bundles the pure-Java `JLayer 1.0.1` MP3 decoder inside the mod JAR — only **Fabric API** is required.
- **Localized:** Built-in English (`en_us`) and German (`de_de`) translations.

---

## 📥 Installation & Setup

### Requirements
- **Minecraft:** `26.2`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** for `26.2`
- **Java:** `25+`

### Getting Started
1. Drop `mp3player-1.0.1.jar` alongside **Fabric API (`26.2`)** into your Minecraft instance's `mods` folder (e.g., in the Modrinth App).
   > 💡 **Essential World Hosting & Multiplayer Note:** To use **🔊 3D Speaker Mode** with friends, all players (and the Fabric server, if dedicated) must have the mod JAR installed. Your friends do **not** need your `.mp3` files on their PC — audio is streamed live in real time!
2. **Add Your Own MP3 Files (3 Ways):**
   - 📂 **In-Game Button:** Right-click with the MP3 Player → Click **"Folder"** *(automatically opens `<minecraft-instance>/mp3s/` in your file manager)*.
   - 🖱️ **Drag & Drop:** Drag `.mp3` files (or folders) directly onto the open MP3 Player GUI inside Minecraft.
   - 📁 **Manual Copy:** Place your `.mp3` files inside `<minecraft-instance>/mp3s/` *(subfolders are scanned automatically)*.

---

## 🛠️ Crafting Recipe

Craft the **MP3 Player** in Survival Mode at a Crafting Table:

| | Column 1 | Column 2 | Column 3 |
| :--- | :---: | :---: | :---: |
| **Top** | Iron Ingot | Glass Pane | Iron Ingot |
| **Middle** | Iron Ingot | Note Block | Iron Ingot |
| **Bottom** | Iron Ingot | Redstone Dust | Iron Ingot |

*(Also available in the Creative Inventory under **Tools & Utilities** or via `/give @s mp3player:mp3_player`.)*

---

## 🎮 Controls & Shortcuts

| Action | Function |
| :--- | :--- |
| **Right-Click** *(holding item)* | Open the MP3 Player GUI |
| **Shift + Right-Click** *(holding item)* | Quick **Play / Pause** toggle without opening the GUI |
| **`M` Key** *(configurable in Controls)* | Open/close the GUI *(requires the item in your inventory, or Creative/Spectator mode)* |
| **`Spacebar`** *(inside GUI)* | Play / Pause |
| **`←` / `→` Arrow Keys** *(inside GUI)* | Seek `-5s` / `+5s` |

All user preferences (volume, audio mode, shuffle, loop mode, mute others) are saved automatically to `config/mp3player.properties`.

### 🧪 Debug / Timeout Testing Helper (Client Command)

To reliably test network drops and connection timeouts directly inside Modrinth (works on Dedicated Servers, Essential Worlds, LAN, and Singleplayer **without** server OP permissions or OS firewall tweaks):

| Command | Effect |
| :--- | :--- |
| `/mp3timeout` *(or `/mp3timeout now`)* | Immediately triggers a genuine Netty `ReadTimeoutException` (`Timed out` disconnect). |
| `/mp3timeout freeze [1..60]` | Drops all inbound & outbound packets in the Netty pipeline (world freezes identically to a real connection drop; default: `8s`) with an actionbar countdown before firing the real timeout. |
| `/mp3timeout cancel` | Cancels an active `freeze` simulation before the timeout fires and seamlessly restores packet flow. |

---

## 🏗️ Project Structure & Building from Source

```text
src/
├── main/
│   ├── java/de/vibecoded/mp3player/
│   │   ├── Mp3PlayerMod.java            # Item, Creative Tab & Payload registration
│   │   ├── item/Mp3PlayerItem.java      # In-game item interaction & tooltips
│   │   ├── mp3/                         # ID3v1/v2 parser, CBR/VBR duration & MPEG frame chunker
│   │   └── network/                     # <= 4 KB CustomPayloads & 3D server relay
│   └── resources/                       # fabric.mod.json, recipe, textures & lang (en_us / de_de)
└── client/
    └── java/de/vibecoded/mp3player/client/
        ├── Mp3PlayerClient.java         # Client entrypoint, keymapping & lifecycle hooks
        ├── audio/                       # Async JLayer decoder, OpenAL channel & remote 3D streams
        ├── config/Mp3Config.java        # Persistent player preferences
        ├── debug/                       # Client-side Netty timeout simulation helper (/mp3timeout)
        ├── gui/Mp3PlayerScreen.java     # Modern in-game player UI + Drag & Drop import
        └── library/Mp3LibraryManager.java
```

### Build Instructions
Requires **JDK 25** (if multiple JDKs are installed on your system, update or remove `org.gradle.java.home` in `gradle.properties` so Gradle points to your local JDK 25 installation):

```powershell
.\gradlew.bat build
```

The compiled mod JAR will be output to `build/libs/mp3player-1.0.1.jar`.

---

## 📄 License & Author

- **Author:** IceKaffee
- **License:** MIT

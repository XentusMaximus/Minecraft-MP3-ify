# 🎧 MP3-Player (`Minecraft 26.2` · Fabric · `v1.1.0`)

![Version 1.1.0](https://img.shields.io/badge/Version-1.1.0-a855f7?style=flat-square)
![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-2ea44f?style=flat-square)
![Mod Loader](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.20.1-dbd0b4?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25%2B-ed8b00?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-38bdf8?style=flat-square)

[🇺🇸 English](README.md) | **🇩🇪 Deutsch** | [📋 Changelog](CHANGELOG.md)

Ein moderner, tragbarer **Ingame-Multiformat-Musikplayer** für **Minecraft 26.2 (Fabric)** — spiele deine eigenen lokalen **`.mp3`**, **`.m4a`**, **`.opus`**, **`.wav`**, **`.flac`** und **`.ogg`** Dateien über ein craftbares Survival-Item ab, wahlweise privat im **Stereo-Kopfhörer-Modus** oder für alle Mitspieler hörbar im **Essential-kompatiblen 3D-Lautsprecher-Modus**.

---

## ✨ Features

- **6 native Audioformate direkt unterstützt (`v1.1.0+`):**
  - **`.mp3`** — MPEG-1/2/2.5 Layer I/II/III (`ID3v1` & `ID3v2` Tags, CBR- & Xing/VBRI-VBR-Laufzeit)
  - **`.m4a`** — MP4 / AAC-LC / HE-AAC (`iTunes`-Metadaten & blitzschnelles MP4-Sample-Seeking)
  - **`.opus`** — Ogg Opus (`OpusTags` Vorbis-Comments, Pre-Skip & Q7.8 Output-Gain, O(1)-Seeking)
  - **`.wav`** — 8/16/24/32-Bit Linear PCM & 32/64-Bit IEEE Float (`LIST`/`INFO`-Tags & samplegenaues Seeking)
  - **`.flac`** — Lossless 8–32 Bit FLAC (`VorbisComment`-Metadaten & `SeekTable`-/Frame-Sync-Seeking)
  - **`.ogg`** — Ogg Vorbis & Ogg Opus (`VorbisComment`-Metadaten & O(1)-Seeking)
- **Craftbares Survival-Item** mit eigenem Pixel-Art-Icon & Creative-Tab-Integration (*Werkzeuge & Hilfsmittel*).
- **Zwei live umschaltbare Audio-Modi** (auch mitten im laufenden Song bei allen 6 Formaten ohne Unterbrechung):
  - 🎧 **Kopfhörer (Lokal):** Voller Stereo-Sound in nativer Sample-Rate nur für dich selbst (`0` Netzwerk-Traffic, funktioniert auch auf Vanilla-/Fremdservern).
  - 🔊 **3D-Lautsprecher (Multiplayer & Essential):** Echter OpenAL-3D-Richtungsklang mit Distanz-Dämpfung (bis 56 Blöcke) und Musiknoten-Partikeln über deinem Charakter.
- **100 % Essential-Mod-kompatibel (48 kHz Opus-3D-Streaming):** Codiert jedes der 6 Quellformate live in kompakte `48 kHz` Opus-Pakete (`~8 KB/s`, `<= 4 KB` pro Paket) über Minecrafts Standard-Play-Kanal — **keine externen UDP-Ports** nötig, **keine Paketgrößen-Kicks**, **sofortiges Mit-Hören mitten im Song** und **0 Mixins** (keine Konflikte mit Essential, Sodium, Iris & Co.).
- **Modernes Hi-Fi Ingame-GUI:**
  - Live-Suche nach Titel, Künstler, Dateiname oder **Audioformat** (`FLAC`, `OPUS`, `M4A`, `WAV`, `OGG`, `MP3`)
  - Farbige Format-Badges & automatische Metadaten-/Laufzeiterkennung für alle 6 Formate
  - Musik-reaktiver Echtzeit-Equalizer
  - Anklick- & ziehbarer Fortschrittsbalken (**Seeking** funktioniert bei allen 6 Formaten und synchronisiert sich live mit allen 3D-Zuhörern)
  - **Shuffle**, **Loop** (`Aus` / `Alle` / `1x`), **Lautstärke-Slider** & Stummschalt-Option für fremde 3D-Streams
  - **Drag & Drop Import:** Ziehe `.mp3`, `.m4a`, `.opus`, `.wav`, `.flac` oder `.ogg` Dateien (oder ganze Musikordner) direkt aus Windows auf das geöffnete Minecraft-Fenster!
- **All-in-One Pure-Java JAR:** Alle Decoder (`JLayer`, `Concentus`, `jFLAC`, `JAAD` sowie Minecrafts `JOrbis` & eigener WAV-Decoder) sind komplett in reinem Java enthalten — kein FFmpeg und keine externen DLLs nötig, es wird nur die **Fabric API** benötigt.

---

## 📥 Installation & Nutzung

### Voraussetzungen
- **Minecraft:** `26.2`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** für `26.2`
- **Java:** `25+`

### Einrichtung
1. Lege die gebaute `mp3player-1.1.0.jar` zusammen mit der **Fabric API (`26.2`)** in den `mods`-Ordner deiner Minecraft-Instanz (z. B. in der Modrinth App).
   > 💡 **Hinweis für Essential World Hosting & Server:** Für den **🔊 3D-Lautsprecher** müssen alle Mitspieler (bzw. der Fabric-Server) `mp3player-1.1.0.jar` installiert haben. Deine Freunde benötigen deine Musikdateien **nicht** auf ihrem PC — egal ob du `.flac`, `.wav`, `.m4a`, `.opus`, `.ogg` oder `.mp3` abspielst, die Musik wird live in Echtzeit als Opus-Stream übertragen!
2. **Eigene Audiodateien hinzufügen (3 Möglichkeiten):**
   - 📂 **Ingame-Button:** Rechtsklick mit dem MP3-Player → Klick auf **„Ordner"** *(öffnet automatisch `<minecraft-instanz>/mp3s/` im Explorer)*.
   - 🖱️ **Drag & Drop:** Ziehe `.mp3`, `.m4a`, `.opus`, `.wav`, `.flac` oder `.ogg` Dateien (oder ganze Musikordner) aus Windows direkt in das geöffnete MP3-Player-GUI in Minecraft.
   - 📁 **Manuell:** Kopiere deine Audiodateien in den Ordner `<minecraft-instanz>/mp3s/` *(Unterordner werden ebenfalls automatisch erkannt)*.

---

## 🛠️ Crafting-Rezept

Der **MP3-Player** lässt sich im Survival-Modus an der Werkbank herstellen:

| | Spalte 1 | Spalte 2 | Spalte 3 |
| :--- | :---: | :---: | :---: |
| **Oben** | Eisenbarren | Glasscheibe | Eisenbarren |
| **Mitte** | Eisenbarren | Notenblock | Eisenbarren |
| **Unten** | Eisenbarren | Redstone | Eisenbarren |

*(Alternativ im Creative-Inventar unter **Werkzeuge & Hilfsmittel** oder per `/give @s mp3player:mp3_player` erhältlich.)*

---

## 🎮 Steuerung & Shortcuts

| Aktion | Funktion |
| :--- | :--- |
| **Rechtsklick** *(mit Item in der Hand)* | Öffnet das MP3-Player-GUI |
| **Shift + Rechtsklick** *(mit Item in der Hand)* | Schnelles **Play / Pause** ohne Öffnen des Menüs |
| **Taste `M`** *(konfigurierbar in den Optionen)* | Öffnet/schließt das GUI *(sofern sich das Item im Inventar befindet oder Creative aktiv ist)* |
| **`Leertaste`** *(im geöffneten GUI)* | Play / Pause |
| **`←` / `→` Pfeiltasten** *(im geöffneten GUI)* | `-5 Sek.` / `+5 Sek.` spulen |

Alle Einstellungen (Lautstärke, Audio-Modus, Shuffle, Loop, Stummschaltung anderer Spieler) werden automatisch in `config/mp3player.properties` gespeichert.

### 🧪 Debug / Timeout-Test-Helper (Client-Befehl)

Um Verbindungsabbrüche & Timeouts direkt aus Modrinth heraus (auf jedem Server, in Essential-Welten oder im Singleplayer **ohne** OP-Rechte und ohne Windows-Firewall-Eingriffe) zuverlässig zu testen:

| Befehl | Wirkung |
| :--- | :--- |
| `/mp3timeout` *(oder `/mp3timeout now`)* | Löst sofort einen echten Netty-`ReadTimeoutException` (`Zeitüberschreitung`) aus. |
| `/mp3timeout freeze [1..60]` | Blockiert alle ein- & ausgehenden Pakete in der Netty-Pipeline (Welt friert wie bei echtem Internetabbruch ein, Standard: `8s`) und löst danach den echten Timeout aus. |
| `/mp3timeout cancel` | Hebt eine laufende `freeze`-Blockade vor Ablauf des Countdowns wieder auf. |

---

## 🏗️ Projektstruktur & Build aus dem Quellcode

```text
src/
├── main/
│   ├── java/de/vibecoded/mp3player/
│   │   ├── Mp3PlayerMod.java            # Item-, Tab- & Netzwerk-Registrierung
│   │   ├── item/Mp3PlayerItem.java      # Ingame-Item & Rechtsklick-/Tooltip-Logik
│   │   ├── mp3/                         # Multiformat-Metadaten-Parser, Ogg-Demuxer & WAV/MP3-Header
│   │   └── network/                     # <= 4 KB CustomPayloads & 3D-Server-Relay
│   └── resources/                       # fabric.mod.json, Crafting-Rezept, Texturen & Übersetzungen (DE/EN)
└── client/
    └── java/de/vibecoded/mp3player/client/
        ├── Mp3PlayerClient.java         # Client-Entrypoint, Keybinding & Event-Hooks
        ├── audio/                       # OpenAL-Channel, 48 kHz Opus-3D-Codec & Format-Decoder
        │   └── decoder/                 # Streaming-PCM + Seek-Decoder (MP3, M4A, OPUS, WAV, FLAC, OGG)
        ├── config/Mp3Config.java        # Persistente Spielereinstellungen
        ├── debug/                       # Client-seitiger Timeout-Simulator (/mp3timeout)
        ├── gui/Mp3PlayerScreen.java     # Modernes Ingame-Player-UI + Format-Badges + Drag & Drop
        └── library/Mp3LibraryManager.java
```

### Selbst kompilieren
Vorausgesetzt wird ein installiertes **JDK 25**:

```powershell
.\gradlew.bat build
```

Die fertige Mod-JAR liegt anschließend unter `build/libs/mp3player-1.1.0.jar`.

---

## 📄 Lizenz & Autor

- **Autor:** IceKaffee
- **Lizenz:** MIT

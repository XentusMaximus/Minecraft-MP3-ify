# MP3-Player & Boombox (`Minecraft 26.2` · Fabric · `v1.3.1`)

![Version 1.3.1](https://img.shields.io/badge/Version-1.3.1-a855f7?style=flat-square)
![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-2ea44f?style=flat-square)
![Mod Loader](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.20.1-dbd0b4?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25%2B-ed8b00?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-38bdf8?style=flat-square)

[🇺🇸 English](README.md) | **🇩🇪 Deutsch** | [📋 Changelog](CHANGELOG.md)

Ein moderner **Ingame-Multiformat-Musikplayer & platzierbare 3D-Boombox** für **Minecraft 26.2 (Fabric)** — spiele deine eigenen lokalen **`.mp3`**, **`.m4a`**, **`.opus`**, **`.wav`**, **`.flac`** und **`.ogg`** Dateien über den tragbaren **MP3-Player** ab oder stelle eine **Boombox** in die Welt, die echten 3D-Raumklang von ihrer Block-Position aus abstrahlt — inklusive eigenem Lautstärke-Regler direkt in den **Minecraft-Musik-&-Geräusche-Optionen**!

---

## Features

- **Verlinkbare 3D-Lautsprecher (`mp3player:speaker`) & Lautsprecher-Controller (`mp3player:speaker_controller`):**
  - **Volle 16×16×16 Lautsprecher-Blöcke ohne Partikel:** Platzierbare, direktionale Studio-Lautsprecher als volle Blöcke, die während der Wiedergabe **keine Partikel** erzeugen.
  - **Erweiterte `32×32×32` (`±32` in X, Y, Z) 3D-Reichweite & Sample-genaue Synchronisation:** Jeder verknüpfte Lautsprecher strahlt die laufende Musik exakt von seiner eigenen Block-Position als **zusätzlichen sample-synchronen 3D-OpenAL-Stream** ab (`18 m` Vollpegel-Kernbereich, `±32×32×32` Abdeckung bis `76 m` Maximaldistanz / `80 m` Server-Relay-Radius — hörbar für dich selbst und für alle Mitspieler im Multiplayer / Essential!). Auch während der laufenden Wiedergabe neu verknüpfte Lautsprecher synchronisieren sich sofort sample-genau ohne Echo oder Versatz; beim Betreten oder Verlassen eines Servers werden die Lautsprecher-Verbindungen automatisch zurückgesetzt.
  - **Lautsprecher-Controller (`mp3player:speaker_controller`):** Verknüpfe oder trenne Lautsprecher per Rechtsklick auf den Block oder öffne mit Rechtsklick in die Luft das **Lautsprecher-Verwaltungs- & Wiedergabe-GUI** (inkl. **96-Blöcke-Umgebungsscan `📡 Scannen`**, Einzel-Stummschaltung `AN/AUS`, individueller Lautstärke pro Lautsprecher `25 %–100 %` und voller Musiksteuerung).
- **Platzierbare 3D-Boombox (`mp3player:boombox`, Neu in `v1.2.0`):**
  - Stelle die 3D-Stereo-Boombox beliebig in deiner Welt auf — die Musik erklingt in **echtem 3D-Richtungsklang exakt von der Position der Boombox** (in 3D hörbar **sowohl für dich selbst als auch für alle Mitspieler** im Umkreis von 56 Blöcken!).
  - Leuchtet während der Wiedergabe mit aktivem LED-Equalizer-Display und Musiknoten-Partikeln; stoppt sofort, wenn der Block abgebaut wird.
  - Laufende Songs lassen sich mitten im Track nahtlos zwischen **🎧 Kopfhörer** und einer platzierten **📻 Boombox** übergeben!
- **Eigener Lautstärke-Slider in den Minecraft-Optionen (Neu in `v1.2.0`):**
  - Nativer **`MP3 & Boombox`**-Regler (`AUS` – `100 %`) direkt unter **Optionen → Musik & Geräusche...** (**0 Mixins**), mit dem du die Musiklautstärke unabhängig von der Gesamtlautstärke (*Master Volume*) regeln oder stummschalten kannst.
- **6 native Audioformate direkt unterstützt:**
  - **`.mp3`** — MPEG-1/2/2.5 Layer I/II/III (`ID3v1` & `ID3v2` Tags, CBR- & Xing/VBRI-VBR-Laufzeit)
  - **`.m4a`** — MP4 / AAC-LC / HE-AAC (`iTunes`-Metadaten & blitzschnelles MP4-Sample-Seeking)
  - **`.opus`** — Ogg Opus (`OpusTags` Vorbis-Comments, Pre-Skip & Q7.8 Output-Gain, O(1)-Seeking)
  - **`.wav`** — 8/16/24/32-Bit Linear PCM & 32/64-Bit IEEE Float (`LIST`/`INFO`-Tags & samplegenaues Seeking)
  - **`.flac`** — Lossless 8–32 Bit FLAC (`VorbisComment`-Metadaten & `SeekTable`-/Frame-Sync-Seeking)
  - **`.ogg`** — Ogg Vorbis & Ogg Opus (`VorbisComment`-Metadaten & O(1)-Seeking)
- **Drei live umschaltbare Audio-Ausgabemodi:**
  - **Kopfhörer (Lokal):** Voller Stereo-Sound in nativer Sample-Rate nur für dich selbst (`0` Netzwerk-Traffic, funktioniert auch auf Vanilla-/Fremdservern).
  - **Tragbarer 3D-Lautsprecher (Multiplayer & Essential):** 3D-Richtungsklang, der deinem Charakter beim Laufen folgt.
  - **Platzierte Boombox (Stationärer 3D-Block):** 3D-Richtungsklang fest verankert an der platzierten Boombox in der Welt.
- **100 % Essential-Mod-kompatibel (48 kHz Opus-3D-Streaming):** Codiert jedes der 6 Quellformate live in kompakte `48 kHz` Opus-Pakete (`~8 KB/s`, `<= 4 KB` pro Paket) über Minecrafts Standard-Play-Kanal — **keine externen UDP-Ports** nötig, **keine Paketgrößen-Kicks**, **sofortiges Mit-Hören mitten im Song** und **0 Mixins** (keine Konflikte mit Essential, Sodium, Iris & Co.).
- **Modernes Hi-Fi Ingame-GUI:**
  - Live-Suche nach Titel, Künstler, Dateiname oder **Audioformat** (`FLAC`, `OPUS`, `M4A`, `WAV`, `OGG`, `MP3`), farbige Format-Badges, Echtzeit-Equalizer, **Seeking**, **Shuffle**, **Loop** (`Aus` / `Alle` / `1x`), **Lautstärke-Slider**, **Stummschaltung anderer Streams** & **Drag & Drop Import**!

---

## Installation & Nutzung

### Voraussetzungen
- **Minecraft:** `26.2`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** für `26.2`
- **Java:** `25+`

### Einrichtung
1. Lege die gebaute `mp3player-1.3.1.jar` zusammen mit der **Fabric API (`26.2`)** in den `mods`-Ordner deiner Minecraft-Instanz (z. B. in der Modrinth App).
   > **Hinweis für Essential World Hosting & Server:** Für den **🔊 3D-Lautsprecher**, die **📻 Boombox** und **🔊 verlinkte Lautsprecher** müssen alle Mitspieler (bzw. der Fabric-Server) `mp3player-1.3.1.jar` installiert haben. Deine Freunde benötigen deine Musikdateien **nicht** auf ihrem PC — die Musik wird live in Echtzeit als Opus-Stream übertragen!
2. **Eigene Audiodateien hinzufügen (3 Möglichkeiten):**
   - **Ingame-Button:** MP3-Player oder Boombox öffnen → Klick auf **„Ordner"** *(öffnet automatisch `<minecraft-instanz>/mp3s/` im Explorer)*.
   - **Drag & Drop:** Ziehe `.mp3`, `.m4a`, `.opus`, `.wav`, `.flac` oder `.ogg` Dateien (oder ganze Musikordner) aus Windows direkt in das geöffnete Player-GUI in Minecraft.
   - **Manuell:** Kopiere deine Audiodateien in den Ordner `<minecraft-instanz>/mp3s/` *(Unterordner werden ebenfalls automatisch erkannt)*.

---

## Crafting-Rezepte

### 1. Tragbarer MP3-Player (`mp3player:mp3_player`)

| | Spalte 1 | Spalte 2 | Spalte 3 |
| :--- | :---: | :---: | :---: |
| **Oben** | Eisenbarren | Glasscheibe | Eisenbarren |
| **Mitte** | Eisenbarren | Notenblock | Eisenbarren |
| **Unten** | Eisenbarren | Redstone | Eisenbarren |

### 2. Platzierbare 3D-Boombox (`mp3player:boombox`)

| | Spalte 1 | Spalte 2 | Spalte 3 |
| :--- | :---: | :---: | :---: |
| **Oben** | Eisenbarren | Redstone | Eisenbarren |
| **Mitte** | Notenblock | Goldbarren | Notenblock |
| **Unten** | Holzbretter | Holzbretter | Holzbretter |

### 3. Verlinkbarer 3D-Lautsprecher (`mp3player:speaker`, ergibt `2×`)

| | Spalte 1 | Spalte 2 | Spalte 3 |
| :--- | :---: | :---: | :---: |
| **Oben** | Holzbretter | Holzbretter | Holzbretter |
| **Mitte** | Eisenbarren | Notenblock | Eisenbarren |
| **Unten** | Holzbretter | Redstone | Holzbretter |

### 4. Lautsprecher-Controller (`mp3player:speaker_controller`)

| | Spalte 1 | Spalte 2 | Spalte 3 |
| :--- | :---: | :---: | :---: |
| **Oben** | — | Redstone-Fackel | — |
| **Mitte** | Eisenbarren | Glasscheibe | Eisenbarren |
| **Unten** | Kupferbarren | Redstone | Kupferbarren |

*(Alle Items sind im Creative-Inventar unter **Werkzeuge & Hilfsmittel** zu finden; Boombox, Lautsprecher und Lautsprecher-Controller zusätzlich unter **Funktionale Blöcke**.)*

---

## Steuerung & Shortcuts

| Aktion | Funktion |
| :--- | :--- |
| **Rechtsklick** *(mit MP3-Player in der Hand)* | Öffnet das MP3-Player-GUI |
| **Shift + Rechtsklick** *(mit MP3-Player in der Hand)* | Schnelles **Play / Pause** |
| **Rechtsklick** *(auf platzierte Boombox)* | Öffnet das **Boombox-GUI** (spielt 3D-Sound vom Block ab) |
| **Shift + Rechtsklick** *(auf Boombox mit leerer Hand)* | Schnelles **Play / Pause** an dieser Boombox |
| **Rechtsklick** *(auf Lautsprecher mit Lautsprecher-Controller)* | **Verknüpft / trennt** diesen Lautsprecher-Block (`±32×32×32` 3D-Reichweite) |
| **Rechtsklick in die Luft** *(mit Lautsprecher-Controller)* | Öffnet das **Lautsprecher-Verwaltungs- & Wiedergabe-GUI** (`96`-Blöcke `📡 Scannen`, Einzel-Lautstärke & Stummschaltung) |
| **Shift + Rechtsklick** *(mit Lautsprecher-Controller)* | Schnelles **Play / Pause** über alle verknüpften Lautsprecher |
| **Taste `M`** *(konfigurierbar in den Optionen)* | Öffnet/schließt das GUI *(mit MP3-Player, Controller, Boombox oder Lautsprecher im Inventar, bei aktiver Wiedergabe oder im Creative)* |
| **Optionen → Musik & Geräusche... → `MP3 & Boombox`** | Eigener Lautstärke-Slider (`AUS` – `100 %`) unabhängig von der Gesamtlautstärke |
| **`Leertaste`** *(im geöffneten GUI)* | Play / Pause |
| **`←` / `→` Pfeiltasten** *(im geöffneten GUI)* | `-5 Sek.` / `+5 Sek.` spulen |

Alle Einstellungen (Minecraft-Optionen-Musiklautstärke, Player-Lautstärke, Audio-Modus, Shuffle, Loop, Stummschaltung anderer Spieler) werden automatisch in `config/mp3player.properties` gespeichert.

### Debug / Timeout-Test-Helper (Client-Befehl)

| Befehl | Wirkung |
| :--- | :--- |
| `/mp3timeout` *(oder `/mp3timeout now`)* | Löst sofort einen echten Netty-`ReadTimeoutException` (`Zeitüberschreitung`) aus. |
| `/mp3timeout freeze [1..60]` | Blockiert alle ein- & ausgehenden Pakete in der Netty-Pipeline (Standard: `8s`) und löst danach den echten Timeout aus. |
| `/mp3timeout cancel` | Hebt eine laufende `freeze`-Blockade vor Ablauf des Countdowns wieder auf. |

---

## Projektstruktur & Build aus dem Quellcode

```text
src/
├── main/
│   ├── java/de/vibecoded/mp3player/
│   │   ├── Mp3PlayerMod.java            # Item-, Boombox- & Lautsprecher-Block-, Tab- & Netzwerk-Registrierung
│   │   ├── block/                       # BoomboxBlock & voller verlinkbarer SpeakerBlock (ohne Partikel)
│   │   ├── item/                        # Mp3PlayerItem, BoomboxItem, SpeakerItem & SpeakerControllerItem
│   │   ├── mp3/                         # Multiformat-Metadaten-Parser, Ogg-Demuxer & WAV/MP3-Header
│   │   └── network/                     # <= 4 KB CustomPayloads & 3D-Speaker/Boombox-Server-Relay
│   └── resources/                       # fabric.mod.json, Modelle, Rezepte, Loot-Table, Texturen & Lang
└── client/
    └── java/de/vibecoded/mp3player/client/
        ├── Mp3PlayerClient.java         # Client-Entrypoint, Keybinding & Event-Hooks
        ├── audio/                       # Sample-synchrone OpenAL-Channels, 48 kHz Opus-3D-Codec & Decoder
        │   └── decoder/                 # Streaming-PCM + Seek-Decoder (MP3, M4A, OPUS, WAV, FLAC, OGG)
        ├── config/Mp3Config.java        # Persistente Spieler- & Sound-Optionen-Einstellungen
        ├── debug/                       # Client-seitiger Timeout-Simulator (/mp3timeout)
        ├── gui/                         # Mp3PlayerScreen (Songs- & Lautsprecher-Tab) & SoundOptionsInjector
        └── library/Mp3LibraryManager.java
```

### Selbst kompilieren
Vorausgesetzt wird ein installiertes **JDK 25**:

```powershell
.\gradlew.bat build
```

Die fertige Mod-JAR liegt anschließend unter `build/libs/mp3player-1.3.1.jar`.

---

## Lizenz & Autor

- **Autor:** IceKaffee
- **Lizenz:** MIT

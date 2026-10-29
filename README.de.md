# 🎧 MP3-Player (`Minecraft 26.2` · Fabric)

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-2ea44f?style=flat-square)
![Mod Loader](https://img.shields.io/badge/Loader-Fabric%20%E2%89%A50.20.1-dbd0b4?style=flat-square)
![Java 25](https://img.shields.io/badge/Java-25%2B-ed8b00?style=flat-square)
![License MIT](https://img.shields.io/badge/License-MIT-38bdf8?style=flat-square)

[🇺🇸 English](README.md) | **🇩🇪 Deutsch** | [📋 Changelog](CHANGELOG.md)

Ein moderner, tragbarer **Ingame-MP3-Player** für **Minecraft 26.2 (Fabric)** — spiele deine eigenen lokalen `.mp3`-Dateien über ein craftbares Survival-Item ab, wahlweise privat im **Stereo-Kopfhörer-Modus** oder für alle Mitspieler hörbar im **Essential-kompatiblen 3D-Lautsprecher-Modus**.

---

## ✨ Features

- **Craftbares Survival-Item** mit eigenem Pixel-Art-Icon & Creative-Tab-Integration (*Werkzeuge & Hilfsmittel*).
- **Zwei live umschaltbare Audio-Modi** (auch mitten im laufenden Song ohne Unterbrechung):
  - 🎧 **Kopfhörer (Lokal):** Voller Stereo-Sound nur für dich selbst (`0` Netzwerk-Traffic, funktioniert auch auf Vanilla-/Fremdservern).
  - 🔊 **3D-Lautsprecher (Multiplayer & Essential):** Echter OpenAL-3D-Richtungsklang mit Distanz-Dämpfung (bis 56 Blöcke) und Musiknoten-Partikeln über deinem Charakter.
- **100 % Essential-Mod-kompatibel:** Streamt reine MPEG-Audioframes in kleinen `<= 4 KB` Paketen direkt über Minecrafts Standard-Play-Kanal — **keine externen UDP-Ports** nötig, **keine Paketgrößen-Kicks** und **0 Mixins** (keine Konflikte mit Essential, Sodium, Iris & Co.).
- **Modernes Hi-Fi Ingame-GUI:**
  - Live-Suche nach Titel, Künstler oder Dateiname
  - Automatischer **ID3v1 / ID3v2 Metadaten-Parser** (Titel, Künstler, Bitrate sowie exakte CBR- & Xing/VBRI-VBR-Laufzeit)
  - Musik-reaktiver Echtzeit-Equalizer
  - Anklick- & ziehbarer Fortschrittsbalken (**Seeking** synchronisiert sich live mit allen 3D-Zuhörern)
  - **Shuffle**, **Loop** (`Aus` / `Alle` / `1x`), **Lautstärke-Slider** & Stummschalt-Option für fremde 3D-Streams
  - **Drag & Drop Import:** Ziehe `.mp3`-Dateien direkt aus Windows auf das geöffnete Minecraft-Fenster!
- **All-in-One JAR:** Der reine Java-MP3-Decoder (`JLayer 1.0.1`) ist direkt in der Mod-JAR eingebettet — es wird nur die **Fabric API** benötigt.

---

## 📥 Installation & Nutzung

### Voraussetzungen
- **Minecraft:** `26.2`
- **Fabric Loader:** `>= 0.20.1`
- **Fabric API:** für `26.2`
- **Java:** `25+`

### Einrichtung
1. Lege die gebaute `mp3player-1.0.1.jar` zusammen mit der **Fabric API (`26.2`)** in den `mods`-Ordner deiner Minecraft-Instanz (z. B. in der Modrinth App).
   > 💡 **Hinweis für Essential World Hosting & Server:** Für den **🔊 3D-Lautsprecher** müssen alle Mitspieler (bzw. der Fabric-Server) die Mod-JAR installiert haben. Deine Freunde benötigen deine `.mp3`-Dateien **nicht** auf ihrem PC — die Musik wird live in Echtzeit gestreamt!
2. **Eigene MP3-Dateien hinzufügen (3 Möglichkeiten):**
   - 📂 **Ingame-Button:** Rechtsklick mit dem MP3-Player → Klick auf **„Ordner"** *(öffnet automatisch `<minecraft-instanz>/mp3s/` im Explorer)*.
   - 🖱️ **Drag & Drop:** Ziehe `.mp3`-Dateien (oder ganze Musikordner) aus Windows direkt in das geöffnete MP3-Player-GUI in Minecraft.
   - 📁 **Manuell:** Kopiere deine `.mp3`-Dateien in den Ordner `<minecraft-instanz>/mp3s/` *(Unterordner werden ebenfalls automatisch erkannt)*.

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
│   │   ├── mp3/                         # ID3v1/v2-Parser, VBR/CBR-Dauer & MPEG-Frame-Chunker
│   │   └── network/                     # <= 4 KB CustomPayloads & 3D-Server-Relay
│   └── resources/                       # fabric.mod.json, Crafting-Rezept, Texturen & Übersetzungen (DE/EN)
└── client/
    └── java/de/vibecoded/mp3player/client/
        ├── Mp3PlayerClient.java         # Client-Entrypoint, Keybinding & Event-Hooks
        ├── audio/                       # Async JLayer-Decoder, OpenAL-Channel & Remote-3D-Streams
        ├── config/Mp3Config.java        # Persistente Spielereinstellungen
        ├── debug/                       # Client-seitiger Timeout-Simulator (/mp3timeout)
        ├── gui/Mp3PlayerScreen.java     # Modernes Ingame-Player-UI + Drag & Drop
        └── library/Mp3LibraryManager.java
```

### Selbst kompilieren
Vorausgesetzt wird ein installiertes **JDK 25** (falls mehrere Java-Versionen installiert sind, ggf. `org.gradle.java.home` in der `gradle.properties` auf dein lokales JDK-25-Verzeichnis anpassen oder die Zeile entfernen, wenn `JAVA_HOME` bereits auf Java 25 zeigt):

```powershell
.\gradlew.bat build
```

Die fertige Mod-JAR liegt anschließend unter `build/libs/mp3player-1.0.1.jar`.

---

## 📄 Lizenz & Autor

- **Autor:** IceKaffee
- **Lizenz:** MIT


# Jarvis – persönlicher Schul- und Alltagsassistent für Android

Jarvis läuft dauerhaft auf einem alten Android-Handy (ohne PC, ohne SIM) und kümmert sich um Schule und Alltag:

- **Schule → Google Kalender** (optional, standardmäßig aus): Prüfungen, Leistungsnachweise, Ausflüge, Fristen, Änderungen und Absagen landen automatisch im Kalender, ohne Duplikate, mit Erinnerungen. EduPage wird direkt gelesen, **jede andere Schul-App oder Schulseite** (WebUntis, IServ, Schulmanager, Moodle, Homepage …) über den allgemeinen Web-Modus.
- **Telegram:** Jarvis schreibt dir (Briefings, Änderungen, Rückfragen) und versteht Text und Sprachnachrichten.
- **Anrufe:** Eine eigene Anruf-App auf deinem Haupthandy. Jarvis klingelt wie ein normaler Kontakt in der Telefon-App und spricht mit einer natürlichen Stimme (Gemini Live).
- **Ruft selbst an, wenn es dringend ist:** Prüfung morgen neu eingetragen, Termin abgesagt („Soll ich dir die freie Stunde blocken?“) usw.
- **Hausaufgaben-Tracker** (aus EduPage plus selbst diktiert) mit Abfrage am Vorabend, **Lernplan vor Prüfungen**, **Vertretungsplan live**, **Noten mit Schnitt**, **Weckanruf mit Briefing**.
- **PC im Blick:** Ein kleiner PC-Agent meldet Auslastung, Temperatur, Speicher, Updates und hängende Programme. Auf Zuruf fährt Jarvis den PC herunter, startet ihn neu, sperrt ihn, regelt die Lautstärke oder beendet ein hängendes Programm. Spätabends fragt er: „Dein PC läuft noch – soll ich ihn ausschalten?“
- **E-Mails:** Jarvis liest dein Postfach (nur lesen), meldet wichtige Mails sofort mit Kurzfassung und packt den Rest ins Briefing. Werbung fliegt raus.
- **Gehirn:** Claude (über dein eigenes Claude-Abo in Termux) oder kostenlos Groq. Groq springt ein, wenn das Claude-Limit erreicht ist.

> Alles läuft ohne laufende Kosten (kostenlose Stufen von Groq, Gemini, Telegram, Open-Meteo). Es werden nirgends Zahlungsdaten hinterlegt.

---

## Was du brauchst

| | |
|---|---|
| **Jarvis-Handy** | Ein Android-Handy (ab Android 12), das immer am Strom und im WLAN bleibt. Darauf läuft `Jarvis` (core). |
| **Dein Handy** | Darauf läuft `Jarvis Anruf` (call). Damit ruft Jarvis dich an und du ihn. |
| **Telegram-Bot** | Kostenlos über [@BotFather](https://t.me/BotFather) (`/newbot`), du bekommst ein Token. |
| **Groq-Schlüssel** | Kostenlos auf [console.groq.com](https://console.groq.com/keys). Nicht nötig bei `brain = nur-claude`. |
| **Gemini-Schlüssel** | Kostenlos auf [aistudio.google.com](https://aistudio.google.com/apikey) (für die Telefonstimme). |
| **Schul-Zugang** (optional) | Deine normale Anmeldung bei EduPage oder der Schul-App deiner Schule. |
| **Claude** (optional) | Ein Claude-Abo und Termux auf dem Jarvis-Handy, siehe unten. Ohne Claude nutzt Jarvis Groq. |

## Einrichtung

1. **APKs bauen** (siehe [Bauen](#bauen)) oder aus den Releases laden.
2. **Jarvis-Handy:** `Jarvis` installieren und öffnen, alle Berechtigungen erlauben (Kalender, Benachrichtigungen). Unter Android-Einstellungen → Akku für Jarvis „Nicht optimieren“ wählen.
3. In der App unter **Zugänge** Groq-Schlüssel, Gemini-Schlüssel und Telegram-Bot-Token eintragen. Die Zugänge werden verschlüsselt im Android-Keystore gespeichert.
4. **Telegram koppeln:** Die App zeigt einen Code. Schick deinem Bot `/start CODE`. Ab dann nimmt Jarvis nur von deinem Telegram-Konto Befehle an.
5. **Einstellungen** in der App ausfüllen (oder vor dem Bauen in `jarvis.properties`), siehe unten.
6. **Schule** (optional), siehe [Schule einrichten](#schule-einrichten).
7. **Dein Handy:** `Jarvis Anruf` installieren, Mikrofon erlauben und den Kopplungscode aus der Jarvis-App eintragen. Optional: Jarvis als Kontakt speichern und „Anrufe über Telefon-App“ einschalten. Die Kachel „Jarvis“ in den Schnelleinstellungen ruft ihn mit einem Tipp an.

## Konfiguration: `jarvis.properties`

Im Hauptordner liegt `jarvis.properties`. Sie enthält die **Startwerte** und wird beim Bauen in die App gepackt. Alles darin lässt sich später in der App unter **Einstellungen** ändern. Was dort steht, gilt vor der Datei. Zugangsschlüssel gehören **nicht** in diese Datei, die trägst du in der App ein.

Die wichtigste Wahl ist das **Gehirn**:

| `brain =` | Was passiert |
|---|---|
| `beides` (Standard) | Claude (dein Abo, über Termux) antwortet. Ist Claude nicht erreichbar oder das Abo-Limit voll, springt der API-Schlüssel (Groq) ein. Nach einem Limit prüft Jarvis alle 20 Minuten, ob Claude wieder geht. |
| `nur-claude` | Nur Claude. Ohne Claude antwortet Jarvis nicht frei, Kalender, Erinnerungen und Schulabgleich laufen trotzdem. |
| `nur-api` | Nur der API-Schlüssel (Groq, kostenlos). Kein Termux, kein Claude nötig. |

Sprachnachrichten in Telegram und die Ersatzstimme am Telefon nutzen die Spracherkennung von Groq, dafür lohnt sich der kostenlose Groq-Schlüssel auch bei `nur-claude`.

## Eigener Name

Alles Persönliche steht in den Einstellungen (bzw. `jarvis.properties`), nicht im Code:

| Einstellung | Beispiel | Wofür |
|---|---|---|
| `Dein Vorname` | `Lena` | So spricht Jarvis dich an. |
| `Über dich, kurz` | `8. Klasse, Musterschule (8a), wohnt in Musterstadt` | Hintergrund für Jarvis. |
| `Name des Assistenten` | `Jarvis` (oder `Friday`, `Karen` …) | Wie sich der Assistent nennt. |
| `EduPage-Schule (Subdomain)` | `meineschule` für `meineschule.edupage.org` | Deine Schule. |
| `Wohnort für das Wetter` | `48.137` / `11.575` | Breiten- und Längengrad (z. B. aus Google Maps: Rechtsklick auf den Ort). |
| Erinnerungen, Ruhezeit, Briefing-Zeiten, Weckanruf, Anruf-Limit … | | Siehe App. |

## Schule einrichten

Die Schulfunktionen sind **standardmäßig aus** (`school_enabled = 0`). So schaltest du sie ein:

1. Jarvis-App → Einstellungen → **Schule** → „Schulfunktionen einschalten“.
2. Bei **Schule suchen** den Namen deiner Schule mit Ort eingeben (z. B. „Gymnasium Musterstadt“) **oder** die Adresse, die du sonst im Browser benutzt (z. B. `meineschule.de` oder `meineschule.webuntis.com`).
3. Jarvis probiert die passende EduPage-Adresse, findet die Schul-Homepage über offene Daten (OpenStreetMap, Wikidata), schaut dort und auf Info-Unterseiten nach Links zu Schul-Apps (EduPage, WebUntis, IServ, Schulmanager, Eltern-Portal, Moodle, itslearning …) und **prüft jeden Treffer** (erreichbar? welche Plattform?). Findet er nichts, gib die Adresse direkt ein.
4. Treffer auswählen:
   - **EduPage:** Jarvis liest EduPage direkt. Du meldest dich einmal auf der EduPage-Seite an, Jarvis speichert nur die Sitzung, nicht dein Passwort. Das ist der vollständige Modus: Prüfungen, Ausflüge, Hausaufgaben, Stundenplan, Vertretungen, Noten.
   - **Alles andere** (WebUntis, IServ, Schulmanager, Moodle, Schulhomepage …): Jarvis öffnet die Seite im eingebauten Browser. Du meldest dich an, öffnest die Seite mit Neuigkeiten, Terminen oder Hausaufgaben und tippst **„Diese Seite beobachten“**. Jarvis prüft sofort, ob er die Seite lesen kann.
5. Ab dann liest Jarvis die beobachteten Seiten regelmäßig. Neue Abschnitte werden wie Schulnachrichten ausgewertet: Termine in den Kalender, Unklares als Rückfrage, Wichtiges als Nachricht. Beim ersten Lesen merkt er sich nur, was schon da ist.

Läuft eine Anmeldung ab, sagt Jarvis Bescheid. Dann einfach in der App „Neu anmelden“ drücken.

**Ehrliche Grenze:** Der Web-Modus liest den sichtbaren Text einer Seite. Bei Apps, die Inhalte erst nach Klicks nachladen, musst du genau die Unterseite beobachten lassen, auf der die Inhalte stehen. Stundenplan, Vertretungen und Noten als eigene Funktionen gibt es bisher nur mit EduPage.

Die meisten Einstellungen kannst du Jarvis auch einfach schreiben, zum Beispiel „Weck mich an Schultagen um 6:40“.

**Kontaktnummer der Anruf-App:** Die App benutzt eine erfundene Nummer, damit Jarvis wie ein normaler Kontakt aussieht (`CallSession.NUMBER` in `call/src/.../CallSession.java`). Anrufe an diese Nummer werden **vor dem Wählen abgefangen** und gehen an Jarvis, es entsteht nie ein echter Mobilfunkanruf. Wer eine andere Nummer will, ändert sie dort vor dem Bauen. Nimm eine Nummer, die es sicher nicht gibt.

## Claude als Gehirn (optional)

Jarvis kann Claude über **dein eigenes Claude-Abo** nutzen. Dabei sieht Jarvis deine Claude-Zugangsdaten nie.

1. [Termux](https://github.com/termux/termux-app/releases) installieren (GitHub-Version).
2. In Termux: `pkg install python proot-distro`, dann `proot-distro install debian`. Darin Claude Code mit dem offiziellen Installer installieren und mit `claude` **selbst anmelden**.
3. In `~/.termux/termux.properties` die Zeile `allow-external-apps = true` eintragen.
4. `tools/termux/jarvis-bridge.py` nach `~/jarvis-bridge.py` in Termux kopieren.
5. In den Jarvis-Einstellungen `Gehirn` auf `claude` lassen. Jarvis startet die Brücke selbst.

Die Brücke läuft nur auf `127.0.0.1`. Claude bekommt **keine** eigenen Datei-, Shell- oder Web-Werkzeuge, nur die Jarvis-Werkzeuge (Kalender, Schule, Erinnerungen …).

## PC-Agent (Linux)

Der Agent ist ein kleines Java-Programm (`jarvis-pc.jar`, Java 17+), das über dieselbe Ende-zu-Ende-verschlüsselte Verbindung wie die Anruf-App mit Jarvis spricht.

```bash
java -jar jarvis-pc.jar pair JARVISPC1.…   # Code: Jarvis-App → Einstellungen → PC → „PC-Kopplungscode anzeigen“
java -jar jarvis-pc.jar install            # richtet einen systemd-Benutzerdienst ein (startet bei jeder Anmeldung)
java -jar jarvis-pc.jar status             # zeigt, was Jarvis sieht
```

Jarvis kann am PC **nur** diese Aktionen auslösen: `shutdown`, `reboot`, `suspend` (jeweils mit 60 Sekunden Warnung auf dem Bildschirm, abbrechbar), `cancel`, `lock`, `notify`, `volume`, `mute` und `kill` (ein Programm beenden; System- und Desktop-Prozesse sind geschützt). Beliebige Befehle sind nicht möglich. Nützliche Helfer: `checkupdates` (Arch, für die Update-Anzeige), `notify-send`, `wpctl`.

## E-Mail

In der Jarvis-App unter Einstellungen die Adresse (bei „Verhalten“) und unter „E-Mail“ das Passwort eintragen. Der IMAP-Server wird aus der Adresse erkannt (Gmail, GMX, web.de, T-Online, Yahoo, iCloud …).

- **Gmail:** 2-Faktor-Anmeldung einschalten, dann unter Google-Konto → Sicherheit → **App-Passwörter** eines erstellen und das eintragen.
- **GMX / web.de:** In den Mail-Einstellungen „POP3/IMAP-Abruf“ erlauben.
- **Outlook/Hotmail:** Microsoft erlaubt dafür kein einfaches Passwort mehr – geht derzeit nicht.

Jarvis liest nur (Mails bleiben ungelesen), verschickt, löscht und verschiebt nie etwas. Mail-Inhalte gelten als Daten, nicht als Befehle.

## Bauen

Ohne Gradle, direkt mit dem Android-SDK:

```bash
# Voraussetzungen: JDK 21, Android SDK (build-tools 36.0.0, platform android-36)
python3 tools/build.py call    # baut die Anruf-App und bettet sie in Jarvis ein
python3 tools/build.py core    # baut Jarvis
python3 tools/build.py pc      # baut den PC-Agenten (build/jarvis-pc.jar)
```

Ergebnis: `build/jarvis-core.apk` und `build/jarvis-call.apk`. Beim ersten Bauen wird ein eigener Signierschlüssel unter `~/.config/jarvis-build/` angelegt. Er liegt nicht im Repository.

`JARVIS_DEBUG=1 python3 tools/build.py core` baut eine Entwicklerversion mit Testbefehlen über ADB (`DebugReceiver`).

## Sicherheit und Grenzen

- Schlüssel und Tokens liegen nur verschlüsselt auf dem Jarvis-Handy, nie im Code.
- Jarvis schreibt nur dir und ruft nur deine gekoppelte Anruf-App an. Er schreibt nie von selbst an Lehrkräfte oder andere.
- Inhalte aus EduPage und dem Web gelten als Daten, nicht als Befehle.
- Löschen, Käufe und Nachrichten an andere passieren nur auf ausdrückliche Anweisung.
- Kostenlose Limits: Groq etwa 1000 Anfragen/Tag pro Modell, Gemini Live im Free Tier, Claude nach deinem Abo. Ist ein Limit erreicht, sagt Jarvis das ehrlich und weicht auf Groq aus.

## Aufbau

```
core/    Jarvis (läuft auf dem Jarvis-Handy): EduPage, Kalender, Telegram, KI, Anruf-Steuerung
call/    Jarvis Anruf (dein Handy): Telefon-Integration, Gemini Live, Ersatzstimme
shared/  Gemeinsamer Code: Schul-Parser, verschlüsselte Verbindung, WebSocket, Gemini Live
tools/   Bauen, Tests, Termux-Brücke
```

Die Verbindung zwischen den beiden Handys läuft Ende-zu-Ende-verschlüsselt (X25519 + AES-GCM) über ntfy.sh.

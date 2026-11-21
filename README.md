# DonutSMP Spin Casino (Fabric 1.21.11)

Slot-Machine-Spin mit **virtuellen, nicht übertragbaren Punkten**. Läuft im Einzelspieler und auf Servern.

## JAR bauen (ohne Programme auf deinem PC)
1. Auf github.com ein kostenloses Konto anlegen, neues Repository erstellen.
2. Den **Inhalt** dieses Ordners hochladen (Add file → Upload files; `.github` und `.gitignore` sind versteckt – am besten alles per Drag & Drop aus dem Explorer markieren).
3. Reiter **Actions** → Workflow „build“ läuft automatisch (ca. 5–10 Min.).
4. Fertigen Lauf öffnen → unten bei **Artifacts** `donutspin-jar` herunterladen → ZIP entpacken → `donutspin-1.0.0.jar`.
5. Das Build-Log zeigt auch den Selbsttest der Spiellogik (muss „ALLE … PRÜFUNGEN BESTANDEN“ melden).

## Installation
Fabric Loader (≥ 0.18) und **Fabric API** für 1.21.11 installieren, die JAR in `mods/` legen.
Auf Servern muss die Mod **auch beim Spieler** installiert sein (wegen der GUI). Ohne GUI geht `/spin bet <Betrag>`.

## Spielen
| Befehl | Wirkung |
|---|---|
| `/spin` | GUI öffnen (Walze, Countdown, Quoten, Einsatz, Verlauf, Statistik) |
| `/spin bet 5M` / `/spin cancel` | Einsatz (1M–500M) setzen / stornieren |
| `/spin balance`, `/spin stats` | Guthaben, eigene Statistik |
| `/spin odds` | Auszahlungstabelle mit Wahrscheinlichkeiten |
| `/spin house` | virtuelle Betreiberbilanz inkl. Prüfsumme |
| `/spin verify <Runde>` | Seed/Hash einer Runde und dein Wurf zum Nachprüfen |
| `/spin give <Spieler> <Betrag>`, `/spin set …` | nur Admins |

Neue Spieler starten mit 10M Punkten. **Admins:** im Einzelspieler der Host; auf Servern Konsole und alle Namen in `<Welt>/donutspin-admins.txt` (ein Name pro Zeile).

## Rundenablauf (60 s)
44 s Setzen (Countdown) → 9 s Walzen-Animation → 7 s Ergebnis. Ohne Einsätze läuft der Countdown einfach weiter.
Einsätze werden nur reserviert und erst bei der Abrechnung verbucht (Absturz = kein Punktverlust).

## Auszahlungstabelle (komplett, keine versteckten Gewichte)
| 0x | 0.1x | 0.4x | 1x | 1.2x | 1.5x | 2x | 4x | 7x | 12x | 24x |
|---|---|---|---|---|---|---|---|---|---|---|
| 3.3% | 30% | 22% | 20% | 12% | 6.63% | 0.5% | 4% | 1.2% | 0.3% | 0.07% |

Rückzahlung = Σ(Wahrscheinlichkeit × Multiplikator) = **86,825 %**, Hausvorteil 13,175 %. Auszahlung = Einsatz × Multiplikator, abgerundet.

## Fairness (nachprüfbar)
Vor jeder Runde wird `SHA-256(Seed)` angezeigt, nach der Runde der Seed selbst.
Dein Wurf = erste 8 Bytes von `SHA-256(Seed + ":" + deine-UUID)` als Zahl mod 10000, zugeordnet über die Tabelle (Wurf 0–329 → 0x, 330–3329 → 0.1x, …).
Gespeichert wird in `<Welt>/donutspin.json` (letzte 200 Seeds). Buchhaltungs-Invariante: Summe Guthaben + Betreiberbilanz = ausgegebene Punkte.

## Falls der Build scheitert
* „Version … nicht gefunden“: in `gradle.properties` die aktuellen Versionen von https://fabricmc.net/develop eintragen.
* Compile-Fehler in Minecraft-Klassen: Log kopieren und mir schicken (Mojang-Mappings, Namen können sich leicht unterscheiden).

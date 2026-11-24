# Tattoo Try-On (Android)

Mit der App kannst du ein Tattoo aus einem Foto freistellen (z. B. aus einem Pinterest-Bild). Das Ergebnis lässt sich auf ein eigenes Körperfoto legen oder live per Kamera (AR mit Körpererkennung) am Körper ansehen.

## Funktionen

**Tattoos hinzufügen (automatisch)**
- Ein oder mehrere Fotos wählen. Die App erkennt das Tattoo in jedem Bild, stellt es frei und legt es unter „Meine Tattoos“ ab.
- Die Erkennung schätzt die Hautfarbe lokal (auch bei Schatten) und markiert alles, was deutlich dunkler oder farbiger ist. Daraus wählt sie den größten, möglichst mittigen Bereich.
- Bereits freigestellte PNGs werden unverändert übernommen.

**Freistellen & Bearbeiten**
- Die automatische Erkennung läuft beim Öffnen sofort. Danach kannst du mit Toleranz/Weichheit, Radieren, Zurückholen oder Umranden nacharbeiten.
- Modi: **Auto** (lokale Hautschätzung), **Tinte** und **Farbe** (beide über angetippte Hautproben).
- Gespeicherte Tattoos lassen sich über „Meine Tattoos → Nachbearbeiten“ erneut öffnen.

**Foto-Anprobe**
- Körperfoto aufnehmen oder wählen, Tattoo verschieben, skalieren und drehen. Mit Hauteffekt (Multiplizieren). Das Ergebnis wird als JPG gespeichert.

**AR-Live-Ansicht**
- Kamerabild und Tattoo sind sofort sichtbar. Das Tattoo schwebt zunächst gestrichelt umrandet in der Mitte.
- **Antippen** der gewünschten Körperstelle verankert das Tattoo dort. Ab dann verfolgt die App diese Hautstelle im Bild (OpenCV: Optical Flow + Homographie). Das Tattoo bleibt an der Stelle, auch wenn sich die Kamera bewegt, und passt sich der Perspektive an. Eine Ganzkörperaufnahme ist nicht nötig, ein Unterarm im Bild genügt.
- **Wölbung**: legt das Tattoo um die Rundung von Arm oder Bein.
- 1 Finger verschiebt, 2 Finger drehen und skalieren, auch im verankerten Zustand.
- **↺ Lösen** gibt das Tattoo frei, dann kannst du neu antippen.
- **⏸ Standbild** friert das Bild ein, dort kannst du weiter justieren. **Als JPG speichern** sichert das Bild mit Hauteffekt.
- Tipps fürs Tracking: gutes Licht, Kamera ruhig und nicht zu schnell bewegen. Etwas Struktur im Bereich (Hautfalten, Armkante, Muttermale) hilft.

## APK erzeugen – Variante A: GitHub (ohne Installation am PC)

1. Kostenloses Konto auf https://github.com anlegen.
2. Oben rechts **+ → New repository**, Name z. B. `TattooTryOn`, auf **Private** stellen und **Create repository** wählen.
3. Auf der Repo-Seite **uploading an existing file** anklicken. Dann den **Inhalt** des entpackten Ordners `TattooTryOn` hineinziehen (alle Dateien und Ordner, inklusive `.github`) und auf **Commit changes** klicken.
   - Falls der Ordner `.github` unsichtbar ist (macOS: `Cmd + Shift + .` blendet ihn ein), gibt es eine Alternative: *Add file → Create new file*, als Name `.github/workflows/build-apk.yml` eintippen und den Inhalt der Datei hineinkopieren.
4. Im Tab **Actions** läuft jetzt „APK bauen“ (ca. 5–8 Minuten). Falls GitHub fragt, die Workflows aktivieren.
5. Ist der Lauf grün, öffnest du ihn und lädst unten unter **Artifacts** die Datei **TattooTryOn-APK** herunter (ZIP, darin `app-debug.apk`).
6. Die APK aufs Handy kopieren und öffnen. Android fragt einmal, ob Installationen aus dieser Quelle erlaubt sind: zulassen.

## APK erzeugen – Variante B: Android Studio

1. Android Studio installieren (https://developer.android.com/studio).
2. **Open** wählen und den Ordner `TattooTryOn` öffnen. Gradle lädt dann alles automatisch.
3. Über **Build → Build App Bundle(s) / APK(s) → Build APK(s)** bauen. Die APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`.
   Alternativ das Handy per USB verbinden (Entwickleroptionen + USB-Debugging) und auf ▶ klicken.

## Technik

- Kotlin, Android 10+ (minSdk 29), keine Internetverbindung nötig.
- CameraX 1.4 (Analyse-Transformation Puffer → Vorschau über `COORDINATE_SYSTEM_VIEW_REFERENCED`).
- OpenCV 4.10 (Lucas-Kanade Optical Flow, RANSAC-Homographie).
- Freistellen: Lasso-Maske, Hautfarb-Proben (Tinten-Verhältnis bzw. Farbabstand) mit weicher Kante und Farbdekontamination an halbtransparenten Pixeln sowie Pinsel. Bis zu 10 Rückgängig-Schritte.
- Projektstruktur: `app/src/main/java/at/tattoo/tryon/`
  - `ExtractActivity`, `ExtractView`, `Extractor`: Freistellen
  - `TryOnActivity`, `TryOnView`: Foto-Anprobe
  - `ArActivity`, `ArOverlayView`, `TattooTracker`: AR-Live-Ansicht
  - `Common.kt`: Laden/Speichern, Tattoo-Bibliothek, Auswahl-Dialog

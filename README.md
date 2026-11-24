# Tattoo Try-On (Android)

Mit der App kannst du ein Tattoo aus einem Foto freistellen (z. B. aus einem Pinterest-Bild). Das Ergebnis lässt sich auf ein eigenes Körperfoto legen oder live per Kamera (AR mit Körpererkennung) am Körper ansehen.

## Funktionen

**1 · Tattoo freistellen**
- Bild aus der Galerie wählen oder in Pinterest/Galerie auf *Teilen → Tattoo freistellen* tippen.
- **Umranden:** Fahre einmal mit dem Finger um das Tattoo. Alles außerhalb wird entfernt, und die Hautfarbe an der Linie wird automatisch erkannt.
- **Haut antippen:** Weitere Hautstellen antippen (hell und schattig), damit die Haut verschwindet.
- **Toleranz / Weichheit:** Damit stellst du ein, wie viel Haut entfernt wird und wie weich die Kanten werden.
- **Modus Tinte** eignet sich für Black- & Grey-Arbeiten, **Modus Farbe** für bunte Tattoos oder Motive auf weißem Papier.
- **Radieren / Zurückholen:** Pinsel zum Nacharbeiten. Mit zwei Fingern kannst du zoomen und verschieben.
- **Speichern:** Das Tattoo wird als transparentes PNG in „Meine Tattoos“ abgelegt und lässt sich auch in die Galerie exportieren.

**2 · Foto-Anprobe**
- Körperfoto mit der Kamera aufnehmen oder aus der Galerie wählen.
- Tattoo mit einem Finger verschieben, mit zwei Fingern skalieren und drehen.
- Deckkraft, Spiegeln sowie **Hauteffekt** (Multiplizieren-Modus: Hautstruktur und Licht scheinen durch).
- Das fertige Bild wird unter *Bilder/TattooTryOn* gespeichert.

**3 · AR-Live-Ansicht**
- Kamerabild mit Körpererkennung (Google ML Kit Pose Detection, läuft komplett offline auf dem Gerät).
- Du wählst das Körperteil: Unter-/Oberarm, Ober-/Unterschenkel, Brust/oberer Rücken oder Bauch/unterer Rücken, jeweils links/rechts. Das Tattoo bewegt sich mit, dreht sich und skaliert mit dem Abstand.
- Die Lage kannst du per Finger relativ zum Körperteil feinjustieren. Das bleibt beim Bewegen erhalten.
- Im Modus **Frei platzieren** gibt es kein Tracking, z. B. für Hand, Hals oder Fuß.
- Mit **Foto** speicherst du einen Schnappschuss mit Hauteffekt in der Galerie.

> Hinweis zur AR: Die Körpererkennung braucht etwas Kontext. Für einen Unterarm sollten Ellbogen und Handgelenk gut sichtbar sein, am besten auch die Schulter. Am einfachsten funktioniert es vor einem Spiegel, wenn eine zweite Person filmt oder mit der Frontkamera. Die Platzierung ist eine flache 2D-Projektion: Das Tattoo folgt Position, Drehung und Größe, wickelt sich aber nicht um die Rundung des Arms.

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
- CameraX 1.4 + `camera-mlkit-vision` (Koordinaten werden automatisch auf die Vorschau umgerechnet).
- ML Kit Pose Detection (gebündeltes Modell, 33 Körperpunkte).
- Freistellen: Lasso-Maske, Hautfarb-Proben (Tinten-Verhältnis bzw. Farbabstand) mit weicher Kante und Farbdekontamination an halbtransparenten Pixeln sowie Pinsel. Bis zu 10 Rückgängig-Schritte.
- Projektstruktur: `app/src/main/java/at/tattoo/tryon/`
  - `ExtractActivity`, `ExtractView`, `Extractor`: Freistellen
  - `TryOnActivity`, `TryOnView`: Foto-Anprobe
  - `ArActivity`, `ArOverlayView`: AR-Live-Ansicht
  - `Common.kt`: Laden/Speichern, Tattoo-Bibliothek, Auswahl-Dialog

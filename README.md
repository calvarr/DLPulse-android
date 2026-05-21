# DLPulse (Android)

## Legal notice

**DLPulse** is open-source software for educational purposes, technical research, and personal media library management. It wraps yt-dlp/ffmpeg; the author does not host copyrighted media. You must comply with copyright laws and platform terms of service; personal offline use only; provided as-is without warranty. Not affiliated with YouTube, SoundCloud, or Google LLC. Full text: [LEGAL.md](../LEGAL.md).

---

**DLPulse** is an Android app for **study and personal offline libraries** — it runs **yt-dlp** and **ffmpeg on your device** (no custom server). Paste a supported public page URL, inspect formats, save locally for your own use, search when extractors allow, **play in the built-in player** (ExoPlayer), browse files, and **cast to Chromecast / Google Cast** when supported.

**Package ID (unchanged for update compatibility):** `ro.yt.downloader`  
**Display name:** DLPulse

## Build (command line)

1. Install [Android SDK](https://developer.android.com/studio) and create `local.properties` in this folder with:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
2. Use **Java 17** for Gradle (AGP 8.x). On JDK 21+ only hosts, set e.g.  
   `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
3. Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
4. Release APK (signed with the **standard debug keystore** for sideload / GitHub releases):  
   `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`  
   For Play Store, replace signing in `app/build.gradle.kts` with your own keystore.

**Update checks:** `versionName` in `app/build.gradle.kts` should match the numeric part of the GitHub release tag (e.g. tag `v1.0.2` → `versionName = "1.0.2"`) so the in-app “new version” logic compares correctly.

## GitHub Releases

Prebuilt APKs are on [Releases](https://github.com/calvarr/DLPulse-android/releases).

### Automated builds (Actions)

When you **push a version tag** whose name starts with `v` (for example `v1.0.1`), [GitHub Actions](https://github.com/calvarr/DLPulse-android/actions) runs `./gradlew assembleRelease`, signs the APK with the standard debug keystore (same as local sideload builds), and **creates a GitHub Release** attaching `DLPulse-<tag>.apk`.

```bash
git tag -a v1.0.1 -m "Release 1.0.1"
git push origin v1.0.1
```

**If a release exists but has no APK:** an older workflow run likely failed before the upload step (for example missing Android SDK platform packages). After the workflow fix on `main`, either **push a new tag** (e.g. `v1.0.2`) or delete the remote tag and tag again, then `git push origin v1.0.1 --force` (only if you are sure no one depends on that tag).

You can also run the workflow manually from the **Actions** tab (**workflow_dispatch**); the APK is uploaded as a workflow **artifact** (no GitHub Release unless the run is on a tag).

Install release APKs by sideloading (unknown sources / “Install unknown apps” for your browser or file manager).

---

## DLPulse — aplicație Android locală

**Numele afișat în lansator / setări:** DLPulse (fost „YT Downloader” în versiuni vechi).  
**ID pachet (neschimbat, compatibilitate update):** `ro.yt.downloader`.

Această variantă Android folosește `yt-dlp` direct în aplicație (fără backend pe VM).

## Ce face aplicatia

- primeste URL public suportat de yt-dlp (pentru studiu / arhiva personala)
- permite alegerea formatului (video/audio) via yt-dlp/ffmpeg
- salveaza local pe telefon in folderul aplicatiei:
  `Android/data/ro.yt.downloader/files/downloads`

## Cerinte

- Android Studio (recomandat pentru build)
- conexiune internet pe telefon

## Build APK

### Cu Android Studio

1. Deschide Android Studio -> File -> Open -> selecteaza folderul proiectului.
2. Asteapta Gradle Sync (prima rulare poate dura).
3. Build -> Build Bundle(s) / APK(s) -> Build APK(s).
4. APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

### Din terminal (Manjaro / Linux)

1. Instaleaza Android SDK si seteaza `sdk.dir` in `local.properties`.
2. Foloseste **Java 17** pentru Gradle (AGP 8.x):
   - `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
3. Ruleaza: `./gradlew assembleDebug` sau `./gradlew assembleRelease`.

## Testare pe Manjaro

- **Emulator Android (AVD)** din Android Studio: instalezi APK-ul pe emulator si testezi acolo (recomandat).
- **Telefon fizic** cu USB debugging: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Nu merge ca aplicatie Linux nativa**: APK-ul este doar pentru Android.

## Utilizare

1. Introdu un URL public suportat de yt-dlp (ex. pagina media).
2. Apasa `Verifica URL`.
3. Alege formatul.
4. Apasa `Descarca local` (doar pentru uz personal / offline).

## Observatii

- Respecta legile privind drepturile de autor si termenii site-urilor folosite.
- Pe unele surse pot exista blocari anti-bot in functie de retea/dispozitiv.
- **Prima deschidere** poate dura: aplicatia **actualizeaza binarele yt-dlp** (necesita **internet**).
- Daca vezi eroare la init, reinstaleaza ultimul APK si verifica spatiu liber pe telefon.

## Daca vezi „Init yt-dlp/ffmpeg esuat”

- Instaleaza APK-ul generat dupa modificarile cu `App`, `extractNativeLibs` si `abiFilters`.
- Deschide aplicatia cu date/Wi-Fi pornite (prima rulare descarca componentele).
- Daca mesajul persista, trimite captura; pe unele ROM-uri trebuie permisiuni suplimentare pentru fisiere.

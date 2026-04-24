# DLPulse (Android)

**DLPulse** is an Android app that runs **yt-dlp on your device**—no custom server. Paste a YouTube URL, pick video or audio formats, and download to app storage; search videos, **play streams in the built-in player** (ExoPlayer), browse your files, and **cast to Chromecast / Google Cast** when supported.

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

## GitHub Releases

Prebuilt APKs are attached to [Releases](https://github.com/calvarr/DLPulse-android/releases). Install by sideloading the APK (unknown sources / “Install unknown apps” for your browser or file manager).

---

## DLPulse — aplicație Android locală

**Numele afișat în lansator / setări:** DLPulse (fost „YT Downloader” în versiuni vechi).  
**ID pachet (neschimbat, compatibilitate update):** `ro.yt.downloader`.

Această variantă Android folosește `yt-dlp` direct în aplicație (fără backend pe VM).

## Ce face aplicatia

- primeste URL YouTube complet
- permite alegerea formatului (video/audio)
- descarca local pe telefon in folderul aplicatiei:
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

1. Introdu URL complet YouTube (`https://www.youtube.com/watch?v=...`).
2. Apasa `Verifica URL`.
3. Alege formatul.
4. Apasa `Descarca local`.

## Observatii

- Pentru anumite clipuri YouTube pot exista blocari anti-bot in functie de retea/dispozitiv.
- **Prima deschidere** poate dura: aplicatia **actualizeaza binarele yt-dlp** (necesita **internet**).
- Daca vezi eroare la init, reinstaleaza ultimul APK si verifica spatiu liber pe telefon.

## Daca vezi „Init yt-dlp/ffmpeg esuat”

- Instaleaza APK-ul generat dupa modificarile cu `App`, `extractNativeLibs` si `abiFilters`.
- Deschide aplicatia cu date/Wi-Fi pornite (prima rulare descarca componentele).
- Daca mesajul persista, trimite captura; pe unele ROM-uri trebuie permisiuni suplimentare pentru fisiere.

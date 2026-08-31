# Sideload signing keystore

`dlpulse-sideload.jks` is a **stable** key used only for GitHub sideload APKs so
users can update over the previous install without uninstalling.

This is **not** a Play Store upload key. Passwords are intentionally in
`app/build.gradle.kts` for CI/local release builds.

# WAVE Android 2.4 - APK bauen ohne Android Studio

Dies ist WAVE Android mit **API 36** und einem vorbereiteten GitHub-Actions-Build.

**Anleitung:** [`OHNE_ANDROID_STUDIO_STARTEN.txt`](OHNE_ANDROID_STUDIO_STARTEN.txt)

Nach dem Hochladen in ein GitHub-Repository baut der Workflow
`.github/workflows/wave-android-apk.yml` eine installierbare **Debug-APK**
und stellt diese unter **Actions → Artifacts → WAVE-Android-APK** bereit.

Hinweis: Das Projekt wurde hier nicht vollstaendig als APK kompiliert und
ist nicht auf einem Android-Handy getestet worden. Hintergrundwiedergabe,
Audioeffekte und Dateifunktionen muessen nach einem erfolgreichen Build
praktisch getestet werden.

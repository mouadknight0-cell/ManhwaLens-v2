# Changelog

All notable changes to ManhwaLens are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0] - 2026-11-19

### Added
- Native Android app (Kotlin + Jetpack Compose), package id `com.manhwalens.app`.
- Screen capture via MediaProjection with a held capture session (foreground service, mediaProjection type).
- Floating draggable lens bubble rendered over other apps (SYSTEM_ALERT_WINDOW, specialUse foreground service) with a bottom translation panel.
- On-device OCR with ML Kit for Korean, Japanese, Chinese and Latin scripts; lines merged into speech-bubble regions.
- On-device translation to 16 targets (Arabic default) via ML Kit Translate; models download once, then offline.
- Tap bubble → auto-capture → OCR → translate pipeline with live working-state.
- Translation history stored on-device (JSON index + thumbnails), with delete/clear-all.
- Privacy-first design: no server round-trips; captures never leave the phone.
- Arabic and English UI with RTL support.
- Unit tests for the recognition/translation pipeline (JVM).
- GitHub Actions: CI (test + debug APK) and tag-triggered release workflow publishing signed APKs.

<div align="center">

<img src="aura_logo.png" alt="Aura Logo" width="128" height="128" style="border-radius: 28px;" />

# Aura

**Next-generation Android music client powered by YouTube Music, synchronized lyrics, universal scrobbling, and hardware-accelerated Liquid Glass.**

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2010+-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=23272e" alt="Platform" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white&labelColor=23272e" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Style-Material%203%20Expressive-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white&labelColor=23272e" alt="Material 3" />
  <img src="https://img.shields.io/badge/License-GPL%20v3.0-blue?style=for-the-badge&labelColor=23272e" alt="License" />
</p>

</div>

---

<div align="center">
  <img src="Screenshot/screenshot_1.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Feed" />
  <img src="Screenshot/screenshot_2.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Player" />
  <img src="Screenshot/screenshot_3.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Lyrics" />
  <br/>
  <br/>
  <img src="Screenshot/screenshot_4.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Generator" />
  <img src="Screenshot/screenshot_5.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Stats" />
  <img src="Screenshot/screenshot_6.png" width="31%" style="border-radius: 14px; margin: 4px;" alt="Settings" />
</div>

---

## Recent Updates & Enhancements

Aura includes core visual and architectural improvements over the original base:

* **Complete App Rebranding**: Renamed to **Aura** across all Gradle builds, manifest declarations, player notifications, and fourteen localized language packages.
* **Frosted Glass Icon**: Fresh adaptive icon suite spanning all density mipmaps (`mdpi` through `xxxhdpi`) featuring a centered translucent frosted aura ribbon glyph over an obsidian slate squircle.
* **Refined Backdrop Blur Pipeline**: Clean hardware-accelerated Liquid Glass rendering using RenderEffect shaders and Kyant Backdrop.

---

## <img src="assets/icons/layers.svg" width="20" height="20" align="center" /> Key Features

| Icon | Feature | Highlight |
|:---:|:---|:---|
| <img src="assets/icons/play-circle.svg" width="20" height="20" /> | **YouTube Music Client** | Stream tracks, albums, artists, and public playlists from YouTube Music with zero ads and background playback. |
| <img src="assets/icons/wand-2.svg" width="20" height="20" /> | **Smart Playlist Generator** | Algorithmic taste mixes and mood radios generated from your listening history, seed artists, loved tracks, and top genres. |
| <img src="assets/icons/radio.svg" width="20" height="20" /> | **Universal Last.fm Scrobbler** | Built-in media scrobbler tracking listening activity across YouTube Music, Spotify, Apple Music, and local players with zero battery drain. |
| <img src="assets/icons/mic.svg" width="20" height="20" /> | **Real-Time Synced Lyrics** | Millisecond-accurate animated karaoke lyrics powered by LRCLIB with 8 customizable fluid physics motions. |
| <img src="assets/icons/arrow-down-to-line.svg" width="20" height="20" /> | **Offline Downloader** | One-tap downloads saved directly to local storage ( `Music/Aura` ), fully tagged with high-res cover art and synchronized `.lrc` lyrics. |
| <img src="assets/icons/compass.svg" width="20" height="20" /> | **Discovery Feed & Genre DNA** | Personalized recommendation radar with deep genre breakdowns, weekly listening recaps, and instant "Start Mix" radios. |
| <img src="assets/icons/share-2.svg" width="20" height="20" /> | **Cross-Platform Playlist Import** | Instantly import public playlists from Spotify and Apple Music directly into your Aura library. |
| <img src="assets/icons/users.svg" width="20" height="20" /> | **Social Feed & Friends** | Follow friends' listening activity via Last.fm, browse their recent scrobbles, and explore their top tracks. |
| <img src="assets/icons/palette.svg" width="20" height="20" /> | **Material 3 Expressive** | Dynamic wallpaper theming, custom HSL color palette engine, album art color extraction, fluid card animations, and tactile haptics. |

---

## <img src="assets/icons/cpu.svg" width="20" height="20" align="center" /> Tech Stack & Architecture

* **Language**: 100% Kotlin
* **UI Toolkit**: Jetpack Compose with Material 3 Expressive
* **Playback Engine**: AndroidX Media3 ExoPlayer with lockscreen controls and Android Auto integration
* **Local Storage**: Room Database with Jetpack DataStore preferences
* **Dependency Injection**: Dagger Hilt
* **Graphics Pipeline**: Kyant Backdrop with hardware RenderEffect shaders
* **Audio Pipeline**: AutoEQ headphone correction filters and USB DAC bit-perfect output handshake

---

## <img src="assets/icons/terminal.svg" width="20" height="20" align="center" /> Building from Source

Clone the repository and compile the release APK with Gradle:

```bash
git clone https://github.com/aniketdubey1204-code/aura.git
cd aura
./gradlew assembleRelease
```

Compiled APKs will be generated in `app/build/outputs/apk/release/`.

---

## <img src="assets/icons/shield-alert.svg" width="20" height="20" align="center" /> Disclaimer

Aura is an open-source educational and research project built to demonstrate Android media architecture with Jetpack Compose and AndroidX Media3. 

* Aura is an independent software project and has no affiliation with Google LLC, YouTube, YouTube Music, Spotify, Apple Inc., or Last.fm.
* Aura does not host, store, or distribute copyrighted media files. All streams and metadata are resolved through public endpoints. All trademarks belong to their respective owners.

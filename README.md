# inu-legacy

[![Latest Release](https://img.shields.io/github/v/release/ZG089/inu-legacy?label=release&color=blue)](https://github.com/ZG089/inu-legacy/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/ZG089/inu-legacy/legacy.yml?branch=main&label=build)](https://github.com/ZG089/inu-legacy/actions/workflows/legacy.yml)
[![Target ABI](https://img.shields.io/badge/ABI-armeabi--v7a-orange)](#downloads)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

A 32-bit ARM (`armeabi-v7a`) port of [teidesu/inugram](https://github.com/teidesu/inugram)

Upstream Inugram does not provide 32-bit ARM support, targeting `arm64-v8a` exclusively. `inu-legacy` resatores full 32-bit compatibility for older hardware and legacy devices while maintaining automated synchronization with upstream releases.

---

## Downloads

Releases are published automatically on every upstream release tag:

| Variant | Artifact | Description |
| :--- | :--- | :--- |
| **Full** | `inugram-legacy-<version>-<sha>-<run>-armeabi-v7a.apk` | Includes the QuickJS plugin engine and LSPlant ART method hooking. |
| **Pluginless** | `inugram-legacy-<version>-<sha>-<run>-pluginless-armeabi-v7a.apk` | Lightweight build without native plugin/hooking runtimes. Lower memory footprint. |

Download signed APKs from [GitHub Releases](https://github.com/ZG089/inu-legacy/releases).

---

## Features

### 32-Bit ARM Port
* **Full `armeabi-v7a` support:** Runs on 32-bit ARM Android devices (API level 23+).
* **Dual variants:** Available in both **Full** (plugin engine + ART hooking) and **Pluginless** variants.
* **Class-aware ELF parsing:** Native 32-bit/64-bit ELF parser (`elf.rs`) for ART symbol and string resolution from `/system/lib/libart.so`.
* **32-bit NaN-boxed QuickJS:** QuickJS runtime configured with 32-bit pointer tagging for script plugins.
* **Zero-conflict patch layer:** Architecture overrides are handled dynamically via Gradle (`legacy/armv7.init.gradle`) without modifying upstream patches or patch series.

### Declutter & Debloat
* **Story removal:** Option to hide Telegram stories completely from the dialogs list and user profiles.
* **AdGuard tracking filter:** Automatic stripping of tracking tokens from shared URLs.
* **Annoyance toggles:** Hide premium reaction upsells, channel recommendation sheets, trending stickers, and bot menu buttons.
* **Performance tuning:** Disable animated avatars, glass glare effects, and adjust animation speed multipliers.

### Interface & Theming
* **Monet (Material You):** Dynamic color palette support with light, dark, and true AMOLED modes.
* **Classic UI mode:** Toggleable option to revert post-v12.6 floating "liquid glass" surfaces to standard flat headers and panels.
* **Custom font stacks:** Manage font families for the whole application with custom fallback definitions, plus dedicated monospace fonts for code blocks.
* **Material 3 elements:** M3 switches, sliders, FABs, and predictive back animations.
* **Icon packs:** Support for `.icons` packs (SVG/PNG/Lottie) including bundled Solar icons.

### Chat & Media QoL
* **Double-tap actions:** Configurable shortcuts on messages (reply, copy, forward, edit, save).
* **Precise timestamps:** Show seconds in message timestamps.
* **Persistent reply state:** Retains message reply context across peer navigation.
* **Unrestricted media:** Option to view and save timer-restricted media without screenshot blocking.
* **Video & GIF controls:** Original video quality dispatch and seekable GIF timeline controls.

### Extensibility & Plugins (Full Variant)
* **QuickJS sandbox:** Isolated TypeScript/JavaScript runtime for user plugins.
* **Runtime ART hooking:** LSPlant + ShadowHook bridge for client behavior modifications without APK repacking.

---

## Architecture & Upstream Sync

`inu-legacy` maintains clean separation between upstream Inugram code and fork-specific build logic:

* **No stock patch modification:** All stock patches in `patches/` and `series` remain byte-identical to upstream.
* **Dynamic Gradle initialization:** [`legacy/armv7.init.gradle`](legacy/armv7.init.gradle) intercepts AGP's DSL at build time, enforcing `abiFilters ['armeabi-v7a']` and parameterizing `cargo-ndk` flags for `buildInuNative`.
* **Automated 6-hour sync workflow:** [`.github/workflows/sync.yml`](.github/workflows/sync.yml) monitors `teidesu/inugram`:
  * Detects new upstream commits, merges them cleanly into `main`, and triggers CI builds.
  * Detects newly published upstream release tags and builds, signs, and publishes matching legacy releases.
* **Merge protection:** `.gitattributes` and the sync workflow preserve fork-specific configurations during upstream merges.

---

## Building Locally

### Prerequisites
* Linux or macOS
* Node.js 20+ and `pnpm`
* Android SDK (API 36, NDK 27.2 & 29.0, CMake 3.31.6)
* JDK 21 (Temurin recommended)
* Rust toolchain with target `armv7-linux-androideabi`

### Build Pluginless Variant
```sh
pnpm install
pnpm run setup -- --pluginless
(cd worktree-pluginless/TMessagesProj/jni/gram-wallet && rustup target add armv7-linux-androideabi)
./gradlew -p worktree-pluginless -I "$PWD/legacy/armv7.init.gradle" :TMessagesProj_App:assembleRelease
```

### Build Full Variant (with Plugins)
```sh
pnpm install
pnpm run setup
(cd worktree/TMessagesProj/jni/gram-wallet && rustup target add armv7-linux-androideabi)
(cd src/native && rustup target add armv7-linux-androideabi)
./gradlew -p worktree -I "$PWD/legacy/armv7.init.gradle" :TMessagesProj_App:assembleRelease
```

Built APKs are output to:
`worktree*/TMessagesProj_App/build/outputs/apk/release/`

---

## Acknowledgements

* [teidesu/inugram](https://github.com/teidesu/inugram) — Upstream Inugram patchset
* [Telegram Android](https://github.com/DrKLO/Telegram) — Base application
* [NagramX](https://github.com/risin42/NagramX) & [Nekogram](https://github.com/Nekogram/Nekogram) — Ported features and inspirations
* [LSPlant](https://github.com/LSPosed/LSPlant) & [ShadowHook](https://github.com/bytedance/shadowhook) — Native ART hooking runtimes

---

## License

This repository is licensed under the [MIT License](LICENSE).

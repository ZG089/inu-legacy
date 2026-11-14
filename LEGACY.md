# inu-legacy

A fork of [teidesu/inugram](https://github.com/teidesu/inugram) that builds **only** for 32-bit
devices (`armeabi-v7a`), for both the **full (plugin engine)** and **pluginless** variants,
with automated scheduled syncing from upstream and GitHub Releases.

Everything fork-specific lives in files upstream does not have, so syncing from upstream never
conflicts:

| File | Purpose |
| --- | --- |
| `.github/workflows/legacy.yml` | Builds and tests both variants on CI, signs and publishes releases |
| `.github/workflows/sync.yml` | Automated 6-hour upstream sync & new release detector |
| `legacy/armv7.init.gradle` | Forces `armeabi-v7a` in all Android modules & configures `buildInuNative` |
| `LEGACY.md` | This page |

The fork edits no stock patch, script, or upstream workflow. Upstream's patchset narrows stock's
ABIs to `arm64-v8a` in its own build files; the init script overrides that dynamically at build
time. The workflow verifies that produced APKs ship only `lib/armeabi-v7a/`.

## One-time setup

Turn off upstream's workflows. They build arm64 and need upstream's secrets. Disabling lives in
the repository settings, so syncs cannot undo it:

```sh
gh workflow disable build.yml
gh workflow disable release.yml
```

Create a signing key, with the alias `inugram`, and store it as secrets. Releases need a stable key,
because users can only update in place when the same key signs every build. Back the keystore up:
losing it means everyone reinstalls.

```sh
keytool -genkeypair -v -keystore inu-legacy.keystore -alias inugram \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 inu-legacy.keystore | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_PASSWORD
```

## Syncing upstream

- **Automated sync:** `.github/workflows/sync.yml` checks `teidesu/inugram` every 6 hours.
  - When new upstream commits are detected, it merges `upstream/main` with your GitHub author identity and pushes to `main`, triggering CI builds for both variants.
  - When a new upstream release tag is detected (e.g. `v13.2.2-56`), it triggers `legacy.yml` in release mode, which signs both APKs and publishes them directly to GitHub Releases with zero artifacts retained in Actions storage.
- **Manual sync:** Run `gh workflow run sync.yml` or merge `teidesu/inugram` into `main`.

## Building locally

### Pluginless variant:
```sh
pnpm install
pnpm run setup -- --pluginless
(cd worktree-pluginless/TMessagesProj/jni/gram-wallet && rustup target add armv7-linux-androideabi)
./gradlew -p worktree-pluginless -I "$PWD/legacy/armv7.init.gradle" :TMessagesProj_App:assembleRelease
```

### Full variant (with plugins):
```sh
pnpm install
pnpm run setup
(cd worktree/TMessagesProj/jni/gram-wallet && rustup target add armv7-linux-androideabi)
(cd src/native && rustup target add armv7-linux-androideabi)
./gradlew -p worktree -I "$PWD/legacy/armv7.init.gradle" :TMessagesProj_App:assembleRelease
```

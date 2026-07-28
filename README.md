# 🌊 TIDAL Patches for Morphe

Patches for the TIDAL Android app, built for [Morphe](https://morphe.software).

## ❓ About

**Swipe to add to queue** - a Spotify style swipe right gesture: drag any track, album, playlist
or mix row to the right and it is added to the play queue. The row follows the finger, a green
strip with a queue glyph is revealed behind it, and the row springs back on release.

It works on every screen that lists items, both the Compose screens (search, home, album,
playlist, artist, mix) and the remaining RecyclerView screens, because the gesture hooks the
shared row primitives instead of individual screens.

Under the hood the gesture triggers the row's own long press and intercepts the context menu
that would open, then runs its "Add to queue" entry directly. That way the app itself resolves
the item, the source metadata, the analytics and the confirmation toast.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.5.0](https://github.com/chukfinley/tidal-patches/releases/tag/v1.5.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 TIDAL&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.202.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Swipe to add to queue](#swipe-to-add-to-queue) | Adds a Spotify style swipe right gesture that adds the swiped item to the play queue, on every screen that lists tracks, albums, playlists or mixes. |  |

</details>

<!-- PATCHES_END -->

&nbsp;

## 📲 Usage

1. Install [Morphe Manager](https://morphe.software).
2. Open the patch sources in Morphe Manager, add a **remote** source and paste this URL:

   ```
   https://raw.githubusercontent.com/chukfinley/tidal-patches/main/patches-bundle.json
   ```

3. Get the TIDAL APK. The Play Store build is an App Bundle, so use an `.apkm` from
   [APKMirror](https://www.apkmirror.com/apk/tidal/tidal-tidal/) - Morphe merges the splits itself.
4. Patch TIDAL, enable **Swipe to add to queue**, install the result.

Morphe checks this source for updates on its own, so a new TIDAL version only needs a new patch
release here, not a new setup.

## 🧑‍💻 Development

- Work on the `dev` branch, use [semantic commits](https://www.conventionalcommits.org)
  (`feat:`, `fix:`, `chore:`).
- Build locally with `./gradlew buildAndroid`, the bundle lands in `patches/build/libs/patches-*.mpp`.
- Apply locally with the Morphe desktop tool:

  ```
  java -jar morphe-desktop.jar patch -p patches/build/libs/patches-1.0.0.mpp \
      --exclusive -e "Swipe to add to queue" -o tidal-patched.apk tidal.apkm
  ```

- `dev` builds publish pre-releases, merging `dev` into `main` (merge commit, no squash)
  publishes a stable release.

## 📄 License

GPLv3, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

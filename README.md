# Bilibili video exporter (BilibiliExporter)

> An Android tool that exports Bilibili cached videos and muxes them into standard MP4, aimed at Android 11+ (including MIUI/HyperOS) where `Android/data` is restricted.

This repository contains an Android APK subproject plus a PC script:

| Subproject | Path | Role | APK output |
|------------|------|------|------------|
| **BilibiliExporter** | `BilibiliExporter/` | Bilibili cache export + MP4 mux | `BilibiliExporter/app/build/outputs/apk/debug/` |
| bilibili_to_mp4.py | repo root | PC-side m4s → mp4 | — |

---

## Project overview

This project exports Bilibili cached videos on Redmi Note 11 5G (and other Android devices).

Android 11+ strictly limits `Android/data`; a normal file manager or USB browse cannot read it.
There are two tools:
1. **BilibiliExporter APK** (Android app): runs on the phone, reads the Bilibili download folder, and copies cache files to public storage
2. **bilibili_to_mp4.py** (PC script): muxes exported `.m4s` files into `.mp4` with FFmpeg

---

## Features

### APK (阿米B站视频导出 v1.8)

**v1.8: progress bar + keep screen on + collapsible toolbar**

- **Horizontal progress bar**: during export/mux the top shows a percent bar + text.
  Total units = series copies + video muxes (when mux is checked), refreshed on the main thread
- **Keep the device awake while exporting**:
  - `FLAG_KEEP_SCREEN_ON`: screen stays on while the app is foreground
  - `PowerManager.PARTIAL_WAKE_LOCK`: CPU stays awake even if the screen turns off, so mux can finish
  - Added `WAKE_LOCK` in the Manifest
  - Released on success or error; max hold 1 hour (leak guard)
- **Collapsible top toolbar**:
  - ▲/▼ on the title bar (shown after videos load)
  - Collapsed: status card, search, sort bar, select-all/export hide; the list fills the rest
  - Subtitle while collapsed: “Selected X · Y episodes”
  - Compact Export next to the fold button → export without expanding first

**v1.7 fix: scan missed new Bilibili cache folders (e.g. 116401868112562)**

- Root cause: `EPISODE_FOLDER_PATTERN = Regex("c_\\d{8,10}")` only matched `c_` + 8–10 digits.
  Newer cids are 13–15 digits (e.g. `c_116401868112562`), or plain numeric names — all missed.
- Fix: every scan path (Direct/SAF/Shizuku) treats **a folder that contains `entry.json`** as an episode.
  That is the real Bilibili cache signature and works across naming schemes.
- Files:
  - `MainActivity.scanDirectRecursive` / `scanSAFRecursive`
  - `UserService.listEpisodeFolders` (Shizuku remote service)
- Trade-off: slightly slower (extra `entry.json` stat per folder), more reliable.

**v1.6: list search**

- Search box at the top: “🔍 Search series or video title…”
- Filters as you type (no submit); ✕ clears
- Match (case-insensitive):
  - Series name (`seriesTitle` or `seriesFolderName`) → whole series shown
  - Video title only (`title` or `partName`) → series shown, matching videos only, auto-expanded
- Status: “Search: X series / Y episodes (all N series / M episodes)”
- While searching:
  - Select-all applies only to **currently visible** videos
  - Object identity is unchanged → changing the query does not drop selection
  - Export uses **all selected** (including hidden by the filter) so search cannot drop a selection

**v1.5: app rename + two delete options**

- Launcher name “阿米B站视频导出”; title bar matches
- Export dialog splits **two independent delete checkboxes**:
  - ☐ Delete Bilibili cache source folders (red, default off — frees phone storage)
  - ☑ Delete `c_XXX` copies in the export dir (default on — leave only MP4)
- Both only enabled when “Mux to MP4” is checked; unchecking mux unchecks both
- Deleting Bilibili sources still shows a danger confirm

**v1.4 fix: “delete” actually deletes Bilibili source cache**

- Root cause: “delete original m4s” deleted **`c_XXX` copies under the export dir** (`/sdcard/Download/BilibiliExport/...`),
  not the **Bilibili cache** (`Android/data/tv.danmaku.bili/download/.../c_XXX`, often several GB).
- Fix:
  - Checkbox text **“Delete Bilibili cache source folders”** (red), default off
  - New `deleteSourceEpisode(source)` dispatched by VideoSource type:
    - Direct → app process `deleteRecursively()` (needs `MANAGE_EXTERNAL_STORAGE`)
    - SAF → `DocumentFile.delete()`
    - Shizuku → `IUserService.deleteRecursively(episodePath)` (shell UID)
  - Confirm dialog lists the impact (“Bilibili will no longer play these downloaded videos”)
  - Failures write a reason into the export report (e.g. “app cannot unlink”, “Shizuku returned false”)
  - Error list expanded from first 5 to first 10
- `c_XXX` copies under the export dir are **kept** (including `entry.json`, danmaku, extra files)

**v1.3: grouped collapse + sort + delete bugfix**

- **Series grouping**: Bilibili layout `download/[seriesId]/c_XXX/` — first-level dirs are series.
  Each series header has a checkbox for the whole group; tap to expand/collapse.
  Expand/Collapse all above the list.
- **Sort** dropdown, 4 modes:
  - Time new→old / old→new (folder mtime)
  - Name A→Z / Z→A (series title)
  - Videos inside a series use the same rule
- **Fix “delete original video checked but nothing deleted”**:
  - Root cause: in Shizuku mode, UserService (shell UID 2000) copies to `/sdcard/Download/`.
    Even after chmod 0755/0644, the app UID may still fail to unlink (ROM sdcardfs).
  - Fix: new `IUserService.deleteRecursively(path)`.
    App tries local `File.deleteRecursively()`, falls back to Shizuku as shell, then `walkBottomUp + delete` file-by-file.
  - Delete failures go into the export report; no silent swallow.

**v1.2: built-in MP4 mux**

- `bilibili_to_mp4.py` behavior inside the APK
- Native `MediaMuxer + MediaExtractor` muxes `audio.m4s + video.m4s` → `.mp4`, **no FFmpeg**, no re-encode, ~1 s/episode
- Skips Bilibili obfuscation header (first 9 ASCII `'0'` bytes); scans for the `ftyp` box to find the MP4 start
- Export options:
  - ☑ Mux to MP4
  - ☑ Delete original m4s (save space)
  - ☑ Name series folders from video titles
- Output filename from `entry.json` `page_data.part` (episode name); illegal characters stripped

**Reality of `Android/data` access:**

| Android version | Shizuku | SAF jump | MANAGE_EXTERNAL_STORAGE | Copy workaround |
|-----------------|---------|----------|-------------------------|-----------------|
| Android 10 and below | Works | Works | Works | Works |
| Android 11/12 | Works | Works | MIUI works | Works |
| **Android 13+** | **Works fully** | **Blocked by the OS** | **Blocked on most ROMs** | **Works** |

**Four modes:**

- **Mode ① Shizuku (★ best)**
  Install Shizuku (github.com/RikkaApps/Shizuku/releases) and start its privileged service via ADB.
  This app `Shizuku.bindUserService()`-starts a remote `UserService` as shell UID (2000); binder IPC proxies file ops.
  Shell UID can **read any `/sdcard/Android/data/*`**.
  - Pros: no staging copy; any Android version
  - Cons: install Shizuku and start it once via ADB (again after reboot)

- **Mode ② Copy workaround (no ADB, reliable backup)**
  Open MIUI Files → `Android/data/tv.danmaku.bili/download` (MIUI Files has extra access),
  long-press download → copy → paste to `/sdcard/Download/`, then pick that folder in this app.
  Shortcut: “Open MIUI Files”.

- **Mode ③ SAF jump to download (Android 11/12 only)**
  `StorageManager.primaryStorageVolume.createOpenDocumentTreeIntent()` INITIAL_URI rewritten to `Android/data/tv.danmaku.bili/download`.
  Android 13+ intercepts with a privacy message.

- **Mode ④ MANAGE_EXTERNAL_STORAGE (some ROMs)**
  Classic all-files access; some MIUI builds can still reach Android/data.
  If the Intent fails, falls back to generic Settings and the app-info page.

**Shared features:**
- Scan Bilibili download dirs (recursively; episode folders historically `c_\d{8,10}`, now identified by `entry.json`)
- Parse `entry.json` for title and part name
- Single-select / select-all
- Copy selected videos with directory structure to `Download/BilibiliExport/` (public storage, USB-visible)
- Remember SAF grant URI
- Android 8.0+ (API 26+)

### Python script (`bilibili_to_mp4.py`)
- Recursively scan an export dir for Bilibili download folders
- Read `entry.json` for title and episode names
- FFmpeg mux `audio.m4s` + `video.m4s` → `.mp4`
- Rename output folders to video titles
- Multi-part series
- Missing-json error handling

---

## Tech stack and dependencies

### APK
| Dependency | Version | Role |
|------------|---------|------|
| Kotlin | 1.9.22 | Primary language |
| AGP (Android Gradle Plugin) | 8.1.4 | Build |
| Gradle | 8.2 | Build system |
| androidx.appcompat | 1.6.1 | Compat |
| material | 1.11.0 | Material Design UI |
| recyclerview | 1.3.2 | Video list |
| documentfile | 1.0.1 | SAF wrapper |
| kotlinx-coroutines-android | 1.7.3 | Async file I/O |
| lifecycle-runtime-ktx | 2.7.0 | lifecycleScope |
| dev.rikka.shizuku:api | 13.1.5 | Shizuku client API |
| dev.rikka.shizuku:provider | 13.1.5 | Shizuku ContentProvider |
| minSdk | 26 (Android 8) | Minimum |
| targetSdk | 34 (Android 14) | Target |

### Python script
- Python 3.x
- FFmpeg (installed and on PATH)

---

## How to build the APK

### Prerequisites
- Android Studio (Hedgehog 2023.1.1 or newer recommended)
- JDK 17+
- Android SDK (compileSdk 34)

### Steps
1. Open the `BilibiliExporter/` folder in Android Studio
2. Wait for Gradle sync (first time downloads deps; a proxy helps)
3. Build → Build Bundle(s)/APK(s) → Build APK(s)
4. APK: `BilibiliExporter/app/build/outputs/apk/debug/app-debug.apk`
5. Install over USB or ADB:
   ```bash
   adb install app-debug.apk
   ```

### Gradle proxy (restricted networks)
Append to `BilibiliExporter/gradle.properties` (replace with your proxy):
```
systemProp.http.proxyHost=YOUR_PROXY
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=YOUR_PROXY
systemProp.https.proxyPort=8080
```

---

## How to use the APK

### First run

1. Install and open. The app shows Android version, Shizuku status, and a recommended mode

2. **Mode ① Shizuku (pink button, best)**

   **One-time setup:**
   - Download and install Shizuku: https://github.com/RikkaApps/Shizuku/releases
   - Open the Shizuku app
   - Start via wireless debugging or computer ADB
   - **ADB (recommended):**
     - USB debugging on, phone connected
     - Run the adb command shown in the Shizuku app (similar to `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`)
     - Shizuku shows “Running”
   - **Wireless debugging (Android 11+):**
     - Enable wireless debugging in Developer options
     - In Shizuku tap Start and pair as prompted

   **In this app:**
   - Tap pink “① Shizuku”
   - Allow the permission prompt the first time
   - App scans `/sdcard/Android/data/tv.danmaku.bili/download` via Shizuku
   - No staging copy; the video list appears directly

   After reboot, start Shizuku again

3. **Mode ② Copy workaround (green):**

   - Tap “② Copy workaround”
   - “Open MIUI Files”
   - `Internal storage → Android → data → tv.danmaku.bili → download`
   - Long-press download → copy → paste to `/Download/`
   - Back in this app: “② Copy workaround” → “Pick the copied folder”
   - Picker opens `/Download/`; choose the pasted download folder

4. **Mode ③ SAF jump to download (blue): Android 11/12 only**

   - Tap “③ SAF jump to download”
   - System picker opens the download dir
   - Tap “Use this folder”

5. **Mode ④ All-files access (gray): some MIUI fallback**

   - Tap “④ All-files access”
   - Turn the switch on in Settings
   - Return; the app scans automatically

6. After the list loads:
   - Grouped by series: header shows title, episode count, last modified; tap to expand/collapse
   - Sort dropdown: time new→old / old→new / name A→Z / Z→A
   - Header check = whole series; or check one episode
   - Expand/Collapse all
   - Select-all selects every video (expanded or not)
   - “Export selected to Downloads” opens options:
     - ☑ Mux to MP4
     - ☑ Delete original m4s (clean `c_XXX` under the export dir after mux)
     - ☑ Name series folders from video titles
   - Wait for copy + mux
7. USB to a PC; files are under `Download/BilibiliExport/`

### ADB fallback (simplest and most reliable)

If every mode fails, on the PC:
```bash
adb pull /sdcard/Android/data/tv.danmaku.bili/download D:\BilibiliExport
```
Then mux with `bilibili_to_mp4.py`.

### Mux with the Python script after export
```bash
# Point base_path in bilibili_to_mp4.py at the export dir
base_path = r"D:\BilibiliExport"  # actual path
python bilibili_to_mp4.py
```

---

## File structure

```
BilibiliExporter-repo/
├── README.md                          # This file
├── bilibili_to_mp4.py                 # PC MP4 mux script
│
└── BilibiliExporter/                  # Android project root
    ├── settings.gradle                # Modules
    ├── build.gradle                   # Root Gradle (plugin versions)
    ├── gradle.properties              # Gradle properties
    ├── gradle/wrapper/
    │   └── gradle-wrapper.properties  # Gradle 8.2
    │
    └── app/
        ├── build.gradle               # Module Gradle (deps)
        ├── proguard-rules.pro         # ProGuard
        └── src/main/
            ├── AndroidManifest.xml    # Permissions, Activity
            │
            ├── aidl/com/biliexport/downloader/
            │   └── IUserService.aidl   # Shizuku remote API
            │
            ├── java/com/biliexport/downloader/
            │   ├── BiliVideo.kt        # Models (VideoSource, VideoGroup, SortMode)
            │   ├── VideoAdapter.kt     # RecyclerView grouped adapter (GroupHeader + VideoEntry)
            │   ├── Mp4Merger.kt        # MediaMuxer m4s → mp4
            │   ├── UserService.kt      # Shizuku remote service (shell UID)
            │   ├── ShizukuHelper.kt    # Shizuku status / bind / unbind
            │   └── MainActivity.kt     # UI (permissions, scan, group, sort, export)
            │
            └── res/
                ├── layout/
                │   ├── activity_main.xml         # Main layout
                │   ├── item_video.xml            # Episode row
                │   ├── item_group_header.xml    # Series header (collapse, select)
                │   └── dialog_export_options.xml # Export options
                ├── values/
                │   ├── strings.xml
                │   ├── colors.xml
                │   └── themes.xml
                ├── drawable/
                │   └── ic_launcher_foreground.xml
                └── mipmap-anydpi-v26/
                    ├── ic_launcher.xml
                    └── ic_launcher_round.xml
```

---

## Bilibili download directory layout

```
Android/data/tv.danmaku.bili/download/
└── [series folder]/
    └── c_XXXXXXXX/        ← episode dir (what this APK recognizes)
        ├── entry.json     ← title (series) and page_data.part (part name)
        ├── 80/            ← quality (80=1080p, 64=720p, 32=480p...)
        │   ├── audio.m4s  ← audio
        │   ├── video.m4s  ← video
        │   └── index.json
        └── danmaku.xml    ← optional danmaku
```

---

## Permissions

| Permission | Role | Notes |
|------------|------|-------|
| `READ_EXTERNAL_STORAGE` | Read storage on Android 10 and below | maxSdkVersion=32 |
| `WRITE_EXTERNAL_STORAGE` | Write storage on Android 9 and below | maxSdkVersion=29 |
| `MANAGE_EXTERNAL_STORAGE` | All-files on Android 11+ | On MIUI can reach Android/data |

> **Note**: Google Play policy forbids `MANAGE_EXTERNAL_STORAGE` for ordinary apps. This APK is sideloaded and is not bound by that.

---

## Known limits

- On **stock Android 11+ (non-MIUI)**, `MANAGE_EXTERNAL_STORAGE` may still not reach `Android/data`
- SAF file managers may hide or refuse `Android/data`
- If both fail, pull with ADB:
  ```bash
  adb pull /sdcard/Android/data/tv.danmaku.bili/download D:\BilibiliExport
  ```

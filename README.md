# Agent JiN

On-device Android agent (`com.ngi.agentjin`). Two GGUF models via llama.cpp, accessibility + screenshot perception, plan-then-execute, a plugin interface, password-based memory encryption, and a portable SAF workspace.

This is **phase 1**: complete core architecture + three plugins (`screen_agent`, `app_manager`, `settings`). Later plugins are new files against `Plugin` / `PluginFactory` — no core rewrite.

There is **no fake model output**. If llama.cpp is not compiled or a GGUF is missing, the UI says so.

## CI APK (GitHub Actions)

Workflow: `.github/workflows/build-apk.yml`

- Triggers: push to `main` / `arena/**`, pull requests to `main`, and **Run workflow**
- JDK 17, Gradle 8.9, Android SDK 35, NDK 27.2, CMake 3.22.1
- Compiles llama.cpp for `arm64-v8a` and uploads `AgentJiN-debug-<sha>.apk` as an Actions artifact (14 days)

Download: GitHub → **Actions** → **Build APK** → latest run → Artifacts.

## Build

Open this folder in Android Studio (Koala / Ladybug or newer).

Requirements:

- Android Studio with **NDK** and **CMake**
- JDK 17
- Network on first native build (CMake FetchContent clones [llama.cpp](https://github.com/ggml-org/llama.cpp))

```bash
./gradlew :app:assembleDebug
```

The first compile fetches llama.cpp (`master`) and builds `libagentjin_llama.so` for `arm64-v8a` and `x86_64`. That step is large and slow.

If Gradle wrapper JAR is missing:

```bash
gradle wrapper --gradle-version 8.9
```

## First run

1. Choose a workspace folder (SAF `ACTION_OPEN_DOCUMENT_TREE`). A persistable URI permission is taken.
2. If `manifest.json` already exists, restore or set a new password.
3. Set a master password/PIN. **It is never stored.** Argon2id (password + public salt) → AES-256-GCM key for `/memories/*`.
4. Models download **inside the app** (Wi-Fi only by default, HTTP Range resume, SHA-256 verify).
5. Enable **Settings → Accessibility → Agent JiN screen agent** so `screen_agent` can read/tap the UI.

### Models (Q4 GGUF)

| Role | File | SHA-256 |
| --- | --- | --- |
| Text / orchestrator (always loaded, mmap) | `LFM2.5-350M-Q4_K_M.gguf` | `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4` |
| Vision (on demand) | `LFM2.5-VL-1.6B-Q4_K_M.gguf` | `aefc3c97c9eb30d9c0dd6af4c38250f5f5106b57c8cf92de7914c7d0a9c94da2` |
| Vision projector | `mmproj-LFM2.5-VL-1.6b-Q8_0.gguf` | `2ce89e610c56f3198ece2b86cf61743a08b9307279c89125eb2412ebb908689d` |

Sources tried in order: Hugging Face → `hf-mirror.com`. If every source fails, the error shows the exact filename, size, checksum, and manual copy path (`models/` in the workspace). Valid checksummed files are not re-downloaded (restore case).

## Workspace layout (portable)

```
/manifest.json
/memories/conversations.db    # AES-256-GCM blob
/memories/notes.json          # AES-256-GCM blob
/memories/preferences.json    # AES-256-GCM blob
/plugins_config/enabled_plugins.json
/logs/
/task_history/
/models/<gguf files>          # not encrypted
```

`manifest.json` holds schema version, per-model `{name, path, sha256, downloaded_at}`, and the **public** Argon2 salts + verification hash. Model files are verified by SHA-256 before use.

OAuth tokens for future connectors go in `SecretStore` (Android Keystore / EncryptedSharedPreferences), never in the SD-card folder.

## Architecture

```
ui/          Compose screens
core/inference   llama.cpp JNI, mmap load, RAM policy
core/planning    Planner (JSON + GBNF) → TaskExecutor (plan-then-execute, re-plan, max steps)
core/plugin      Plugin, PluginFactory (ServiceLoader), PluginManager
core/safety      ConfirmationGate, PermissionGuard (logs every call), UndoManager
core/storage     SAF root, encrypted memories, session, conversations.db
core/download    Foreground service, Range, SHA-256, Wi-Fi-only
core/screen      AccessibilityService + MediaProjection fallback
plugins/         screen_agent, app_manager, settings
```

### Two models

- **Text** (LFM2.5-350M Q4): always-on orchestrator. Conversation, intent, JSON plans, tool calls.
- **Vision** (LFM2.5-VL-1.6B Q4 + mmproj): loaded when `screen_agent` needs a screenshot; unloaded after idle. On ≤3GB RAM devices it is never kept resident.

### Plan-then-execute

Multi-step work is a JSON list of steps. One plugin call at a time. On failure the current UI tree is sent back for a revised plan. Configurable max-step budget. Live checklist in the chat UI. If a step needs unknown data, the agent **asks** — it does not invent it.

### Plugins (phase 1 only)

| Name | What it actually does |
| --- | --- |
| `screen_agent` | Accessibility tree dump, click/type/scroll/back/home/recents/tap. Vision+screenshot only when the tree is insufficient. |
| `app_manager` | List launcher apps, open by name/package, HOME to background. Cannot force-stop other apps (Android restriction, reported honestly). |
| `settings` | Brightness via `WRITE_SETTINGS`. Wi-Fi/Bluetooth: real APIs on old Android; on 10+/13+ opens the system panel because apps cannot flip those radios. |

The plugins screen includes a **“more plugins coming soon”** empty state that lists **nothing fake**.

### Adding a plugin later

1. New class implementing `com.ngi.agentjin.core.plugin.Plugin`.
2. New `PluginFactory` with a public no-arg constructor.
3. Append the factory FQCN to `app/src/main/resources/META-INF/services/com.ngi.agentjin.core.plugin.PluginFactory`.

Do not add phase-2 plugins to the phase-1 bootstrap list in `PluginManager`. Disabled plugins are not placed in the active map, so their models/resources are never loaded.

### Safety (core, not a plugin)

- Confirmation dialog when `Plugin.isSensitive` is true.
- Undo stack where the plugin implements `undo`.
- `PermissionGuard` logs every plugin invocation to `/task_history`.
- Failed password attempts: 5 free, then 30s / 1m / 5m / 15m / 1h backoff.
- Optional biometric unlock wraps the **already derived** key in Android Keystore on that device only.

### RAM (3GB phones)

`ActivityManager.getMemoryInfo` → constrained mode: smaller contexts, 2 threads, vision unloaded immediately after use, mmap always on.

## Icon

Adaptive icon + density PNGs are generated from the provided flame SVG (`res/drawable/ic_launcher_foreground.xml` is the vector source).

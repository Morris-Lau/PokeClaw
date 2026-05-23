# Codex Local Session

## 2026-05-24 — Simplified Chinese Support

- Branch: `codex/chinese-language-support`
- Goal: implement end-to-end Simplified Chinese support plus an in-app language setting.
- Scope: Android app language setting, primary chat/settings/model/workflow UI strings, Chinese deterministic task routing, Chinese direct device-data guards, QA checklist, backlog, README changelog.
- Constraints: do not touch untracked `AGENTS.md`; keep external automation intent contract unchanged; LAN config web page keeps browser-language detection.
- QA added first in `QA_CHECKLIST.md`: `H10`, `Q9`, `DD8-DD13`, `M51-M55`.
- Current status: implementation complete and installed on Pixel 9 Pro.
- Build: `./gradlew assembleDebug` passed; latest debug APK installed from `app/build/outputs/apk/debug/PokeClaw_v0.6.12_20260524_024912.apk`.
- QA result: H10 language setting passed via UI switches `简体中文 -> English -> System default -> 简体中文`, including relaunch persistence for Simplified Chinese. Q9 chat/settings/models shell checks passed for primary Chinese UI. DD8-DD13 routed to deterministic tools; DD13 returned the expected Chinese accessibility-missing error. M51-M55 routed through Tier 1 direct intent/tool paths; M54 opened Chrome via PackageManager fallback. Crash buffer was empty after final verification.
- QA gap: full A-K release sweep was not run in this session; focused affected-section E2E was run instead.

## 2026-05-24 — Model Download Reliability

- Branch: `codex/model-download-reliability`
- Goal: refactor local model downloads from a synchronous activity-bound path into a WorkManager-backed, resumable, checksummed download subsystem.
- Scope: QA checklist `U1-U8`, WorkManager dependency, exact built-in model metadata, managed download sidecars, Range resume/restart handling, foreground download notification, Settings/Chat download observers, debug report diagnostics.
- Constraints: keep custom model URLs/import out of scope; do not reuse task/monitor `ForegroundService` for downloads; preserve linked-file availability for existing local model paths.
- Current status: implementation complete; final build gate passed and latest debug APK installed on Pixel 9 Pro.
- QA added first in `QA_CHECKLIST.md`: `U1-U8` Local Model Download Reliability, plus Debug Changelog entries for unit/component, build, and ADB smoke coverage.
- Verification: `./gradlew testDebugUnitTest --tests 'io.agents.pokeclaw.agent.llm.*'` passed; final `./gradlew testDebugUnitTest assembleDebug` passed; `PokeClaw_v0.6.12_20260524_031105.apk` installed on Pixel 9 Pro `4B061FDAP00257`.
- ADB smoke: launched PokeClaw, opened Settings -> Models, verified the current model/defaults, Gemma E2B/E4B rows, and `下载` actions render; current-process logcat showed no crash after opening Models.
- QA gap: full multi-GB production download completion, network-loss resume through completion, process-kill resume through completion, corrupt-file device injection, and low-storage simulation were not run in this session; deterministic download paths are covered by MockWebServer component tests.

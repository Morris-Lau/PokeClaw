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

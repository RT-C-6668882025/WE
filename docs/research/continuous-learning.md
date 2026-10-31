# Continuous learning paper

## Implemented in WE

A workspace request creates a `LearningTurn`. The Android runtime chooses tools/Skills and must write a unique `/memory/pages/turn-<UUID>.md`. WE verifies a completed runtime result and an actual nonempty bounded file, renders a new PDF, then opens it automatically. An unsaved chat answer is not a completed page.

PaperActivity captures a submission's vectors, document/page/revision/region and asks the existing subscription vision adapter to transcribe handwriting and diagram relationships. On an unchanged page this feedback starts a new LearningTurn with the associated source material, without navigating back to the workspace or pressing Execute. The agent chooses correction, explanation, revision or the next exercise. Source material is explicitly named and new material has a unique path.

If the user writes or changes pages while waiting, WE does not replace the visible paper. The result remains available through “查看生成页”; the original PDF and vectors are untouched. New ink can be recognized after the current task completes. The same page can receive typed requirements. Tool configuration is collapsed by default.

The pinned runtime has a partial JSON decoder for native reads: a legal escaped slash in a path could fail with INVALID_WORKSPACE_PATH. NativeAgentHost now canonicalizes tool argument JSON at the ModelClient boundary. This preserves values while keeping the provider's original function-call items in the subscription checkpoint. The two-turn fixture uses escaped paths to cover this failure.

Each turn records request, origin, source path, creation time and prepared/running/ready/interrupted status outside the agent workspace. Ready papers can be reopened after restarting the app. Interrupted runs are not automatically replayed; this avoids repeating external MCP effects. This is activity-owned execution: closing the activity cancels the active generation; it is not a durable background worker. Recognition already submitted may finish storing its captured result.

## Verification boundary

LearningLoopContract runs the actual pinned Android agent runtime, file tools and subscription request serializer against a deterministic synthetic model. Android instrumentation uses the actual PDF renderer. The JVM contract injects a deterministic artifact writer because Robolectric 4.13 does not implement PdfDocument/PdfRenderer. It exercises two turns, source reads, new page creation, original ink preservation, ready recovery and rejection of a completed response with no saved page. It does not test a real account, vision accuracy or Android background survival. Existing instrumentation also opens the paper and persists touch ink.

This version generates text learning papers, not a rich graphical scene editor. Vision is a separate first stage whose text feeds the agent. Ambiguous recognition must lead to a clarification on the new page, not fabricated content. No Python/Node Skill execution has been added.

## Open-source candidates inspected (2026-10-31)

| Project | Useful part | Evidence / decision |
| --- | --- | --- |
| [DSH Go](https://github.com/ccch1mneyyy/dsh-Go) | Complete on-device Linux/Node/DSH lifecycle, background service, plugin UI bridge | README and DshProcess.kt confirm default PRoot launch and optional direct glibc; approximately 1 GB runtime. App is Flutter plus Kotlin; WE is Kotlin Views. Its app code is AGPL-3.0, SDK/examples MIT. Not embedded in WE. |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | MIT agent loop, filesystem Skills, MCP, SDK profiles | architecture and packages/skill + packages/mcp docs inspected. dsh-Go pins @deepseek-ai/dsh 0.2.1-rc.4. Current upstream docs are newer and cannot be assumed to describe that release. Android packaging would reuse the DSH Go approach rather than requiring an external computer. Subscription compatibility with WE's official OAuth gateway remains untested. |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | Kotlin AgentLoop, Skill references, MCP OAuth, PRoot workspace | Source inspected: ai/.../AgentLoop.kt, app/.../SkillsTools.kt, workspace/.../LocalWorkspaceBackend.kt. Agent loops through model/tool rounds, skill tool loads instructions/references, workspace executes commands inside local PRoot. AGPL-3.0 app; a better language match than DSH Go, but its Compose/Room/Koin dependencies and subscription compatibility need an isolated module spike. Not integrated. |
| [AndroidDraw](https://github.com/NicolasPauferro/AndroidDraw) | Existing Android packaging for Excalidraw | package.json confirms Capacitor 8.2.0 + Excalidraw workspace. Useful packaging reference, not proof of low-latency stylus performance or native integration; no code imported. |
| [Excalidraw](https://github.com/excalidraw/excalidraw) | MIT structured editable diagrams/scenes, JSON serialization | [scene format](https://github.com/excalidraw/excalidraw/blob/master/dev-docs/docs/codebase/json-schema.mdx), [utilities](https://docs.excalidraw.com/docs/@excalidraw/excalidraw/api/utils). Candidate for diagrams/frames that the agent can edit as objects. Requires a WebView/React surface, Android pen testing and a controlled native bridge; not integrated. |
| [Jetpack Ink](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api) / [google/ink](https://github.com/google/ink) | Native stroke authoring, rendering and low-latency pen input | Android Views integration documented. Candidate for replacing the current simple PaperInkView stroke renderer after page semantics are stable. Does not solve recognition or agent control; not integrated. |

The working integration continues to use cc.noharry:agent-runtime-android:0.3.0 (MIT), already embedded in WE and compatible with its subscription transport. Replacing it with DSH before validating the subscription adapter and runtime lifecycle would interrupt the working path. The immutable turn artifact contract can be reused by either engine.

Next engine experiment should pin DSH Go and its runtime, demonstrate a local Android start, run a file/Skill/MCP task, then adapt a single model request through WE's existing gateway without exporting OAuth credentials into the guest environment. Passing a desktop Node experiment would not establish Android compatibility.

## Verified on this change

- `testDebugUnitTest`: 94 tests, 0 failures, 0 errors. Includes the two-turn real-runtime/file-tool contract with legal escaped JSON paths and a deterministic JVM artifact writer.
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: successful.
- `tools/check-subscription-protocol.sh`: 88 checks passed with synthetic/offline fixtures.
- New Android instrumentation contract compiles but has not been executed on a device in this session. No attached Android device or emulator was available. Physical subscription vision accuracy, pen feel and automatic page switching need device acceptance.
- GitHub Actions did not expose a run for this branch during verification; the results above are from the local managed build environment. The PR stays a draft rather than claiming an Android instrumentation pass.

# WE · Write Everything

**Start writing. Return to your workspace and continue.**

WE is an Android handwriting and AI workspace application, built on [Moling](https://github.com/xiaoxi668v-prog/moling). Use handwriting, drawing or typing, and keep conversations, documents and learning pages within a task.

[Downloads](https://github.com/RT-C-6668882025/WE/releases) · [Release notes](docs/releases/) · [Issues](https://github.com/RT-C-6668882025/WE/issues) · [中文](README.md)

Current source version: **v0.4.6 Preview**. See Releases for APKs actually available on GitHub.

## The interface

- **Home:** use handwriting, drawing or typing. Drawing does not trigger AI on pen pause by default; voice comes from your keyboard.
- **Workspaces:** browse and resume previous tasks. Existing conversation archives are retained. Selecting a task restores its identity and recent conversation context.
- **Workspace documents:** view files, import PDFs, open the latest learning paper, rename or delete the workspace.
- **Settings:** configure models, subscription login, recognition, handwriting and appearance. Separate PDF and Skill/MCP workbench entries have been removed.
- **About:** WE project information, licensing and upstream credits.

Tap the bottom-left icon to open the Workspaces / Settings / About panel.

Workspaces support name search, pinned-first sorting, batch archive and recoverable Trash. Restoring a task retains its identity, materials and ink.

## Handwriting and learning papers

MCP can publish structured, pen-ready pages with question/answer regions, annotation margins, drawing areas and reference figures. Generated content and editable ink share page coordinates. See [writable page protocol](docs/writable-pages.md).


Home supports ML Kit Digital Ink recognition and the selected subscription vision model. PDF papers support handwriting, undo, pan, zoom, rectangular selection, recognition, grading and explanations. Ink is stored separately from the original PDF; submissions record the source page, revision and region.

A learning paper can automatically continue after a writing pause: recognized feedback is passed to the on-device agent, which reads the source material and saves a revised or subsequent text learning paper. If you keep writing while it works, the generated result remains available without switching away from your current page.

Orchestration, files and ink live on Android. Model inference uses your selected provider over the network. A computer, Termux or separately deployed agent engine is not required.

## Skill and MCP status

The embedded Android Agent Runtime provides file tools, Skill reading and a public HTTPS MCP client. This is **not an external MCP server**.

External MCP is available under Settings → Agent connection · MCP. A same-device native client can connect to the Android loopback endpoint; tools read workspace files and handwritten page images, write files/Skills and publish a new learning page. Writes use durable operation receipts and version checks.

WordTok-style fixed public URLs use a separately deployed HTTPS relay while Android executes tools. **No hosted WE public domain is bundled.** Deploy the dedicated relay first; do not reuse WordTok URLs or data. See [MCP setup](mcp/README.md). A cloud client cannot reach Android loopback directly.

 Current generated papers are primarily text. Editable graphical scenes, arbitrary Python/Node Skill scripts, MCP OAuth, stdio and Google Drive sync are not supported. DSH Go, RikkaHub, Excalidraw and Jetpack Ink were investigated but have not been integrated.

See [native agent implementation](docs/android-native-agent.md) and [verification boundaries](docs/research/continuous-learning.md).

## Install and configure

Requires **Android 8.0 / API 26 or newer**. Ordinary Android phones and tablets are supported, with additional direct-ink integration on compatible Huawei e-ink hardware.

1. Download a WE APK from this repository's [Releases](https://github.com/RT-C-6668882025/WE/releases).
2. Open Settings and choose ChatGPT subscription or an Anthropic Messages-compatible API provider.
3. For subscription access, finish browser authorization, refresh the account model catalog, select text and image models, and save the selection.
4. Return to Home or resume a workspace.

Subscription credentials are encrypted locally using Android Keystore. Signing out removes the local session. If the final browser redirect to `127.0.0.1` fails while authorization is still active, paste the complete failed-page URL into the app's callback recovery dialog. The callback address and authorization state are still validated. **The URL contains one-time authorization data: do not post it in Issues, chats or public pages.**

Preview APKs use debug signing. In-place updates require the same signing certificate. Preserve the existing installation and data if Android reports a signature mismatch; uninstalling deletes app data. Previews from different build environments may use different certificates.

## Build

Kotlin, Java and Android Views; `minSdk 26`, `compileSdk / targetSdk 34`. Requires **JDK 17, Android SDK Platform 34 and Build Tools 34.0.0**.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.opc.baize.agent.NativeAgentDeviceTest
```

Local validation passed the full unit test suite, lint and APK assembly, including task continuation, workspace indexing, data retention and UI entry checks. Synthetic model and callback tests do not establish real-account authorization, vision accuracy, keyboard input or physical stylus performance.

## Credits and license

Maintained by **RT · [RT-C-6668882025](https://github.com/RT-C-6668882025)**.

- [Moling](https://github.com/xiaoxi668v-prog/moling), by 小西校长: upstream application and handwriting foundation.
- [Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime) `0.3.0`: on-device agent, Skills and MCP client, MIT; [bundled license](app/src/main/assets/agent-licenses/Android-Agent-Runtime-MIT.txt).
- Google ML Kit Digital Ink Recognition and AndroidX components.
- Long Cang, Ma Shan Zheng and Zhi Mang Xing fonts, SIL Open Font License 1.1.

WE retains **GPL-3.0**; see [LICENSE](LICENSE). Third-party code and fonts retain their respective licenses.

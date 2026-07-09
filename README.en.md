<div align="center">

[中文](README.md) · **English**

# Moling （墨灵）

**An "ink spirit" living inside your e-ink screen — write a question by hand, pause your pen, and the answer appears on the paper.**

<img src="docs/01-device.jpg" alt="Moling running on an e-ink tablet" width="620">

No text field, no buttons — just a pen and paper. You handwrite a question on the e-ink
screen; Moling recognizes your strokes and then "writes" the reply back onto the page,
character by character, in plain classical Chinese (like margin notes in an old book).

</div>

---

## ⬇️ Download & Install

> For non-technical users: no commands, no compiling — just download and go.

**👉 [Download Moling APK (v1.0.1)](https://github.com/xiaoxi668v-prog/moling/releases/download/v1.0.1/moling-1.0.1.apk)**

Or grab the newest build from the **[Releases page](https://github.com/xiaoxi668v-prog/moling/releases/latest)**.

**Steps:**

1. Open the link above on your phone / e-ink tablet and download `moling-1.0.1.apk`；
2. Tap to install (if you see an "unknown sources" prompt, allow this install when asked)；
3. On first launch you'll be guided to enter a large-model API once (see *Configure the LLM* below), and you're ready to write.

> Best experience on **Huawei-style e-ink tablets**; it also installs and runs on ordinary
> Android phones / tablets — you just won't get that e-ink "paper" feel.

---

## ✍️ What it looks like

**Paper on open** — launch straight into a blank sheet and Moling's self-introduction, with no extra UI.

<img src="docs/02-welcome.jpg" alt="Welcome page" width="360">

**Write to ask, pause to answer** — write "how to sleep early" and, after a short pause,
Moling writes back a reply in classical Chinese, stroke by stroke.

<img src="docs/03-qa.jpg" alt="Handwritten Q&A example" width="360">

**Tap the seal at the bottom-left** to summon a half-screen panel from the page: browse
your **history** of every exchange, delete single entries, or start a fresh conversation.

<img src="docs/04-history.jpg" alt="History panel" width="360">

**Settings** let you adjust reply font size, switch between three handwriting fonts, and tune
how quickly recognition fires after you lift your pen.

<img src="docs/05-settings-font.jpg" alt="Settings — font & writing" width="360">

Further down, enter your own **large-model API** (key / endpoint / model name);
an optional self-hosted handwriting-recognition service is also supported.

<img src="docs/06-settings-api.jpg" alt="Settings — LLM API" width="360">

---

## 📖 About Moling

- Author: Principal Xiaoxi (小西校长)
- Version: Moling 1.0.1
- License: GPL-3.0 (see [LICENSE](LICENSE))
- Platform: Android (minSdk 26 / targetSdk 34), Kotlin + classic Views, no Jetpack Compose

### How direct e-ink writing works (in one line)

On supported Huawei e-ink devices, Moling uses reflection to call the system's existing
handwriting framework (`HWEditView`) and panel-refresh interface (`EPDCDevice`), so ink
appears "a stroke at a time" almost without app-layer drawing — pushing writing latency to
the limit. On other devices it automatically falls back to pure self-drawing + AndroidX
motion prediction (Kalman extrapolation): same behavior, just slightly higher latency.

Recognition runs on Google **ML Kit Digital Ink** (local, offline, consuming stroke
trajectories directly — no image upload). Answers go to a user-configured endpoint
compatible with the **Anthropic Messages API**.

---

## 🔧 Configure the LLM API

The first launch shows a welcome and guides you into Settings; afterwards you can
**tap the bottom-left seal** anytime to open the half-screen panel and switch to **Settings**:

- **API key**: obtain from the DeepSeek open platform (or any compatible service), in the form `sk-xxxxxx`.
- **Endpoint**: defaults to `https://api.deepseek.com/anthropic`; change it to any service compatible with the Anthropic Messages API.
- **Model name**: defaults to `deepseek-v4-flash`.

Once all three are filled in and saved, Moling can reply. If any of the three is empty,
Moling gently prompts you on the page to configure it first — it never crashes.
The key is stored only on-device (SharedPreferences); it never enters any build artifact and is never uploaded.

Settings also include:

- **Font**: reply font size (24–48) and switching between three open-source handwriting fonts, applied instantly.
- **Writing**: pen-lift recognition delay (300ms–3000ms) — increase it if you write slowly with pauses, decrease it if you want it snappier.
- **Handwriting recognition (optional)**: on devices without Google services (e.g. some Huawei
  models) on-device ML Kit recognition isn't available; you can self-host a recognition
  service and enter its address here (`http://<LAN-IP>:<port>/api/ask`). Leave it blank to use on-device recognition only.

---

## 👆 Controls

- **Handwrite a question**: just write on the screen; after a short pause (default 0.8s, adjustable 0.3–3s in Settings) it auto-recognizes and answers.
- **Tap the bottom-left seal**: open the half-screen panel (**History** / **Settings** / **About**), landing on **History** first.
- **History**: grouped by conversation; tap any entry to review it; tap "删" (Delete) at the end of a row to remove that single entry (with confirmation); "＋ 新对话" (New Chat) starts a fresh one.
- **Two-finger swipe left/right**: page through / review.
- **Long-press top-left**: clear the whole book (start a new conversation; history is still reviewable).
- **Long-press top-right**: open Settings directly.
- **Handwrite `/new`**: start a new conversation.

---

## 🛠 Build it yourself (developers)

Prerequisites: JDK 17, Android SDK (platform 34, build-tools).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk   # or your SDK path
./gradlew assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # run pure-logic unit tests
```

Build artifacts contain **no keys whatsoever**. Just install the APK onto an e-ink tablet
(the first launch guides you through configuring the LLM API).

---

## 🖋 Fonts

Three open-source handwriting fonts are bundled (all under SIL Open Font License 1.1):

- Long Cang （龙藏）
- Ma Shan Zheng （马善政）
- Zhi Mang Xing （智莽行）

Font files live in `app/src/main/assets/` and ship with the repository to guarantee reproducible builds.

---

## 🙏 Credits & third-party notices

- Handwriting fonts: Long Cang / Ma Shan Zheng / Zhi Mang Xing (Google Fonts, SIL OFL 1.1)
- Handwriting recognition: Google ML Kit Digital Ink Recognition
- UI framework: AndroidX (incl. `androidx.input` motion prediction)
- Huawei e-ink handwriting framework: interoperated via reflection with the system's existing
  capabilities (`HWEditView` / `EPDCDevice`); contains no Huawei proprietary code. Non-Huawei
  devices automatically fall back to pure self-drawing + motion prediction.

---

## 📜 License

Released under **GPL-3.0**. You may freely use, study, modify, and redistribute it;
derivative works must also be open-sourced under GPL-3.0. See [LICENSE](LICENSE) for the full terms.

---

## 💬 Community

Find it useful, have ideas, or want to report a bug? You're welcome to scan the QR code below
to join the "Moling APP" chat group:

<img src="docs/07-wechat-group.jpg" alt="Moling APP chat group QR code" width="260">

> The group QR code is refreshed periodically. If it has expired, leave a note on [Issues](https://github.com/xiaoxi668v-prog/moling/issues).

Repository: https://github.com/xiaoxi668v-prog/moling

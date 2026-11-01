# WE editor integration and task boundaries

## Product contract

Home remains a handwriting/text conversation surface. Drawing opens a separate
editor. PDF/AI learning pages remain independently writable documents. Selecting
a workspace resumes its conversation on Home; it does not navigate to a canvas.
A task owns conversations, raw home ink, drafts, drawing documents, source
materials, generated pages and execution records. Editor mode is not task identity.

Implemented in this change:
- Restore visible Home InkView and the Huawei direct-writing path; typed input
  submits on Home. Add an explicit Home Send action (auto-recognition stays opt-in).
- Save software Home ink drafts per task; preserve submitted raw ink as separate
  handwriting assets. Draft restore rescales coordinates for the viewport.
- Keep an active drawing pointer per workspace; new drawings are distinct PDF
  identities. Creating a drawing does not publish an AI learning turn or replace
  its latest-ready pointer.
- Retain native Drawdance drawing, layers and MyPaint controls from the drawing
  branch. Editor, PNG exports and MCP snapshots use the same layer renderer.

## xnotes audit and first extraction

Source: https://github.com/shardulvs/xnotes-android
Audited checkout: 26f42b2945df85087ab8d36a5344aa0e0c949707
License: MIT; bundled as assets/agent-licenses/Xnotes-MIT.txt.

The repository separates pure Kotlin `core` code from `platform`, `canvas`,
`gl`, the serialization format and Compose UI. Its whole DocumentCodec is not a
standalone Android library: it refers to document/page/items, styled text, source
PDFs, page templates, assets and their lifetimes. PDF handling includes native
PDFium and platform threading. Importing that subsystem requires a data adapter,
not copying a complete second application's explorer and editor.

First reused files, preserved in the upstream package:
- core/geometry/Pt.kt
- core/geometry/Affine.kt

WE now uses those transforms for selection rotation, mirror, scale and movement.
Future candidates: vector fragment erasure/spatial indexing; PDF text markup and
page-margin transforms. They require individual dependency and format audits.

## Drawpile audit

Pinned Drawpile: 2c2a616f0e944fe86e9fd2cd420bfc3e0ee87461.
The C/C++ Drawdance engine can run behind JNI without Qt. The complete Drawpile
client uses Qt modules and its own application/lifecycle/UI. It cannot be mounted
as a regular Android View. WE currently integrates the native engine and an
Android editor; it does not bundle the full Qt client. Qt-client embedding would
require a separate deployment/Activity/resource and document bridge investigation.

## Jetpack Ink candidate

Official Ink 1.0.0 source artifacts were inspected, including
InProgressStrokesView. Live authoring is a View with explicit stroke handoff to a
dry renderer; it is not a drop-in Paint replacement. A future adapter must
preserve physical samples for recognition, finished stroke identity, legacy draft
rendering and Huawei fallback. No Jetpack Ink dependency is claimed in this
change; the current Home renderer continues to use its existing motion predictor.

## Remaining verification and limitations

Physical Redmi/stylus latency and Huawei draft restoration are not verified.
Home draft restoration currently covers the software InkView path, not replay
into Huawei's proprietary HWEditView. Source drawings remain independent from
PDF/AI-page history; a full searchable asset catalogue and general backup bundle
remain future work. See third-party/drawpile.md for drawing feature boundaries.

## Validation of this implementation

- `testDebugUnitTest`: 118 tests, zero failures/skips, including Home input,
  task-scoped drafts, existing workspace navigation, serialization and xnotes transforms.
- `lintDebug`: zero errors; 47 warnings remain.
- `assembleDebug` and `assembleDebugAndroidTest`: passed with ARM64, ARMv7 and x86_64 native libraries.
- Host CMake/CTest: persistent canvas, preview isolation, local erase,
  cross-stroke smudge, MyPaint colour output and legacy ARGB import passed.
- MCP relay Node test passed. APK signature verification passed.
- Android instrumentation tests were compiled but not run on a device in this environment.

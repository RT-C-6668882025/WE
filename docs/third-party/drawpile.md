# Drawpile / Drawdance Android integration

Upstream: https://github.com/drawpile/Drawpile
Pinned commit: 2c2a616f0e944fe86e9fd2cd420bfc3e0ee87461
Zstandard: https://github.com/facebook/zstd at f8745da6ff1ad1e7bab384bd1f9d742439278e99.

Run `bash tools/fetch-native-sources.sh` before Gradle builds. It retrieves the
pinned source trees, including upstream copyright notices. CI uses the same
script. Source directories are build dependencies under vendor/, not copied
unversioned snapshots. APK license notices are in assets/drawdance-licenses.txt.

The NDK compiles upstream dpcommon, dpmsg, dpdb, dpengine and bundled MyPaint,
Parson, SQLite and qgrayraster. It uses POSIX threading, stdio, Android zlib and
pinned Zstandard. It does not instantiate Qt or a desktop process.

Drawing strokes tagged drawdance-v1 run the real upstream classic brush engine,
message serialization objects, 15-bit tiled canvas and flattening through JNI.
The Android layer renderer keeps a native tiled canvas alive between strokes. Live
stroke previews clone the upstream canvas state (copy-on-write); releasing a
preview never mutates committed ink. Undo and edits deterministically replay the
saved stroke commands. Legacy vector and mask strokes retain their renderer and
are imported through tiled PutImage messages before subsequent native strokes.

Local drawing features exposed in WE:
- Layer creation, naming, visibility, locking, opacity, ordering, duplication and deletion.
- Normal, multiply, screen, overlay, darken and lighten composition.
- Classic and MyPaint engine presets, hardness, opacity, spacing, pressure and smudge controls.
- Cross-stroke mixing on the current layer; local eraser and whole-stroke eraser.
- Lines, rectangles, ellipses, exact-colour contiguous layer fill and eyedropper.
- Whole-stroke selection move/scale/copy/delete, undo/redo, opaque/transparent PNG export.
- Layer metadata and brush parameters saved with the page and used by editor, snapshots and MCP.

This is an Android local drawing workspace, not the complete desktop Drawpile
application. Multiplayer/session hosting, recording playback, arbitrary .myb
preset import, textured brush packs, selection masks, raster selection transforms,
and cross-layer colour sampling are not implemented. MyPaint choices are built-in
parameter presets, not the desktop preset collection. Native working resolution
is capped at 1536 pixels on the longest side; PNG export is capped at 2048.
Only two layer canvases are cached at a time to bound native memory. Large layer
counts and long sessions still require real ARM tablet latency measurements.

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
The adapter renders bounded per-stroke native canvases and Android composites
those rasters over the PDF. Finished rasters use a bounded memory cache. Page
ink remains replayable vectors; editor, snapshots and MCP share the renderer.
Older drawpile-classic-v1 mask strokes and vector strokes retain their renderer.

This completes the classic brush-to-native-canvas path, not every Drawpile
feature: native canvases are currently isolated per stroke; cross-stroke smudge,
layer management UI, MyPaint preset selection, recording and collaboration are
not exposed. Real ARM tablet latency and long-stroke benchmarks remain required.

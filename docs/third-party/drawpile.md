# Drawpile Android port: classic brush kernel

Upstream: https://github.com/drawpile/Drawpile
Pinned commit: 2c2a616f0e944fe86e9fd2cd420bfc3e0ee87461
Source: src/drawdance/libengine/dpengine/paint.c
License: GPL-3.0-or-later; copyright notices retained in drawpile_classic.c.
Classic stamps also derive from GIMP, as the upstream header records.

The Android NDK library `we_drawpile` ports the classic round mask lookup and
mask rasterizer. JNI returns a stamp; Android Canvas composites spaced dabs.
Pressure interpolation and dab placement are WE adapter code, not upstream
Drawpile's brush engine. This is a **partial engine port**, not the complete
Drawdance engine, MyPaint, layers, blend modes, recording or network collaboration.
No desktop process or Qt UI is required.

New drawing strokes record `engine=drawpile-classic-v1`; existing vector strokes
retain their appearance. Editor, snapshots and MCP read_page share the renderer.
Native load failures are not silently disguised as successful Drawpile drawing.

Next: full Drawdance canvas/brush pipeline, tile caching, blend modes, layers,
then benchmark latency and memory on physical ARM64 tablets.

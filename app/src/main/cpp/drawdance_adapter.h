// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdbool.h>
#include <stdint.h>
bool we_drawdance_render(int width, int height, const float *points, int count,
                         float brush_width, uint32_t color, uint32_t *pixels);

typedef struct WE_Canvas WE_Canvas;
WE_Canvas *we_canvas_new(int width, int height);
WE_Canvas *we_canvas_clone(WE_Canvas *canvas);
void we_canvas_free(WE_Canvas *canvas);
bool we_canvas_seed(WE_Canvas *canvas, const uint32_t *pixels);
bool we_canvas_stroke(WE_Canvas *canvas, const float *points, int count, float size, uint32_t color, const float *options);
bool we_canvas_pixels(WE_Canvas *canvas, uint32_t *pixels);

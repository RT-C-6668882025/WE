// SPDX-License-Identifier: GPL-3.0-or-later
// Android adapter to upstream Drawpile/Drawdance. Brush scheduling, dabs,
// 15-bit blending, tile canvas and flattening are all executed by upstream code.
#include "drawdance_adapter.h"
#include <dpengine/brush.h>
#include <dpengine/brush_engine.h>
#include <dpengine/canvas_state.h>
#include <dpengine/draw_context.h>
#include <dpengine/pixels.h>
#include <dpmsg/message.h>
#include <dpmsg/messages.h>
#include <math.h>
#include <stdlib.h>

typedef struct { DP_Message **items; int used, capacity; } Messages;
static void push(void *user, DP_Message *message) {
    Messages *m = user;
    if (m->used == m->capacity) {
        m->capacity = m->capacity ? m->capacity * 2 : 32;
        m->items = DP_realloc(m->items, sizeof(*m->items) * m->capacity);
    }
    m->items[m->used++] = message;
}
static bool apply(DP_CanvasState **canvas, DP_DrawContext *context, DP_Message *message) {
    DP_CanvasState *next = DP_canvas_state_handle(*canvas, context, NULL, message);
    DP_message_decref(message);
    if (!next) return false;
    DP_canvas_state_decref(*canvas); *canvas = next; return true;
}
bool we_drawdance_render(int width, int height, const float *points, int count,
                         float brush_width, uint32_t color, uint32_t *pixels) {
    if (width < 1 || height < 1 || width > 2048 || height > 2048 || count < 1 || count > 1000000 ||
        !isfinite(brush_width) || brush_width <= 0 || brush_width > 1000) return false;
    for (int i = 0; i < count * 4; ++i) if (!isfinite(points[i])) return false;
    DP_DrawContext *dc = DP_draw_context_new();
    DP_CanvasState *canvas = DP_canvas_state_new();
    Messages messages = {0};
    bool ok = apply(&canvas,dc,DP_msg_canvas_resize_new(0,0,width,height,0)) &&
        apply(&canvas,dc,DP_msg_layer_tree_create_new(0,1,0,0,0,0,"",0));
    if (ok) {
        DP_BrushEngine *be = DP_brush_engine_new(NULL,push,NULL,NULL,&messages);
        DP_ClassicBrush brush = {0};
        brush.size.min = brush_width * 0.25f; brush.size.max = brush_width;
        brush.hardness.min = brush.hardness.max = 0.8f;
        brush.opacity.min = brush.opacity.max = ((color >> 24) & 255) / 255.0f;
        brush.spacing = 0.12f; brush.shape = DP_BRUSH_SHAPE_CLASSIC_SOFT_ROUND;
        brush.paint_mode = DP_PAINT_MODE_DIRECT; brush.brush_mode = DP_BLEND_MODE_NORMAL;
        brush.erase_mode = DP_BLEND_MODE_ERASE;
        brush.color = DP_upixel8_to_float((DP_UPixel8){color | 0xff000000u});
        brush.size_dynamic.type = DP_CLASSIC_BRUSH_DYNAMIC_PRESSURE;
        for (int i = 0; i < DP_CLASSIC_BRUSH_CURVE_VALUE_COUNT; ++i) brush.size.curve.values[i] = i / 255.0f;
        DP_BrushEngineStrokeParams params = {0}; params.layer_id = 1;
        DP_brush_engine_classic_brush_set(be,&brush,&params,NULL,false);
        DP_brush_engine_random_seed_set(be,1);
        DP_brush_engine_stroke_begin(be,canvas,1,false,false,false,false,1.0f,0.0f);
        for (int i = 0; i < count; ++i) {
            const float *p = points+i*4;
            DP_BrushPoint bp = {0}; bp.x = p[0]; bp.y = p[1]; bp.pressure = fmaxf(0,fminf(1,p[2])); bp.time_msec = (long long)p[3];
            DP_brush_engine_stroke_to(be,bp,canvas);
        }
        DP_brush_engine_stroke_end(be,(long long)points[(count-1)*4+3]+1,canvas,false);
        DP_brush_engine_dabs_flush(be); DP_brush_engine_free(be);
        for (int i = 0; i < messages.used; ++i) {
            if (ok) ok = apply(&canvas,dc,messages.items[i]);
            else DP_message_decref(messages.items[i]);
        }
        if (ok) ok = apply(&canvas,dc,DP_msg_pen_up_new(1,1));
        if (ok) {
            unsigned char *rgba = DP_malloc((size_t)width*height*4);
            ok = DP_canvas_state_to_flat_separated_urgba8(canvas, DP_FLAT_IMAGE_INCLUDE_SUBLAYERS | DP_FLAT_IMAGE_UNPREMULTIPLY, NULL,NULL,rgba);
            if (ok) for (int i = 0; i < width*height; ++i) {
                int size = width*height;
                pixels[i] = ((uint32_t)rgba[i+size*3]<<24) | ((uint32_t)rgba[i]<<16) | ((uint32_t)rgba[i+size]<<8) | rgba[i+size*2];
            }
            DP_free(rgba);
        }
    }
    DP_free(messages.items); DP_canvas_state_decref(canvas); DP_draw_context_free(dc);
    return ok;
}

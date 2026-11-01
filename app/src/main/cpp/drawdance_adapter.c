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
#include <string.h>
#include <zlib.h>

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


struct WE_Canvas { DP_CanvasState *state; DP_DrawContext *context; int width, height; bool ok; };
static bool session_apply(WE_Canvas *s, DP_Message *m) {
    if (!s->ok) { DP_message_decref(m); return false; }
    s->ok = apply(&s->state, s->context, m); return s->ok;
}
static void session_push(void *user, DP_Message *m) { session_apply(user, m); }
WE_Canvas *we_canvas_new(int width, int height) {
    if (width < 1 || height < 1 || width > 2048 || height > 2048) return NULL;
    WE_Canvas *s = calloc(1, sizeof(*s)); if (!s) return NULL;
    s->state = DP_canvas_state_new(); s->context = DP_draw_context_new();
    s->width = width; s->height = height; s->ok = true;
    if (!session_apply(s, DP_msg_canvas_resize_new(0,0,width,height,0)) ||
        !session_apply(s, DP_msg_layer_tree_create_new(0,1,0,0,0,0,"",0))) {
        we_canvas_free(s); return NULL;
    }
    return s;
}
void we_canvas_free(WE_Canvas *s) {
    if (s) { DP_canvas_state_decref(s->state); DP_draw_context_free(s->context); free(s); }
}
WE_Canvas *we_canvas_clone(WE_Canvas *s) {
    if (!s || !s->ok) return NULL;
    WE_Canvas *copy = calloc(1,sizeof(*copy)); if (!copy) return NULL;
    *copy = *s; copy->state = DP_canvas_state_incref(s->state); copy->context = DP_draw_context_new(); return copy;
}
static void copy_image(size_t size, unsigned char *out, void *user) { memcpy(out,user,size); }
bool we_canvas_seed(WE_Canvas *s, const uint32_t *pixels) {
    if (!s || !pixels) return false;
    // Small PutImage tiles stay below the protocol payload limit, even for noise.
    for (int y=0;y<s->height;y+=64) for (int x=0;x<s->width;x+=64) {
        int w=s->width-x<64?s->width-x:64, h=s->height-y<64?s->height-y:64;
        unsigned char raw[64*64*4], zipped[64*64*4+128];
        for (int j=0;j<h;j++) for(int i=0;i<w;i++) {
            uint32_t c=pixels[(y+j)*s->width+x+i]; unsigned a=c>>24; int n=(j*w+i)*4;
            raw[n]=(c&255)*a/255; raw[n+1]=((c>>8)&255)*a/255; raw[n+2]=((c>>16)&255)*a/255; raw[n+3]=a;
        }
        unsigned len=w*h*4; zipped[0]=len>>24; zipped[1]=len>>16; zipped[2]=len>>8; zipped[3]=len;
        uLongf size=sizeof(zipped)-4; if(compress2(zipped+4,&size,raw,len,1)!=Z_OK) return false;
        if(!session_apply(s,DP_msg_put_image_new(0,1,DP_BLEND_MODE_NORMAL,x,y,w,h,copy_image,size+4,zipped))) return false;
    }
    return true;
}
bool we_canvas_stroke(WE_Canvas *s, const float *points, int count, float size,
                      uint32_t color, const float *options) {
    if (!s || !s->ok || count<1 || count>1000000 || !isfinite(size) || size<=0 || size>1000) return false;
    for(int i=0;i<count*4;i++) if(!isfinite(points[i])) return false;
    for(int i=0;i<6;i++) if(!isfinite(options[i]) || options[i]<0 || options[i]>1) return false;
    // options: hardness, opacity, spacing, smudge, pressure, MyPaint.
    DP_BrushEngine *be=DP_brush_engine_new(NULL,session_push,NULL,NULL,s);
    DP_BrushEngineStrokeParams params={0}; params.layer_id=1;
    DP_UPixelFloat ink=DP_upixel8_to_float((DP_UPixel8){color | 0xff000000u});
    bool erase=options[3]<0.001f && (color>>24)==0;
    if (options[5]>0.5f) {
        DP_MyPaintBrush brush={0}; brush.color=ink; brush.paint_mode=DP_PAINT_MODE_DIRECT;
        brush.brush_mode=DP_BLEND_MODE_NORMAL; brush.erase_mode=DP_BLEND_MODE_ERASE; brush.erase=erase;
        DP_MyPaintSettings settings={0};
        settings.mappings[MYPAINT_BRUSH_SETTING_OPAQUE].base_value=options[1];
        settings.mappings[MYPAINT_BRUSH_SETTING_OPAQUE_MULTIPLY].base_value=1;
        settings.mappings[MYPAINT_BRUSH_SETTING_RADIUS_LOGARITHMIC].base_value=logf(size/2);
        settings.mappings[MYPAINT_BRUSH_SETTING_HARDNESS].base_value=options[0];
        settings.mappings[MYPAINT_BRUSH_SETTING_DABS_PER_ACTUAL_RADIUS].base_value=3;
        settings.mappings[MYPAINT_BRUSH_SETTING_SMUDGE].base_value=options[3];
        settings.mappings[MYPAINT_BRUSH_SETTING_SMUDGE_LENGTH].base_value=0.5;
        if(options[4]>0.5f) {
            DP_MyPaintControlPoints *pressure=&settings.mappings[MYPAINT_BRUSH_SETTING_RADIUS_LOGARITHMIC].inputs[MYPAINT_BRUSH_INPUT_PRESSURE];
            pressure->n=2; pressure->xvalues[0]=0;pressure->yvalues[0]=-1.4f;pressure->xvalues[1]=1;pressure->yvalues[1]=0;
        }
        DP_brush_engine_mypaint_brush_set(be,&brush,&settings,&params,NULL,false);
    } else {
        DP_ClassicBrush brush={0}; brush.color=ink;
        brush.size.min=size*(options[4]>0.5f?0.25f:1);brush.size.max=size;
        brush.hardness.min=brush.hardness.max=options[0];
        brush.opacity.min=brush.opacity.max=options[1];
        brush.smudge.min=brush.smudge.max=options[3]; brush.resmudge=1; brush.smudge_alpha=true;
        brush.spacing=fmaxf(0.01f,options[2]); brush.shape=DP_BRUSH_SHAPE_CLASSIC_SOFT_ROUND;
        brush.paint_mode=DP_PAINT_MODE_DIRECT;brush.brush_mode=DP_BLEND_MODE_NORMAL;brush.erase_mode=DP_BLEND_MODE_ERASE;brush.erase=erase;
        if(options[4]>0.5f) brush.size_dynamic.type=DP_CLASSIC_BRUSH_DYNAMIC_PRESSURE;
        for(int i=0;i<256;i++) brush.size.curve.values[i]=i/255.0f;
        DP_brush_engine_classic_brush_set(be,&brush,&params,NULL,false);
    }
    DP_brush_engine_random_seed_set(be,1);
    DP_brush_engine_stroke_begin(be,s->state,1,false,false,false,false,1,0);
    for(int i=0;i<count && s->ok;i++) {
        DP_BrushPoint bp={0};bp.x=points[i*4];bp.y=points[i*4+1];bp.pressure=fmaxf(0,fminf(1,points[i*4+2]));bp.time_msec=(long long)points[i*4+3];
        DP_CanvasState *before=DP_canvas_state_incref(s->state);
        DP_brush_engine_stroke_to(be,bp,before); DP_brush_engine_dabs_flush(be); DP_canvas_state_decref(before);
    }
    DP_CanvasState *before=DP_canvas_state_incref(s->state);
    DP_brush_engine_stroke_end(be,(long long)points[(count-1)*4+3]+1,before,false); DP_canvas_state_decref(before);
    DP_brush_engine_dabs_flush(be);DP_brush_engine_free(be);
    if(s->ok) session_apply(s,DP_msg_pen_up_new(1,1)); return s->ok;
}
bool we_canvas_pixels(WE_Canvas *s, uint32_t *pixels) {
    if(!s || !s->ok) return false;
    int size=s->width*s->height; unsigned char *rgba=malloc((size_t)size*4); if(!rgba) return false;
    bool ok=DP_canvas_state_to_flat_separated_urgba8(s->state,DP_FLAT_IMAGE_INCLUDE_SUBLAYERS|DP_FLAT_IMAGE_UNPREMULTIPLY,NULL,NULL,rgba);
    if(ok) for(int i=0;i<size;i++) pixels[i]=((uint32_t)rgba[i+size*3]<<24)|((uint32_t)rgba[i]<<16)|((uint32_t)rgba[i+size]<<8)|rgba[i+size*2];
    free(rgba);return ok;
}

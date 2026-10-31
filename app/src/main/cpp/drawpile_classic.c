/*
 * Copyright (C) 2022 askmeaboutloom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * --------------------------------------------------------------------
 *
 * Most of this code is based on Drawpile, using it under the GNU General
 * Public License, version 3. See 3rdparty/licenses/drawpile/COPYING for
 * details.
 *
 * --------------------------------------------------------------------
 *
 * Parts of this code are based on libmypaint, using it under the MIT license.
 * See 3rdparty/libmypaint/COPYING for details.
 *
 * --------------------------------------------------------------------
 *
 * Parts of this code are based on GIMP, using it under the GNU General Public
 * License, version 3. See 3rdparty/licenses/gimp/COPYING for details.
 *
 */
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <math.h>
#include <string.h>
#define DP_BIT15 32768
#define CLASSIC_LUT_RADIUS 128
#define CLASSIC_LUT_SIZE (128 * 128)
#define DP_square_float(x) ((x) * (x))
#define DP_int_to_float(x) ((float)(x))
#define DP_float_to_int(x) ((int)(x))
#define DP_uint32_to_uint16(x) ((uint16_t)(x))
typedef struct { int top, left, diameter; uint16_t *data; } DP_BrushStamp;
// Per-call LUT avoids introducing upstream global initialization/thread locks.
static _Thread_local uint16_t classic_lut[CLASSIC_LUT_SIZE];
static const uint16_t *get_classic_lut(int hardness) {
    double h = 1.0 - hardness / 100.0;
    double exponent = h < 0.0000004 ? 1000000.0 : 0.4 / h;
    for (int i = 0; i < CLASSIC_LUT_SIZE; ++i) {
        double d = 1.0 - pow(pow(sqrt(i) / CLASSIC_LUT_RADIUS, exponent), 2.0);
        classic_lut[i] = (uint16_t)(d * DP_BIT15);
    }
    return classic_lut;
}
static void prepare_stamp(DP_BrushStamp *stamp, int scaled_hardness,
                          float radius, int diameter, const uint16_t **out_lut,
                          float *out_lut_scale)
{
    stamp->diameter = diameter;

    int stamp_offsets = -diameter / 2;
    stamp->top = stamp_offsets;
    stamp->left = stamp_offsets;

    *out_lut = get_classic_lut(scaled_hardness);
    *out_lut_scale = DP_square_float((CLASSIC_LUT_RADIUS - 1.0f) / radius);
}

static void get_mask(DP_BrushStamp *stamp, float radius, int scaled_hardness)
{
    float r = radius / 2.0f;

    if (r < 1.0) {
        // Special case for single-pixel brush.
        stamp->left = -1;
        stamp->top = -1;
        stamp->diameter = 3;
        uint16_t data[] = {0, 0, 0, 0, DP_BIT15, 0, 0, 0, 0};
        memcpy(stamp->data, data, sizeof(data));
    }
    else {
        float offset;
        int diameter;
        int raw_diameter = DP_float_to_int(ceilf(radius) + 2.0f);
        bool raw_diameter_even = raw_diameter % 2 == 0;
        if (raw_diameter_even) {
            diameter = raw_diameter + 1;
            offset = -1.0f;
        }
        else {
            diameter = raw_diameter;
            offset = -0.5f;
        }

        // Empirically determined fudge factors to make small brushes look nice.
        float fudge = r < 4.0                      ? 0.8f
                    : raw_diameter_even && r < 8.0 ? 0.9f
                                                   : 1.0f;

        const uint16_t *lut;
        float lut_scale;
        prepare_stamp(stamp, scaled_hardness, r, diameter, &lut, &lut_scale);
        uint16_t *d = stamp->data;

        for (int y = 0; y < diameter; ++y) {
            float yy = DP_square_float(DP_int_to_float(y) - r + offset);
            for (int x = 0; x < diameter; ++x) {
                float dist =
                    (DP_square_float(DP_int_to_float(x) - r + offset) + yy)
                    * fudge * lut_scale;
                int i = DP_float_to_int(dist);
                *d = i < CLASSIC_LUT_SIZE ? lut[i] : 0;
                ++d;
            }
        }
    }
}

static void get_high_res_mask(DP_BrushStamp *stamp, float radius,
                              int scaled_hardness)
{

    int raw_diameter = DP_float_to_int(ceilf(radius) + 2.0f);
    float raw_offset = (ceilf(radius) - radius) / -2.0f;
    int diameter;
    float offset;
    if (raw_diameter % 2 == 0) {
        diameter = raw_diameter + 1;
        offset = raw_offset - 2.5f;
    }
    else {
        diameter = raw_diameter;
        offset = raw_offset - 1.5f;
    }

    const uint16_t *lut;
    float lut_scale;
    prepare_stamp(stamp, scaled_hardness, radius, diameter, &lut, &lut_scale);
    uint16_t *ptr = stamp->data;

    for (int y = 0; y < diameter; ++y) {
        float y2 = DP_int_to_float(y * 2);
        float yy0 = DP_square_float(y2 - radius + offset);
        float yy1 = DP_square_float(y2 + 1.0f - radius + offset);

        for (int x = 0; x < diameter; ++x) {
            float x2 = DP_int_to_float(x * 2);
            float xx0 = DP_square_float(x2 - radius + offset);
            float xx1 = DP_square_float(x2 + 1.0f - radius + offset);

            int dist00 = DP_float_to_int((xx0 + yy0) * lut_scale);
            int dist01 = DP_float_to_int((xx0 + yy1) * lut_scale);
            int dist10 = DP_float_to_int((xx1 + yy0) * lut_scale);
            int dist11 = DP_float_to_int((xx1 + yy1) * lut_scale);

            uint32_t acc =
                (dist00 < CLASSIC_LUT_SIZE ? (uint32_t)lut[dist00] : 0)
                + (dist01 < CLASSIC_LUT_SIZE ? (uint32_t)lut[dist01] : 0)
                + (dist10 < CLASSIC_LUT_SIZE ? (uint32_t)lut[dist10] : 0)
                + (dist11 < CLASSIC_LUT_SIZE ? (uint32_t)lut[dist11] : 0);
            *(ptr++) = DP_uint32_to_uint16(acc / 4);
        }
    }
}


JNIEXPORT jintArray JNICALL
Java_com_opc_baize_paper_DrawpileBrush_nativeStamp(JNIEnv *env, jobject self, jint hardness) {
    (void)self;
    if (hardness < 0 || hardness > 100) return NULL;
    uint16_t mask[35 * 35] = {0};
    DP_BrushStamp stamp = {0, 0, 0, mask};
    get_mask(&stamp, 32.0f, hardness);
    jint pixels[35 * 35];
    int count = stamp.diameter * stamp.diameter;
    for (int i = 0; i < count; ++i) pixels[i] = (jint)(((uint32_t)mask[i] * 255 / DP_BIT15) << 24) | 0x00ffffff;
    jintArray result = (*env)->NewIntArray(env, count);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, count, pixels);
    return result;
}

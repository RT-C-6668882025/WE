// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdbool.h>
#include <stdint.h>
bool we_drawdance_render(int width, int height, const float *points, int count,
                         float brush_width, uint32_t color, uint32_t *pixels);

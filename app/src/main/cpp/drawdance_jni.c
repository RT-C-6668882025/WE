// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <stdlib.h>
#include "drawdance_adapter.h"
JNIEXPORT jintArray JNICALL Java_com_opc_baize_paper_DrawdanceBrush_nativeRender(
    JNIEnv *env, jobject self, jfloatArray input, jint width, jint height, jfloat size, jint color) {
    (void)self;
    if (!input || width < 1 || height < 1 || width > 2048 || height > 2048) return NULL;
    jsize length = (*env)->GetArrayLength(env,input);
    if (length < 4 || length % 4 || length > 4000000) return NULL;
    jfloat *points = (*env)->GetFloatArrayElements(env,input,NULL);
    if (!points) return NULL;
    uint32_t *pixels = calloc((size_t)width*height,sizeof(*pixels));
    bool ok = pixels && we_drawdance_render(width,height,points,length/4,size,(uint32_t)color,pixels);
    (*env)->ReleaseFloatArrayElements(env,input,points,JNI_ABORT);
    jintArray result = NULL;
    if (ok) {
        result = (*env)->NewIntArray(env,width*height);
        if (result) (*env)->SetIntArrayRegion(env,result,0,width*height,(jint *)pixels);
    }
    free(pixels); return result;
}

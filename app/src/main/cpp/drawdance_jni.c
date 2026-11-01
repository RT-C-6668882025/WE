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
#define JNI_NAME(name) Java_com_opc_baize_paper_NativeDrawing_##name
JNIEXPORT jlong JNICALL JNI_NAME(create)(JNIEnv *e,jobject o,jint w,jint h) { (void)e;(void)o;return (jlong)(intptr_t)we_canvas_new(w,h); }
JNIEXPORT jlong JNICALL JNI_NAME(cloneCanvas)(JNIEnv *e,jobject o,jlong h) { (void)e;(void)o;return (jlong)(intptr_t)we_canvas_clone((WE_Canvas *)(intptr_t)h); }
JNIEXPORT void JNICALL JNI_NAME(destroy)(JNIEnv *e,jobject o,jlong h) { (void)e;(void)o;we_canvas_free((WE_Canvas *)(intptr_t)h); }
JNIEXPORT jboolean JNICALL JNI_NAME(stroke)(JNIEnv *e,jobject o,jlong h,jfloatArray input,jfloat size,jint color,jfloatArray opts) {
    (void)o; if(!input || !opts) return JNI_FALSE;
    int n=(*e)->GetArrayLength(e,input);if(n<4 || n%4 || n>4000000 || (*e)->GetArrayLength(e,opts)!=6) return JNI_FALSE;
    jfloat options[6];(*e)->GetFloatArrayRegion(e,opts,0,6,options);
    jfloat *points=(*e)->GetFloatArrayElements(e,input,NULL);if(!points) return JNI_FALSE;
    bool ok=we_canvas_stroke((WE_Canvas *)(intptr_t)h,points,n/4,size,(uint32_t)color,options);
    (*e)->ReleaseFloatArrayElements(e,input,points,JNI_ABORT);return ok;
}
JNIEXPORT jboolean JNICALL JNI_NAME(seed)(JNIEnv *e,jobject o,jlong h,jintArray input,jint w,jint height) {
    (void)o;if(!input || w<1 || height<1 || w>2048 || height>2048 || (*e)->GetArrayLength(e,input)!=w*height) return JNI_FALSE;
    jint *pixels=(*e)->GetIntArrayElements(e,input,NULL);if(!pixels) return JNI_FALSE;
    bool ok=we_canvas_seed((WE_Canvas *)(intptr_t)h,(uint32_t *)pixels);
    (*e)->ReleaseIntArrayElements(e,input,pixels,JNI_ABORT);return ok;
}
JNIEXPORT jintArray JNICALL JNI_NAME(pixels)(JNIEnv *e,jobject o,jlong h,jint w,jint height) {
    (void)o;if(w<1 || height<1 || w>2048 || height>2048) return NULL;
    uint32_t *pixels=calloc((size_t)w*height,sizeof(*pixels));if(!pixels) return NULL;
    bool ok=we_canvas_pixels((WE_Canvas *)(intptr_t)h,pixels);jintArray result=NULL;
    if(ok) {result=(*e)->NewIntArray(e,w*height);if(result)(*e)->SetIntArrayRegion(e,result,0,w*height,(jint *)pixels);}
    free(pixels);return result;
}

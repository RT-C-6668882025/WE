#include "drawdance_adapter.h"
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#define W 160
#define H 100
static void stroke(WE_Canvas *s,uint32_t color,float y,float width,const float *options) {
    float points[]={20,y,1,0,140,y,1,100};
    assert(we_canvas_stroke(s,points,2,width,color,options));
}
int main(void) {
    const float classic[]={1,1,.1,0,0,0},mypaint[]={.8,1,.1,0,0,1},smudge[]={.6,1,.1,.95,0,0};
    uint32_t before[W*H],after[W*H];
    WE_Canvas *s=we_canvas_new(W,H);assert(s);
    stroke(s,0xffff0000,50,20,classic);assert(we_canvas_pixels(s,before));
    assert((before[50*W+80]>>24)>200);
    WE_Canvas *copy=we_canvas_clone(s);assert(copy);
    stroke(copy,0x00000000,50,6,classic);assert(we_canvas_pixels(copy,after));assert((after[50*W+80]>>24)<30);
    assert(we_canvas_pixels(s,after));assert(after[50*W+80]==before[50*W+80]);we_canvas_free(copy);
    float blue[]={80,50,1,0,140,50,1,100};assert(we_canvas_stroke(s,blue,2,20,0xff0000ff,classic));
    float mix[]={50,50,1,0,120,50,1,100};assert(we_canvas_stroke(s,mix,2,30,0xff000000,smudge));
    assert(we_canvas_pixels(s,after));assert((after[50*W+100]>>16&255)>0 && (after[50*W+100]&255)>0);
    we_canvas_free(s);
    s=we_canvas_new(W,H);stroke(s,0xff00ff00,30,20,mypaint);assert(we_canvas_pixels(s,before));
    assert((before[30*W+80]>>8&255)>200 && (before[30*W+80]>>24)>100);
    copy=we_canvas_new(W,H);assert(we_canvas_seed(copy,before));assert(we_canvas_pixels(copy,after));
    for(int i=0;i<W*H;i++) {
        assert(abs((int)(before[i]>>24)-(int)(after[i]>>24))<=1);
        for(int shift=0;shift<=16;shift+=8) {
            float a=((before[i]>>shift)&255)*(before[i]>>24)/255.f;
            float b=((after[i]>>shift)&255)*(after[i]>>24)/255.f;assert(fabsf(a-b)<1.1f);
        }
    }
    we_canvas_free(copy);we_canvas_free(s);
    assert(!we_canvas_new(0,10));assert(!we_canvas_new(3000,10));
    puts("PASS: native continuity, preview isolation, local erase, cross-stroke smudge, MyPaint and legacy ARGB import");
    return 0;
}

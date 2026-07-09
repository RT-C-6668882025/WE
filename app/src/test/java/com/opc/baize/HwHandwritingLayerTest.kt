package com.opc.baize

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * HWEditView 直写层的容错底线：桌面 JVM 上 android.eink.view.edit.HWEditView 必然不存在，
 * Class.forName 抛 ClassNotFoundException。isFrameworkPresent() 必须安全返回 false、不抛——
 * MainActivity 正是靠这条守卫（`useDirectInk() && isFrameworkPresent()`）决定走不走直写层，
 * 从而在真机框架缺失/不放行时无缝回退 InkView 老路。
 *
 * （create(context) 的完整反射路径依赖设备类 + 真实 Context，桌面 JVM 无法构造，
 * 故不在此覆盖；isFrameworkPresent()=false 已保证 create 根本不会被调用。
 * IHWTrack/HWPoint → Stroke 的转换逻辑由 HwTrackConverterTest 用假数据完整覆盖。）
 */
class HwHandwritingLayerTest {

    @Test fun frameworkAbsentReturnsFalseNoCrash() {
        assertFalse(HwHandwritingLayer.isFrameworkPresent())
    }
}

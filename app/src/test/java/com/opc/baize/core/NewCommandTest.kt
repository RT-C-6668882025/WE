package com.opc.baize.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** is_new_command 规则移植正确性（用例对照 server.py 注释与实测行为） */
class NewCommandTest {

    @Test fun exactNew() {
        assertTrue(NewCommand.isNewCommand("new"))
        assertTrue(NewCommand.isNewCommand("NEW"))
        assertTrue(NewCommand.isNewCommand("/new"))
        assertTrue(NewCommand.isNewCommand(" / new "))
    }

    @Test fun ocrToleranceWithSlash() {
        // OCR 常把手写 /new 认成 /wew 之类：带斜杠 + 编辑距离 ≤1 即算
        assertTrue(NewCommand.isNewCommand("/wew"))
        assertTrue(NewCommand.isNewCommand("/mew"))
        assertTrue(NewCommand.isNewCommand("/nw"))    // 删一个字符
        assertTrue(NewCommand.isNewCommand("/news"))  // 增一个字符
        assertTrue(NewCommand.isNewCommand("／new"))  // 全角斜杠
        assertTrue(NewCommand.isNewCommand("、new"))  // 顿号也算斜杠形
        assertTrue(NewCommand.isNewCommand("|new"))   // 竖线
        assertTrue(NewCommand.isNewCommand("\\new"))  // 反斜杠
    }

    @Test fun noSlashMustBeExact() {
        assertFalse(NewCommand.isNewCommand("wew"))
        assertFalse(NewCommand.isNewCommand("news"))
        assertFalse(NewCommand.isNewCommand("mew"))
    }

    @Test fun chineseNeverTriggers() {
        assertFalse(NewCommand.isNewCommand("写个new故事"))
        assertFalse(NewCommand.isNewCommand("/new 一下"))
        assertFalse(NewCommand.isNewCommand("新篇"))
    }

    @Test fun tooFarOrTooLong() {
        assertFalse(NewCommand.isNewCommand("/n"))          // 字母数 <2
        assertFalse(NewCommand.isNewCommand("/nevermind"))  // 字母数 >4
        assertFalse(NewCommand.isNewCommand("/abc"))        // 编辑距离 >1
        assertFalse(NewCommand.isNewCommand(""))
        assertFalse(NewCommand.isNewCommand("/"))
    }

    @Test fun edit1Basics() {
        assertTrue(NewCommand.edit1("new", "new"))
        assertTrue(NewCommand.edit1("wew", "new"))
        assertTrue(NewCommand.edit1("ne", "new"))
        assertTrue(NewCommand.edit1("neww", "new"))
        assertFalse(NewCommand.edit1("nw", "wen"))
        assertFalse(NewCommand.edit1("abcde", "new"))
    }
}

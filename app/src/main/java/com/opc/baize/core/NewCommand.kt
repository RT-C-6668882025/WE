package com.opc.baize.core

/**
 * 手写 /new 指令判定——逐条移植 server.py 的 is_new_command / _edit1：
 * - 识别常把手写 /new 认成 /wew 之类：带斜杠时字母部分与 "new" 编辑距离 ≤1 即算；
 * - 无斜杠须严格等于 "new"（大小写不敏感）；
 * - 含汉字一律不算（防“写个new故事”误触发）。
 */
object NewCommand {

    private val CJK = Regex("[一-鿿]")
    private val SLASH = Regex("[/\\\\／、|]")

    /** 编辑距离 ≤1（增/删/换一个字符），与 server.py 的 _edit1 同构 */
    fun edit1(aIn: String, bIn: String): Boolean {
        var a = aIn
        var b = bIn
        if (a == b) return true
        var la = a.length
        var lb = b.length
        if (Math.abs(la - lb) > 1) return false
        if (la == lb) {
            var diff = 0
            for (i in 0 until la) if (a[i] != b[i]) diff++
            return diff <= 1
        }
        if (la > lb) {
            val t = a; a = b; b = t
            val tl = la; la = lb; lb = tl
        }
        var i = 0
        var j = 0
        var diff = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) {
                i++
            } else {
                diff++
                if (diff > 1) return false
            }
            j++
        }
        return true
    }

    fun isNewCommand(recognized: String): Boolean {
        if (CJK.containsMatchIn(recognized)) return false
        val letters = recognized.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()
        if (letters == "new") return true
        val hasSlash = SLASH.containsMatchIn(recognized)
        return hasSlash && letters.length in 2..4 && edit1(letters, "new")
    }
}

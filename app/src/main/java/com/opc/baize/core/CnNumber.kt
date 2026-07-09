package com.opc.baize.core

/** 页码大写数字（壹/贰/叁…），逐条移植 index.html 的 numToCn；超 99 退回阿拉伯数字。 */
object CnNumber {
    private val D = arrayOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖")

    fun numToCn(n: Int): String {
        if (n <= 0 || n > 99) return n.toString()
        val t = n / 10
        val o = n % 10
        val sb = StringBuilder()
        if (t >= 2) sb.append(D[t])
        if (t >= 1) sb.append("拾")
        if (o > 0) sb.append(D[o])
        return if (sb.isEmpty()) D[0] else sb.toString()
    }
}

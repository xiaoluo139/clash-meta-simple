package com.github.kr328.clash.design.util

import android.graphics.Color

/**
 * 把 [overlay] 按 [ratio] 混到 [base] 上。
 *
 * 用于生成「选中态」的浅色底（例如模式卡片、当前节点行），
 * 这样不用改主题也能在浅色/深色模式下都保持文字可读。
 */
fun blendColor(base: Int, overlay: Int, ratio: Float): Int {
    val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
    val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
    val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()

    return Color.rgb(r, g, b)
}
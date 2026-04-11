package me.magnum.melonds.domain.model

import androidx.annotation.Keep

@Keep
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val bottom get() = y + height

    val right get() = x + width
}

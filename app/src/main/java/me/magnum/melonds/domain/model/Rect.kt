package me.magnum.melonds.domain.model

import androidx.annotation.Keep

@Keep
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val bottom get() = y + height

    val right get() = x + width

    fun contains(other: Rect): Boolean {
        return x <= other.x && y <= other.y && right >= other.right && bottom >= other.bottom
    }
}

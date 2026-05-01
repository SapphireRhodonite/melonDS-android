package me.magnum.melonds.impl.dtos.layout

import com.google.gson.annotations.SerializedName
import me.magnum.melonds.domain.model.layout.Insets

data class InsetsDto(
    @SerializedName("left")
    val left: Int,
    @SerializedName("top")
    val top: Int,
    @SerializedName("right")
    val right: Int,
    @SerializedName("bottom")
    val bottom: Int
) {

    fun toModel(): Insets {
        return Insets(left, top, right, bottom)
    }

    companion object {
        fun fromModel(insets: Insets): InsetsDto {
            return InsetsDto(insets.left, insets.top, insets.right, insets.bottom)
        }
    }
}

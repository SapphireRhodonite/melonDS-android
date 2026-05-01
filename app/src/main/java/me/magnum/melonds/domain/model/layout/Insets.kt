package me.magnum.melonds.domain.model.layout

data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    companion object {
        val Zero = Insets(0, 0, 0, 0)
    }
}

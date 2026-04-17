package me.magnum.melonds.ui.layouteditor.model

import me.magnum.melonds.domain.model.layout.LayoutComponent

data class LayoutComponentPositionEditorState(
    val component: LayoutComponent,
    val x: Int,
    val y: Int,
    val maxX: Int,
    val maxY: Int,
)

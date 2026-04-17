package me.magnum.melonds.ui.layouteditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.magnum.melonds.R
import me.magnum.melonds.ui.common.component.dialog.BaseDialog
import me.magnum.melonds.ui.common.component.dialog.DialogButton
import me.magnum.melonds.ui.common.melonOutlinedTextFieldColors
import me.magnum.melonds.ui.layouteditor.model.LayoutComponentPositionEditorState
import me.magnum.melonds.utils.getLayoutComponentName

@Composable
fun LayoutComponentPositionDialog(
    positionEditorState: LayoutComponentPositionEditorState?,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    if (positionEditorState == null) {
        return
    }

    var xValueText by rememberSaveable(
        positionEditorState.component,
        positionEditorState.x,
        positionEditorState.y,
        positionEditorState.maxX,
        positionEditorState.maxY,
    ) {
        mutableStateOf(positionEditorState.x.toString())
    }
    var yValueText by rememberSaveable(
        positionEditorState.component,
        positionEditorState.x,
        positionEditorState.y,
        positionEditorState.maxX,
        positionEditorState.maxY,
    ) {
        mutableStateOf(positionEditorState.y.toString())
    }

    val xRange = 0..positionEditorState.maxX
    val yRange = 0..positionEditorState.maxY
    val parsedX = xValueText.toIntOrNull()?.takeIf(xRange::contains)
    val parsedY = yValueText.toIntOrNull()?.takeIf(yRange::contains)
    val isXInvalid = xValueText.isNotEmpty() && parsedX == null
    val isYInvalid = yValueText.isNotEmpty() && parsedY == null

    BaseDialog(
        title = stringResource(
            R.string.layout_component_position_title,
            stringResource(getLayoutComponentName(positionEditorState.component)),
        ),
        onDismiss = onDismiss,
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CoordinateField(
                    label = stringResource(R.string.input_x),
                    value = xValueText,
                    range = xRange,
                    isError = isXInvalid,
                    imeAction = ImeAction.Next,
                    onValueChange = { xValueText = it },
                )
                CoordinateField(
                    label = stringResource(R.string.input_y),
                    value = yValueText,
                    range = yRange,
                    isError = isYInvalid,
                    imeAction = ImeAction.Done,
                    onValueChange = { yValueText = it },
                    onDone = {
                        if (parsedX != null && parsedY != null) {
                            onSave(parsedX, parsedY)
                        }
                    }
                )
            }
        },
        buttons = {
            DialogButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(R.string.ok),
                enabled = parsedX != null && parsedY != null,
                onClick = {
                    if (parsedX != null && parsedY != null) {
                        onSave(parsedX, parsedY)
                    }
                },
            )
        }
    )
}

@Composable
private fun CoordinateField(
    label: String,
    value: String,
    range: IntRange,
    isError: Boolean,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            colors = melonOutlinedTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            singleLine = true,
        )
        Text(
            text = stringResource(R.string.layout_position_allowed_range, range.first, range.last),
            style = MaterialTheme.typography.caption,
            color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
        )
    }
}

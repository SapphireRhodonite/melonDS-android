package me.magnum.melonds.ui.romlist.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.rom.Rom

@Composable
fun RomContextMenu(
    rom: Rom?,
    onDismiss: () -> Unit,
    onToggleFavorite: (Rom) -> Unit,
    onShowDetails: (Rom) -> Unit,
    onSendSaveFile: (Rom) -> Unit,
    onImportSaveFile: (Rom) -> Unit,
) {
    if (rom == null) return
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colors.surface,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = rom.config.customName ?: rom.name,
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ContextItem(
                    icon = if (rom.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    label = stringResource(
                        if (rom.isFavorite) R.string.rom_action_unfavorite
                        else R.string.rom_action_favorite,
                    ),
                    onClick = {
                        onToggleFavorite(rom)
                    },
                )
                ContextItem(
                    icon = Icons.Filled.Info,
                    label = stringResource(R.string.rom_action_details),
                    onClick = {
                        onShowDetails(rom)
                        onDismiss()
                    },
                )
                ContextItem(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.rom_action_send_save_file),
                    onClick = {
                        onSendSaveFile(rom)
                        onDismiss()
                    },
                )
                ContextItem(
                    icon = Icons.Filled.FileDownload,
                    label = stringResource(R.string.rom_action_import_save_file),
                    onClick = {
                        onImportSaveFile(rom)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colors.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            color = MaterialTheme.colors.onSurface,
            style = MaterialTheme.typography.body1,
            modifier = Modifier.weight(1f, fill = true),
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
        )
    }
}

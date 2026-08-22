package com.omnideck.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.omnideck.designsystem.theme.Spacing

/**
 * Confirmation dialog (OD-111).
 *
 * [destructive] is a real distinction, not styling: a dialog that can delete data
 * renders its confirm action on error colours and does **not** dismiss on an outside
 * tap, so an accidental touch cannot destroy something. Leaving that to each module
 * is how one of them eventually gets it wrong.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (destructive) {
                TertiaryButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                )
            } else {
                PrimaryButton(text = confirmLabel, onClick = onConfirm)
            }
        },
        dismissButton = { TertiaryButton(text = dismissLabel, onClick = onDismiss) },
        properties = DialogProperties(
            // A destructive action requires a deliberate choice; dismissing by
            // tapping outside is too easy to do by accident.
            dismissOnClickOutside = !destructive,
            dismissOnBackPress = !destructive,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * Modal bottom sheet with the platform's padding and dismissal behaviour applied
 * once, so module sheets do not each rediscover navigation-bar insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                // Without this a sheet's last control sits under the gesture bar.
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg),
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = Spacing.md),
                )
            }
            content()
        }
    }
}

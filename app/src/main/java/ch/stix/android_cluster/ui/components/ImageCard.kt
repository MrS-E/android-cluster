package ch.stix.android_cluster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.stix.android_cluster.R
import ch.stix.android_cluster.data.VmImage
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * Image selector: lists imported images and lets the user pick the boot image,
 * delete one, or import a new one via the system file picker.
 */
@Composable
fun ImageCard(
    images: List<VmImage>,
    selectedImage: String?,
    onSelect: (VmImage) -> Unit,
    onDelete: (VmImage) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.small),
        ) {
            Text(
                text = stringResource(R.string.images_card_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (images.isEmpty()) {
                Text(
                    text = stringResource(R.string.images_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                images.forEach { image ->
                    ImageRow(
                        image = image,
                        selected = image.name == selectedImage,
                        onSelect = { onSelect(image) },
                        onDelete = { onDelete(image) },
                    )
                }
            }

            TextButton(onClick = onImport) {
                Text(stringResource(R.string.action_import_image))
            }
        }
    }
}

@Composable
private fun ImageRow(
    image: VmImage,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = Dimens.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.small),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = image.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatSize(image.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.action_delete_image))
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb >= 1024) {
        stringResource(R.string.image_size_mb, "%.1f".format(kb / 1024.0))
    } else {
        stringResource(R.string.image_size_kb, "%.0f".format(kb))
    }
}

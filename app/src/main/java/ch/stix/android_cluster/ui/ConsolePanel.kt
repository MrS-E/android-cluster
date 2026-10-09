package ch.stix.android_cluster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import ch.stix.android_cluster.R
import ch.stix.android_cluster.ui.theme.AndroidclusterTheme
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * Scrollable serial console: shows the guest output stream (auto-scrolling to
 * the newest line) and forwards typed input to the guest.
 */
@Composable
fun ConsolePanel(
    lines: List<String>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
        // The console lives inside a vertically scrollable parent, so the list
        // must be given a bounded height — an unbounded (infinite) max height
        // would make LazyColumn throw at measure time.
        val outputModifier = Modifier
            .fillMaxWidth()
            .height(Dimens.consoleHeight)
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.console_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = outputModifier.padding(Dimens.small),
            )
        } else {
            LazyColumn(state = listState, modifier = outputModifier) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.extraSmall / 2),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.console_input_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = input.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_send))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConsolePanelPreview() {
    AndroidclusterTheme {
        ConsolePanel(
            lines = listOf(
                "[    0.000000] Booting Linux on physical CPU 0x0",
                "[    0.000000] Linux version 6.1.0 (buildroot)",
                "login: _",
            ),
            onSend = {},
        )
    }
}

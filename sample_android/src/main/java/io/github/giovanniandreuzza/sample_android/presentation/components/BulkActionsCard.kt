package io.github.giovanniandreuzza.sample_android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giovanniandreuzza.sample_android.presentation.MainUiAction

@Composable
fun BulkActionsCard(
    onAction: (MainUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bulk actions", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onAction(MainUiAction.EnqueueAll) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enqueue All", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { onAction(MainUiAction.CancelAll) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel All", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onAction(MainUiAction.PauseAll) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pause All", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { onAction(MainUiAction.ResumeAll) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Resume All", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

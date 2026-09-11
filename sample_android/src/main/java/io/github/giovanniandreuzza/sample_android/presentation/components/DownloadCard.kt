package io.github.giovanniandreuzza.sample_android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giovanniandreuzza.sample_android.presentation.DownloadDisplayState
import io.github.giovanniandreuzza.sample_android.presentation.DownloadItemUiState
import io.github.giovanniandreuzza.sample_android.presentation.VerificationResult

@Composable
fun DownloadCard(
    item: DownloadItemUiState,
    onEnqueue: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: file name + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusBadge(state = item.displayState)
            }

            // Progress / status detail
            when (val state = item.displayState) {
                is DownloadDisplayState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is DownloadDisplayState.Paused -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "${(state.progress * 100).toInt()}% — paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DownloadDisplayState.Enqueued -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is DownloadDisplayState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                else -> {}
            }

            // Content digest — what the transferred bytes hashed to, and the result of
            // re-deriving it from the file on disk.
            item.checksum?.let { digest ->
                Text(
                    text = "sha256 " + digest.take(16) + "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (val verification = item.verification) {
                VerificationResult.Running -> Text(
                    text = "verifying…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                VerificationResult.Match -> Text(
                    text = "verified — bytes on disk are unchanged",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                is VerificationResult.Mismatch -> Text(
                    text = "MISMATCH — on disk " + verification.onDisk.take(16) + "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                is VerificationResult.Error -> Text(
                    text = "verify failed: " + verification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                null -> {}
            }

            // Action buttons — contextual, right-aligned
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                when (item.displayState) {
                    DownloadDisplayState.Idle -> {
                        Button(onClick = onEnqueue) { Text("Enqueue") }
                    }

                    is DownloadDisplayState.Downloading -> {
                        OutlinedButton(onClick = onPause) { Text("Pause") }
                        OutlinedButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Cancel") }
                    }

                    is DownloadDisplayState.Paused -> {
                        Button(onClick = onResume) { Text("Resume") }
                        OutlinedButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Cancel") }
                    }

                    is DownloadDisplayState.Failed -> {
                        Button(onClick = onRetry) { Text("Retry") }
                    }

                    DownloadDisplayState.Finished -> {
                        OutlinedButton(onClick = onVerify) { Text("Verify") }
                    }

                    DownloadDisplayState.Enqueued -> {
                        Button(onClick = onStart) { Text("Start") }
                    }
                }
            }
        }
    }
}

package io.github.giovanniandreuzza.sample_android.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.giovanniandreuzza.sample_android.presentation.DownloadDisplayState

@Composable
fun StatusBadge(state: DownloadDisplayState, modifier: Modifier = Modifier) {
    val (label, containerColor, contentColor) = when (state) {
        DownloadDisplayState.Idle -> Triple(
            "Idle",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )

        DownloadDisplayState.Enqueued -> Triple(
            "Queued",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )

        is DownloadDisplayState.Downloading -> Triple(
            "Downloading",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )

        is DownloadDisplayState.Paused -> Triple(
            "Paused",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )

        DownloadDisplayState.Finished -> Triple(
            "Done \u2713",
            Color(0xFF2E7D32),
            Color.White
        )

        is DownloadDisplayState.Failed -> Triple(
            "Failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

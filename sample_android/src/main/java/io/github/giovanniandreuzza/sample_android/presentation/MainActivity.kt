package io.github.giovanniandreuzza.sample_android.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.giovanniandreuzza.sample_android.presentation.ui.theme.SamplesTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SamplesTheme {
                val uiState by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                ObserveAsEvents(viewModel.uiEvent) { event ->
                    when (event) {
                        is MainUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                    }
                }

                MainScreen(
                    uiState = uiState,
                    onAction = viewModel::onAction,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}

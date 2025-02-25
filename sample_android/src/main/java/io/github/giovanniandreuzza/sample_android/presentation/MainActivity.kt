package io.github.giovanniandreuzza.sample_android.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.giovanniandreuzza.sample_android.presentation.ui.theme.SamplesTheme
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.component.KoinComponent
import timber.log.Timber
import java.io.File

/**
 * Main activity.
 *
 * @author Giovanni Andreuzza
 */
class MainActivity : ComponentActivity(), KoinComponent {

    private val viewModel: MainViewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folder = File(filesDir, "test").also {
            it.mkdirs()
        }

        // Comment this line to keep downloaded files
        cleanDownloadFolder()

        setContent {
            SamplesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        Text(
                            text = "Sample Android - Test Downloads",
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.enqueueAll(folder)
                                }
                            ) {
                                Text(
                                    text = "Enqueue All",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.startAll()
                                }
                            ) {
                                Text(
                                    text = "Start All",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.pauseAll()
                                }
                            ) {
                                Text(
                                    text = "Pause All",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.resumeAll()
                                }
                            ) {
                                Text(
                                    text = "Resume All",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.cancelAll()
                                }
                            ) {
                                Text(
                                    text = "Cancel All",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.enqueue1(folder)
                                }
                            ) {
                                Text(
                                    text = "Enqueue Download 1",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.enqueue2(folder)
                                }
                            ) {
                                Text(
                                    text = "Enqueue Download 2",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.enqueue3(folder)
                                }
                            ) {
                                Text(
                                    text = "Enqueue Download 3",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.start1()
                                }
                            ) {
                                Text(
                                    text = "Start Download 1",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.start2()
                                }
                            ) {
                                Text(
                                    text = "Start Download 2",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.start3()
                                }
                            ) {
                                Text(
                                    text = "Start Download 3",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.pause1()
                                }
                            ) {
                                Text(
                                    text = "Pause Download 1",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.pause2()
                                }
                            ) {
                                Text(
                                    text = "Pause Download 2",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.pause3()
                                }
                            ) {
                                Text(
                                    text = "Pause Download 3",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.resume1()
                                }
                            ) {
                                Text(
                                    text = "Resume Download 1",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.resume2()
                                }
                            ) {
                                Text(
                                    text = "Resume Download 2",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.resume3()
                                }
                            ) {
                                Text(
                                    text = "Resume Download 3",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(14.dp)
                        )

                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min)
                        ) {
                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.cancel1()
                                }
                            ) {
                                Text(
                                    text = "Cancel Download 1",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.cancel2()
                                }
                            ) {
                                Text(
                                    text = "Cancel Download 2",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.padding(14.dp)
                            )

                            Button(
                                modifier = Modifier.padding(innerPadding),
                                onClick = {
                                    viewModel.cancel3()
                                }
                            ) {
                                Text(
                                    text = "Cancel Download 3",
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun cleanDownloadFolder() {
        Timber.i("Deleting all downloaded files")

        val folder = File(filesDir, "installer")
        folder.listFiles()?.forEach {
            val deleted = it.delete()

            if (deleted) {
                Timber.i("${it.name} deleted successfully")
            } else {
                Timber.e("${it.name} failed to be deleted")
            }
        }
    }
}
package dev.herdwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.herdwatch.ui.AppViewModel
import dev.herdwatch.ui.HerdwatchRoot
import dev.herdwatch.ui.theme.HerdwatchTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(application as HerdwatchApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HerdwatchTheme {
                HerdwatchRoot(viewModel)
            }
        }
    }
}

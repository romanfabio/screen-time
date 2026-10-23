package io.github.romanfabio.screentime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.romanfabio.screentime.ui.ScreenTimeApp
import io.github.romanfabio.screentime.ui.theme.ScreenTimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenTimeTheme {
                ScreenTimeApp()
            }
        }
    }
}

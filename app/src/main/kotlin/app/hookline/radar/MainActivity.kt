package app.hookline.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.hookline.radar.core.designsystem.HooklineTheme
import app.hookline.radar.map.MapScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System launch screen until the first frame; MapScreen then shows the same mark while data loads.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HooklineTheme {
                MapScreen()
            }
        }
    }
}

package ch.stix.android_cluster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ch.stix.android_cluster.ui.ClusterScreen
import ch.stix.android_cluster.ui.theme.AndroidclusterTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidclusterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClusterScreen()
                }
            }
        }
    }
}

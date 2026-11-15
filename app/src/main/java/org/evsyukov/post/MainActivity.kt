package org.evsyukov.post

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.theme.PostTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PostTheme {
                Surface(Modifier.fillMaxSize()) {
                    PostApp()
                }
            }
        }
    }
}

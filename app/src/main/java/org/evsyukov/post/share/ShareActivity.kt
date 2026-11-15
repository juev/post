package org.evsyukov.post.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.evsyukov.post.AppContainerHost
import org.evsyukov.post.MainActivity
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme

/**
 * The target of the Share menu (product.md R19–R23): a translucent window with the Share sheet over the app that
 * shared. The shared files are copied into app storage while this activity, which holds their URI grant, is
 * alive; it finishes only after an action, and the actions wait for the copy (R9, R19).
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AppContainerHost).container
        val shared = SharedContent.from(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PostTheme {
                    val model = viewModel { ShareModel(container, createSavedStateHandle(), shared) }
                    ShareSheet(
                        model = model,
                        onDone = { message ->
                            runOnUiThread {
                                // R21: the toast outlives the sheet, so it goes through the application context.
                                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        },
                        onOpenComposer = { draftId, openSchedule ->
                            runOnUiThread {
                                startActivity(
                                    Intent(this, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(MainActivity.EXTRA_DRAFT_ID, draftId)
                                        .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, openSchedule),
                                )
                                finish()
                            }
                        },
                        onDismiss = ::finish,
                    )
                }
            }
        }
    }
}

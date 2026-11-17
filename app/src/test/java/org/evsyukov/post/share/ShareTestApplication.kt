package org.evsyukov.post.share

import android.app.Application
import org.evsyukov.post.AppContainer
import org.evsyukov.post.AppContainerHost
import org.evsyukov.post.PrepareTrigger
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.media.probe.MediaDecodeCheck
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The application of the Share activity tests: a container with a stand-in cipher, counting publish and
 * prepare triggers, and a decode check the test decides.
 */
class ShareTestApplication : Application(), AppContainerHost {
    val triggers = AtomicInteger()
    val prepares = AtomicInteger()

    /** Why the device cannot decode a file; null, as on a working device, unless a test says otherwise. */
    var decodeProblem: (File, String) -> String? = { _, _ -> null }

    override val container: AppContainer by lazy {
        AppContainer(
            this,
            publishTrigger = PublishTrigger { triggers.incrementAndGet() },
            prepareTrigger = PrepareTrigger { prepares.incrementAndGet() },
            mediaDecodeCheck = MediaDecodeCheck { file, mime -> decodeProblem(file, mime) },
        ) { FakeCipher() }
    }
}

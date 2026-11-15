package org.evsyukov.post.share

import android.app.Application
import org.evsyukov.post.AppContainer
import org.evsyukov.post.AppContainerHost
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.FakeCipher
import java.util.concurrent.atomic.AtomicInteger

/** The application of the Share activity tests: a container with a stand-in cipher and a counting publish trigger. */
class ShareTestApplication : Application(), AppContainerHost {
    val triggers = AtomicInteger()

    override val container: AppContainer by lazy { AppContainer(this, publishTrigger = PublishTrigger { triggers.incrementAndGet() }) { FakeCipher() } }
}

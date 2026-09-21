package top.cxmeow.risingstones.app

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareArtifact
import top.cxmeow.risingstones.feature.personaldata.ui.compose.PersonalDataShareHost

/** Runtime-owned: configuration recreation does not revoke a receiver which is still opening PNG. */
internal class PersonalDataPngShareHost(
    context: Context,
    private val hasAccess: () -> Boolean,
    private val launch: (Intent) -> Unit = { context.applicationContext.startActivity(it) },
) : PersonalDataShareHost {
    private val controller = PngShareController(context)
    private val revision = AtomicLong()
    private val exportMutex = Mutex()

    override suspend fun share(artifact: PersonalDataShareArtifact) = exportMutex.withLock {
        val request = revision.get()
        check(hasAccess())
        try {
            val intent = withContext(Dispatchers.IO) { controller.prepare(artifact.pngBytes()) }
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                check(request == revision.get() && hasAccess())
                launch(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (error: Throwable) {
            controller.clear()
            throw error
        }
    }

    override fun clear() {
        revision.incrementAndGet()
        controller.clear()
    }
}

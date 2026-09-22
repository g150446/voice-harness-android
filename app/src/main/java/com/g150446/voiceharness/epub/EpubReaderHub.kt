package com.g150446.voiceharness.epub

import android.content.Context
import com.g150446.voiceharness.BleConnectionService
import com.g150446.voiceharness.EvenG2ReadingSession
import com.g150446.voiceharness.InteractionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide EPUB reader shared by the phone screens, the BLE service and voice handling. */
internal object EpubReaderHub {
    @Volatile private var library: EpubLibrary? = null
    @Volatile private var controller: EpubReaderController? = null

    fun library(context: Context): EpubLibrary = library ?: synchronized(this) {
        library ?: EpubLibrary(context.applicationContext).also { library = it }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun get(context: Context): EpubReaderController = controller ?: synchronized(this) {
        controller ?: EpubReaderController(
            store = library(context),
            glass = ServiceGlass,
            scope = scope,
        ).also { controller = it }
    }

    /** EPUB mode was switched on or the glass reconnected: show the reader's current page. */
    fun enterAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { get(appContext).enter() } }
    }

    /** The glass follows the reader only while EPUB mode is on and the plugin is polling. */
    private object ServiceGlass : EpubGlass {
        override fun following(): Boolean =
            BleConnectionService.interactionMode.value == InteractionMode.EPUB &&
                EvenG2ReadingSession.isClientActive()

        override fun publishReading(text: String) = EvenG2ReadingSession.publishReading(text)

        override fun publishResponse(text: String) = EvenG2ReadingSession.publishResponse(text)

        override fun failAdvance(message: String) = EvenG2ReadingSession.failAdvance(message)
    }
}

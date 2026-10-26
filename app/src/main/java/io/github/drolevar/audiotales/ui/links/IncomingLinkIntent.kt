package io.github.drolevar.audiotales.ui.links

import android.content.Intent
import io.github.drolevar.audiotales.data.online.IncomingBookLinkParser

/** Reads only approved scalar input; never forwards an Intent, flags, ClipData or stream. */
internal object IncomingLinkIntent {
    fun text(intent: Intent): String? = try {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.take(IncomingBookLinkParser.MAX_URL_LENGTH + 1) ?: ""
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                // Check length before materializing an arbitrary CharSequence implementation.
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                if (text == null || text.length > IncomingBookLinkParser.MAX_TEXT_LENGTH) "" else text.toString()
            } else null
            else -> null
        }
    } catch (_: RuntimeException) { "" }
}

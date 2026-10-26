package io.github.drolevar.audiotales.ui.links

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.online.*
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal sealed interface IncomingLinkState {
    data object Idle : IncomingLinkState
    data object Loading : IncomingLinkState
    data class ConfirmRegistration(val host: String, val deliveryId: String) : IncomingLinkState
    data class Ready(val deliveryId: String, val target: IncomingBookTarget) : IncomingLinkState
    data class Error(val reason: OnlineBookError, val canRetry: Boolean) : IncomingLinkState
}

@HiltViewModel
internal class IncomingBookLinkViewModel internal constructor(
    private val source: IncomingBookLinkSource,
    private val saved: SavedStateHandle,
) : ViewModel() {
    @Inject constructor(source: IncomingBookLinkResolver, saved: SavedStateHandle) : this(source as IncomingBookLinkSource, saved)
    private val mutableState = MutableStateFlow<IncomingLinkState>(IncomingLinkState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var restored = false

    /** Activity lifecycle entry; a restored confirmation never registers automatically. */
    fun restore() {
        if (restored) return
        restored = true
        val raw = saved.get<String>(RAW) ?: return
        if (saved.get<String>(STAGE) == "ready") {
            val library = saved.get<String>(LIBRARY)
            val id = saved.get<String>(REMOTE)
            val delivery = saved.get<String>(DELIVERY)
            if (library != null && id != null && delivery != null) {
                mutableState.value = IncomingLinkState.Ready(delivery, IncomingBookTarget(saved[BOOK], library, id))
                return
            }
        }
        if (saved.get<String>(STAGE) == "invalid") {
            mutableState.value = IncomingLinkState.Error(OnlineBookError.INVALID_LINK, false)
        } else if (saved.get<String>(STAGE) == "error") {
            val reason = saved.get<String>(ERROR)?.let { name -> OnlineBookError.entries.firstOrNull { it.name == name } }
                ?: OnlineBookError.NETWORK
            mutableState.value = IncomingLinkState.Error(reason, true)
        } else if (saved.get<String>(STAGE) == "confirm") {
            try {
                val request = IncomingBookLinkParser.parseSharedText(raw)
                mutableState.value = IncomingLinkState.ConfirmRegistration(request.baseUrl.removePrefix("https://").trimEnd('/'), requireNotNull(saved[DELIVERY]))
            } catch (_: OnlineBookException) {
                mutableState.value = IncomingLinkState.Error(OnlineBookError.INVALID_LINK, false)
            }
        } else {
            // Consent during an interrupted registration is asked again after process recreation.
            resolve(raw)
        }
    }

    fun accept(text: String) {
        val bounded = text.take(IncomingBookLinkParser.MAX_TEXT_LENGTH + 1)
        if (saved.get<String>(RAW) == bounded && state.value !is IncomingLinkState.Idle) return
        cancelWork()
        saved[RAW] = bounded
        saved[DELIVERY] = UUID.randomUUID().toString()
        resolve(bounded)
    }

    fun retry() { saved.get<String>(RAW)?.let(::resolve) }

    fun confirmRegistration(deliveryId: String) {
        val confirmation = state.value as? IncomingLinkState.ConfirmRegistration ?: return
        if (confirmation.deliveryId != deliveryId) return
        saved.get<String>(RAW)?.let { resolve(it, register = true) }
    }

    fun dismiss() {
        cancelWork()
        clearPending()
        mutableState.value = IncomingLinkState.Idle
    }

    fun consumed(deliveryId: String) {
        val ready = state.value as? IncomingLinkState.Ready ?: return
        if (ready.deliveryId != deliveryId) return
        clearPending()
        mutableState.value = IncomingLinkState.Idle
    }

    private fun resolve(raw: String, register: Boolean = false) {
        cancelWork()
        val token = generation
        mutableState.value = IncomingLinkState.Loading
        saved[STAGE] = "loading"
        job = viewModelScope.launch {
            try {
                val request = IncomingBookLinkParser.parseSharedText(raw)
                if (register) source.register(request)
                coroutineContext.ensureActive()
                val result = source.resolve(request)
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                mutableState.value = when (result) {
                    is IncomingBookResolution.NeedsRegistration -> {
                        saved[STAGE] = "confirm"
                        IncomingLinkState.ConfirmRegistration(result.baseUrl.removePrefix("https://").trimEnd('/'), requireNotNull(saved[DELIVERY]))
                    }
                    is IncomingBookResolution.Ready -> {
                        saved[BOOK] = result.target.bookId
                        saved[LIBRARY] = result.target.libraryId
                        saved[REMOTE] = result.target.remoteId
                        saved[STAGE] = "ready"
                        IncomingLinkState.Ready(requireNotNull(saved[DELIVERY]), result.target)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                val reason = (error as? OnlineBookException)?.reason ?: OnlineBookError.NETWORK
                saved[ERROR] = reason.name
                saved[STAGE] = if (reason == OnlineBookError.INVALID_LINK) "invalid" else "error"
                mutableState.value = IncomingLinkState.Error(reason, reason != OnlineBookError.INVALID_LINK)
            }
        }
    }

    private fun cancelWork() { generation++; job?.cancel(); job = null }
    private fun clearPending() { listOf(RAW, DELIVERY, STAGE, BOOK, LIBRARY, REMOTE, ERROR).forEach { saved.remove<String>(it) } }

    private companion object {
        const val RAW = "incoming.raw"
        const val DELIVERY = "incoming.delivery"
        const val STAGE = "incoming.stage"
        const val BOOK = "incoming.book"
        const val LIBRARY = "incoming.library"
        const val REMOTE = "incoming.remote"
        const val ERROR = "incoming.error"
    }
}

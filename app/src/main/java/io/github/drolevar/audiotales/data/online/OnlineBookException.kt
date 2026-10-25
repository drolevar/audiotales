package io.github.drolevar.audiotales.data.online

enum class OnlineBookError {
    INVALID_LINK, NETWORK, PROVIDER_RESPONSE, UNEXPECTED_FORMAT, EMPTY_PLAYLIST,
    UNSUPPORTED_SOURCE, RESPONSE_TOO_LARGE, SAVE_FAILED, ACCESS_NOT_CONFIGURED, UNSUPPORTED_LIBRARY,
}

/** Deliberately excludes transport URLs and access parameters from user-visible diagnostics. */
class OnlineBookException(val reason: OnlineBookError) : Exception()

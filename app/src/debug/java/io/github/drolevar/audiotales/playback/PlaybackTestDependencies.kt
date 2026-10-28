package io.github.drolevar.audiotales.playback

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.drolevar.audiotales.data.local.AudioTalesDatabase

/** Tests borrow the same database that the running service observes; excluded from release. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackTestDependencies {
    fun database(): AudioTalesDatabase
}

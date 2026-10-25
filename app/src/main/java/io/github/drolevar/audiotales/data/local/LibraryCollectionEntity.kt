package io.github.drolevar.audiotales.data.local

import androidx.room3.Embedded
import androidx.room3.Relation

/** Room loads both relations in the observing DAO transaction. */
data class LibraryCollectionEntity(
    @Embedded val book: BookEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["bookId"])
    val tracks: List<TrackEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["bookId"])
    val progress: List<PlaybackProgressEntity>,
)

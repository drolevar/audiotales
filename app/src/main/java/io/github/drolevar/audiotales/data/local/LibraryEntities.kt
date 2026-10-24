package io.github.drolevar.audiotales.data.local

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["bookId", "id"], unique = true),
        Index(value = ["bookId", "sequenceIndex"], unique = true),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val uri: String,
    val sequenceIndex: Int,
    val durationMs: Long? = null,
)

@Entity(
    tableName = "playback_progress",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["bookId", "id"],
        childColumns = ["bookId", "trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "trackId"])],
)
data class PlaybackProgressEntity(
    @PrimaryKey val bookId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["bookId", "id"],
        childColumns = ["bookId", "trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["bookId", "trackId"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val trackId: String,
    val positionMs: Long,
    val label: String,
    val createdAtEpochMs: Long,
)

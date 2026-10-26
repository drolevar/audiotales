package io.github.drolevar.audiotales.data.local

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.security.MessageDigest

object LibraryMigrations {
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE tracks ADD COLUMN sourceStartMs INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE tracks ADD COLUMN sourceEndMs INTEGER")
            connection.execSQL("ALTER TABLE tracks ADD COLUMN audioResourceId TEXT")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE books ADD COLUMN description TEXT")
            connection.execSQL("ALTER TABLE books ADD COLUMN language TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE online_libraries ADD COLUMN isRegistered INTEGER NOT NULL DEFAULT 1")
            connection.execSQL("ALTER TABLE online_libraries ADD COLUMN isSelected INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE books ADD COLUMN coverUrl TEXT")
            connection.execSQL("ALTER TABLE books ADD COLUMN coverReferer TEXT")
        }
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE books ADD COLUMN providerId TEXT")
            connection.execSQL("ALTER TABLE books ADD COLUMN providerBookId TEXT")
            connection.execSQL("ALTER TABLE books ADD COLUMN sourceUrl TEXT")
            connection.execSQL("CREATE UNIQUE INDEX index_books_providerId_providerBookId ON books(providerId, providerBookId)")
            connection.execSQL("ALTER TABLE tracks ADD COLUMN providerTrackRef TEXT")
            connection.execSQL("CREATE UNIQUE INDEX index_tracks_bookId_providerTrackRef ON tracks(bookId, providerTrackRef)")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE tracks ADD COLUMN streamReferer TEXT")
            connection.execSQL("ALTER TABLE tracks ADD COLUMN downloadReferer TEXT")
            connection.execSQL("CREATE TABLE IF NOT EXISTS online_libraries (id TEXT NOT NULL PRIMARY KEY, baseUrl TEXT NOT NULL, displayName TEXT NOT NULL, protocol TEXT NOT NULL)")
            connection.execSQL("CREATE UNIQUE INDEX index_online_libraries_baseUrl ON online_libraries(baseUrl)")
            // Version 2 used these two host names as provider IDs. Convert their library
            // references without replacing any book/track IDs or listening history.
            // This historical mapping does not limit newly registered library hosts.
            for (host in listOf("audioknigi.com.ua", "librivox.club")) {
                val base = "https://$host/"
                val id = "library-" + MessageDigest.getInstance("SHA-256")
                    .digest(base.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                connection.execSQL("INSERT INTO online_libraries (id, baseUrl, displayName, protocol) " +
                    "SELECT '$id', '$base', '$host', 'PARTNER' WHERE EXISTS " +
                    "(SELECT 1 FROM books WHERE providerId = '$host')")
                connection.execSQL("UPDATE tracks SET streamReferer = '${base}mobapp', downloadReferer = '$base' " +
                    "WHERE bookId IN (SELECT id FROM books WHERE providerId = '$host')")
                connection.execSQL("UPDATE books SET providerId = '$id' WHERE providerId = '$host'")
            }
        }
    }
}

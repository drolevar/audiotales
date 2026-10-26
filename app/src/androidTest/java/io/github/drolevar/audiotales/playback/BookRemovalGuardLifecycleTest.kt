package io.github.drolevar.audiotales.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookRemovalGuardLifecycleTest {
    @Test
    fun disconnectDoesNotReleaseRemovalAndFinallyCanReleaseWithoutAController() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val guard = BookRemovalGuard()
            val owner = PlaybackConnection(context, guard)
            val otherActivity = PlaybackConnection(context, guard)
            try {
                // The local operation reserves before IPC, even if dispatch cannot connect.
                assertFalse(owner.prepareBookRemoval("book"))
                assertTrue(guard.isReserved("book"))

                owner.disconnect()

                assertTrue("Activity disconnect must not reopen playback during deletion", guard.isReserved("book"))
                assertFalse(otherActivity.prepareBookRemoval("book"))
                assertTrue(otherActivity.finishBookRemoval("book"))
                assertTrue("Another Activity cannot release the owner's reservation", guard.isReserved("book"))
            } finally {
                withContext(NonCancellable) {
                    assertTrue(owner.finishBookRemoval("book"))
                }
            }
            assertFalse(guard.isReserved("book"))
            assertTrue(owner.finishBookRemoval("book"))
        }
    }
}

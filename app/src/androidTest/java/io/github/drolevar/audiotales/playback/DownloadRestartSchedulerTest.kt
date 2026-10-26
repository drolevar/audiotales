package io.github.drolevar.audiotales.playback

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadRestartSchedulerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun restartJobIsPersistedAndCancelledByANewSchedulerInstance() {
        val power = context.getSystemService(PowerManager::class.java)
        assertTrue("Run with an awake screen so the test job cannot execute", power.isInteractive)
        assertFalse("Run outside device idle mode", power.isDeviceIdleMode)
        val jobs = context.getSystemService(JobScheduler::class.java)
        // Do not replace a real download's pending restart job in a populated fixture.
        assertNull("A real download restart is already pending", jobs.getPendingJob(RESTART_JOB_ID))
        val requirements = Requirements(Requirements.NETWORK or Requirements.DEVICE_IDLE)

        try {
            val scheduler = createDownloadRestartScheduler(context)
            assertEquals(requirements, scheduler.getSupportedRequirements(requirements))
            assertTrue(scheduler.schedule(requirements, context.packageName, RESTART_ACTION))

            val job = jobs.getPendingJob(RESTART_JOB_ID)
            assertNotNull("The restart must be registered with Android JobScheduler", job)
            requireNotNull(job)
            assertEquals(RESTART_JOB_ID, job.id)
            assertEquals(ComponentName(context, PlatformScheduler.PlatformSchedulerService::class.java), job.service)
            assertTrue("The OS job must survive reboot without receiver enablement", job.isPersisted)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            assertTrue(job.isRequireDeviceIdle)
            assertEquals(context.packageName, job.extras.getString("service_package"))
            assertEquals(RESTART_ACTION, job.extras.getString("service_action"))
            assertEquals(requirements.requirements, job.extras.getInt("requirements"))

            assertTrue(createDownloadRestartScheduler(context).cancel())
            assertNull("A recreated scheduler must cancel the same app-owned job", jobs.getPendingJob(RESTART_JOB_ID))
        } finally {
            jobs.cancel(RESTART_JOB_ID)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun manifestAllowsOnlySystemBindingAndKeepsDownloadRestartPrivate() {
        val packages = context.packageManager
        val schedulerService = packages.getServiceInfo(
            ComponentName(context, PlatformScheduler.PlatformSchedulerService::class.java), 0,
        )
        assertTrue(schedulerService.enabled)
        assertTrue(schedulerService.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", schedulerService.permission)

        // Earlier APKs may still have durable WorkManager jobs waiting for network access.
        val legacySchedulerService = packages.getServiceInfo(
            ComponentName(context.packageName, "androidx.work.impl.background.systemjob.SystemJobService"),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue(legacySchedulerService.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", legacySchedulerService.permission)

        val packageInfo = packages.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        val restart = Intent(RESTART_ACTION).setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val handlers = packages.queryIntentServices(restart, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(1, handlers.size)
        assertEquals(AudioDownloadService::class.java.name, handlers.single().serviceInfo.name)
        assertFalse(handlers.single().serviceInfo.exported)
    }

    private companion object {
        // This is a durable app-owned identity, including across process recreation/upgrades.
        const val RESTART_JOB_ID = 1003
        const val RESTART_ACTION = "androidx.media3.exoplayer.downloadService.action.RESTART"
    }
}

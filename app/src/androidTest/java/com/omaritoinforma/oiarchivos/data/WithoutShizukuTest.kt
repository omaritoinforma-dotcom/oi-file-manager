package com.omaritoinforma.oiarchivos.data

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omaritoinforma.oiarchivos.UserActionActivity
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on a disposable emulator with shared-storage app-op granted by CI. */
@RunWith(AndroidJUnit4::class)
class WithoutShizukuTest {
    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val fixture get() = File(Environment.getExternalStorageDirectory(), "Download/oi-rootless-test.apk")
        @BeforeClass @JvmStatic fun prepareFixture() {
            fixture.parentFile!!.mkdirs()
            File(context.applicationInfo.sourceDir).copyTo(fixture, overwrite = true)
        }
        @AfterClass @JvmStatic fun removeFixture() { fixture.delete() }
    }
    @Before fun reset() {
        File(context.cacheDir, "oi-pending-installs").deleteRecursively()
        BridgeConfig(context).apply { allowDebug = false; allowChanges = false; allowDelete = false }
        BridgeStatus.running = false
        assertFalse("Emulator must have no active Shizuku", RootlessDebugger.status().getBoolean("rootlessReady"))
    }
    @After fun cleanup() {
        File(context.cacheDir, "oi-pending-installs").deleteRecursively()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        BridgeConfig(context).apply { allowDebug = false; allowChanges = false; allowDelete = false }
        BridgeStatus.running = false
    }
    @Test fun packageMetadataWorksUnderOrdinaryAppUid() {
        val result = RootlessDebugger.execute(context, "debug_run",
            JSONObject().put("action", "package").put("packageName", context.packageName), false)
        assertEquals("android_api", result.getString("executor"))
        assertNotEquals(2000, result.getInt("uid")); assertNotEquals(0, result.getInt("uid"))
        val data = JSONObject(result.getString("stdout"))
        assertEquals(context.packageName, data.getString("packageName"))
        assertEquals(64, data.getJSONArray("currentSignerSha256").getString(0).length)
        assertTrue(data.getJSONArray("permissions").length() > 0)
    }
    @Test fun unsupportedOperationCannotAcquireShellPrivileges() {
        val error = assertThrows(IOException::class.java) {
            RootlessDebugger.execute(context, "debug_run",
                JSONObject().put("action", "force_stop").put("packageName", context.packageName), false)
        }
        assertTrue(error.message.orEmpty().contains("ADB/Shizuku"))
        assertThrows(IOException::class.java) {
            RootlessDebugger.execute(context, "debug_run", JSONObject().put("action", "memory")
                .put("packageName", "com.example.other"), false)
        }
    }
    @Test fun remoteReadsStillRequireOwnerPermission() {
        assertThrows(IOException::class.java) {
            RootlessDebugger.execute(context, "debug_run", JSONObject().put("action", "battery"), true)
        }
    }
    @Test fun installationQueuesConfirmationAndProviderIsNarrow() {
        val response = RootlessDebugger.execute(context, "debug_run", JSONObject().put("action", "install")
            .put("value", fixture.path).put("packageName", context.packageName), false)
        assertEquals("requires_user_action", response.getString("status"))
        assertTrue(response.getBoolean("requiresUserAction")); assertFalse(response.getBoolean("completed"))
        assertTrue(response.isNull("exitCode"))
        assertEquals("pending_list", response.getString("delivery")) // notification permission revoked in CI
        val request = RootlessUserActions.store(context).load(response.getString("requestId"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.installer", request.apk!!)
        assertNotNull(context.contentResolver.openInputStream(uri)?.use { it.read() })
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.installer", fixture)
        }
        assertFalse(context.packageManager.getProviderInfo(ComponentName(context, InstallFileProvider::class.java), 0).exported)
        assertFalse(context.packageManager.getActivityInfo(ComponentName(context, UserActionActivity::class.java), 0).exported)
    }
    @Test fun permissionRevocationBlocksPreviouslyQueuedRemoteAction() {
        BridgeStatus.running = true
        val config = BridgeConfig(context).apply { allowDebug = true; allowChanges = true; allowDelete = true }
        RootlessUserActions.requireAuthorization(context, "install", true)
        config.allowChanges = false
        assertThrows(IOException::class.java) { RootlessUserActions.requireAuthorization(context, "install", true) }
        config.allowChanges = true; config.allowDelete = false
        assertThrows(IOException::class.java) { RootlessUserActions.requireAuthorization(context, "uninstall", true) }
        config.allowDelete = true; BridgeStatus.running = false
        assertThrows(IOException::class.java) { RootlessUserActions.requireAuthorization(context, "install", true) }
    }
    @Test fun packageMismatchDoesNotLeaveInstallableRequest() {
        assertThrows(IOException::class.java) {
            RootlessDebugger.execute(context, "debug_run", JSONObject().put("action", "install")
                .put("value", fixture.path).put("packageName", "com.example.different"), false)
        }
        assertTrue(RootlessUserActions.store(context).pendingIds().isEmpty())
    }
}

package no.nordicsemi.andorid.ble.test.regression

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Reproduces the state corruption that happens when `BluetoothDevice.connectGatt(...)` throws a
 * [SecurityException] because the `BLUETOOTH_CONNECT` runtime permission has been revoked.
 *
 * Real world scenario:
 *
 *  1. The user revokes the "Nearby devices" permission in the OS settings. Android kills the
 *     process.
 *  2. The process is restarted and the (singleton) BleManager tries to connect straight away.
 *  3. [no.nordicsemi.android.ble.BleManagerHandler] `internalConnect()` sets
 *     `connectionState = STATE_CONNECTING` and *then* calls `device.connectGatt(...)`, which
 *     throws a SecurityException. `Request.enqueue()` runs `nextRequest()` synchronously on the
 *     caller's thread, so the exception escapes out of `connect(device).suspend()` and nothing
 *     ever rolls the state back.
 *  4. The user grants the permission again. The singleton looks at `getConnectionState()`, sees
 *     STATE_CONNECTING and concludes a connection attempt is already in flight, so it never
 *     retries. And even when it does retry, `operationInProgress` is still `true`, so the new
 *     ConnectRequest is only queued and never executed.
 *
 * The three tests below assert the behavior we *want*. They all currently fail - that is the bug.
 */
@RunWith(RobolectricTestRunner::class)
// SDK 31+ is required: that is where BLUETOOTH_CONNECT started being enforced.
@Config(sdk = [34], application = Application::class)
class RevokedConnectPermissionTest {

    private lateinit var context: Application
    private lateinit var device: BluetoothDevice
    private lateinit var manager: TestBleManager

    /** The bare minimum BleManager, standing in for the app's singleton manager. */
    private class TestBleManager(context: Context) : BleManager(context) {
        override fun getMinLogPriority(): Int = Int.MAX_VALUE // silence the logs
        override fun log(priority: Int, message: String) = Unit
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean = true
        override fun initialize() = Unit
        override fun onServicesInvalidated() = Unit
    }

    @Before
    @Suppress("DEPRECATION") // BluetoothAdapter.getDefaultAdapter()
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The permission is revoked, exactly as it is after the user turned it off in Settings
        // and the process was restarted.
        context.denyBluetoothConnect()

        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        device = adapter.getRemoteDevice(DEVICE_ADDRESS)
        // Makes connectGatt(...) throw a SecurityException while BLUETOOTH_CONNECT is not granted,
        // which is what the framework does on a real device.
        shadowOf(device).setShouldThrowSecurityExceptions(true)

        manager = TestBleManager(context)
    }

    /**
     * After `connect().suspend()` blows up with a SecurityException, the manager must be back in
     * a usable, disconnected state.
     *
     * Currently fails: `connectionState` is left at `STATE_CONNECTING` (1).
     */
    @Test
    fun connectionStateIsResetWhenConnectGattThrowsSecurityException() {
        assertEquals(BluetoothGatt.STATE_DISCONNECTED, manager.connectionState)

        connectExpectingSecurityException()

        // Drain anything the manager posted to the main looper before inspecting the state.
        ShadowLooper.shadowMainLooper().idle()

        assertFalse("Manager reports itself as connected", manager.isConnected)
        assertEquals(
            "connectionState was not rolled back after connectGatt() threw",
            BluetoothGatt.STATE_DISCONNECTED,
            manager.connectionState,
        )
    }

    /**
     * The plain (non-coroutine) API must not leave the request queue blocked either.
     *
     * `Request.enqueue()` runs `nextRequest()` on the calling thread, so the SecurityException is
     * thrown straight at the caller, after `operationInProgress` has already been set to `true`
     * and before any callback could be notified. Nothing resets it, so every request enqueued
     * afterwards is only added to the queue and never executed.
     *
     * Note: `ConnectRequest.suspend()` accidentally recovers from this, because the coroutine is
     * cancelled and `TimeoutableRequest.cancel()` -> `cancelCurrent()` -> `nextRequest(force)`
     * clears the flag. The stale `connectionState` survives even that.
     */
    @Test
    fun requestQueueIsNotBlockedWhenConnectGattThrows() {
        try {
            manager.connect(device).enqueue()
            fail("Expected connectGatt(...) to throw a SecurityException")
        } catch (expected: SecurityException) {
            // Expected.
        }
        ShadowLooper.shadowMainLooper().idle()

        // The user goes back to Settings and grants the permission again.
        context.grantBluetoothConnect()

        manager.connect(device).enqueue()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(
            "The second ConnectRequest never reached connectGatt() - the queue is wedged",
            1,
            shadowOf(device).bluetoothGatts.size,
        )
    }

    /**
     * The scenario as the app actually experiences it: a singleton manager that only starts a
     * connection when it believes it is disconnected.
     *
     * Currently fails: after the permission is granted back the manager still reports
     * `STATE_CONNECTING`, so the app never retries and stays offline until the process is killed.
     */
    @Test
    fun singletonRetriesConnectingAfterThePermissionIsGrantedBack() {
        connectExpectingSecurityException()
        ShadowLooper.shadowMainLooper().idle()

        // The user goes back to Settings and grants the permission again.
        context.grantBluetoothConnect()

        // This guard is what a typical singleton manager does when the app comes to foreground.
        // Enqueued rather than suspended, because nothing simulates the GATT callbacks here and
        // the request would never complete.
        if (manager.connectionState == BluetoothGatt.STATE_DISCONNECTED) {
            manager.connect(device).enqueue()
        }
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(
            "The manager still reports STATE_CONNECTING, so the app never retried",
            1,
            shadowOf(device).bluetoothGatts.size,
        )
    }

    private fun connectExpectingSecurityException() {
        try {
            runBlocking { manager.connect(device).suspend() }
            fail("Expected connectGatt(...) to throw a SecurityException")
        } catch (expected: SecurityException) {
            // This is what the app sees on a real device after the permission was revoked.
        }
        // Nothing was created, the connection attempt never started.
        assertEquals(0, shadowOf(device).bluetoothGatts.size)
    }

    private fun Application.denyBluetoothConnect() =
        shadowOf(this).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private fun Application.grantBluetoothConnect() =
        shadowOf(this).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

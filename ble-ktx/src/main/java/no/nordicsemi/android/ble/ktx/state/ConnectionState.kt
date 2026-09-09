package no.nordicsemi.android.ble.ktx.state

import android.bluetooth.BluetoothProfile
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.state.ConnectionState.Disconnected.Reason
import no.nordicsemi.android.ble.observer.ConnectionObserver

@Suppress("unused")
sealed class ConnectionState {

    /** A connection to the device was initiated. */
    data object Connecting: ConnectionState()

    /** The device has connected and begun service discovery and initialization. */
    data object Initializing: ConnectionState()

    /** The initialization is complete, and the device is ready to use. */
    data object Ready: ConnectionState()

    /** The disconnection was initiated. */
    data object Disconnecting: ConnectionState()

    /** The device disconnected or failed to connect. */
    data class Disconnected(val reason: Reason): ConnectionState() {
        enum class Reason {
            SUCCESS,
            UNKNOWN,
            TERMINATE_LOCAL_HOST,
            TERMINATE_PEER_USER,
            LINK_LOSS,
            NOT_SUPPORTED,
            CANCELLED,
            TIMEOUT,
            PERMISSION_DENIED;

            companion object {
                internal fun parse(reason: Int): Reason = when (reason) {
                    ConnectionObserver.REASON_SUCCESS -> SUCCESS
                    ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> TERMINATE_LOCAL_HOST
                    ConnectionObserver.REASON_TERMINATE_PEER_USER -> TERMINATE_PEER_USER
                    ConnectionObserver.REASON_LINK_LOSS -> LINK_LOSS
                    ConnectionObserver.REASON_NOT_SUPPORTED -> NOT_SUPPORTED
                    ConnectionObserver.REASON_CANCELLED -> CANCELLED
                    ConnectionObserver.REASON_TIMEOUT -> TIMEOUT
                    ConnectionObserver.REASON_PERMISSION_DENIED -> PERMISSION_DENIED
                    else -> UNKNOWN
                }
            }
        }

        /** Whether the device, that was connected using auto connect, has disconnected. */
        val isLinkLoss: Boolean
            get() = reason == Reason.LINK_LOSS

        /** Whether at least one required service was not found. */
        val isNotSupported: Boolean
            get() = reason == Reason.NOT_SUPPORTED

        /** Whether the connection timed out. */
        val isTimeout: Boolean
            get() = reason == Reason.TIMEOUT

        /** Whether a permission required to use Bluetooth is missing. */
        val isPermissionDenied: Boolean
            get() = reason == Reason.PERMISSION_DENIED
    }

    /**
     * Whether the target device is connected, or not.
     */
    val isConnected: Boolean
        get() = this is Initializing || this is Ready

    /**
     * Whether the target device is ready to use.
     */
    val isReady: Boolean
        get() = this is Ready

    companion object {

        internal fun of(manager: BleManager) = when(manager.connectionState) {
            BluetoothProfile.STATE_CONNECTING -> Connecting
            BluetoothProfile.STATE_CONNECTED -> if (manager.isReady) Ready else Initializing
            BluetoothProfile.STATE_DISCONNECTING -> Disconnecting
            else -> Disconnected(Reason.UNKNOWN)
        }

    }

}
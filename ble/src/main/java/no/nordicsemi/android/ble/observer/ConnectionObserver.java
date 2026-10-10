/*
 * Copyright (c) 2020, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package no.nordicsemi.android.ble.observer;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;

import androidx.annotation.NonNull;
import no.nordicsemi.android.ble.ConnectRequest;
import no.nordicsemi.android.ble.annotation.DisconnectionReason;

/**
 * Additional callback for device disconnect with more information about state.
 */
public interface ConnectionObserver {
	/** The reason of disconnection is unknown. */
	int REASON_UNKNOWN = -1;
	/** The disconnection was initiated by the user. */
	int REASON_SUCCESS = 0;
	/** The local device initiated disconnection. */
	int REASON_TERMINATE_LOCAL_HOST = 1;
	/** The remote device initiated graceful disconnection. */
	int REASON_TERMINATE_PEER_USER = 2;
	/**
	 * This reason will only be reported when {@link ConnectRequest#useAutoConnect(boolean)}} was
	 * called with parameter set to true, and connection to the device was lost for any reason
	 * other than graceful disconnection initiated by the peer user.
	 * <p>
	 * Android will try to reconnect automatically.
	 */
	int REASON_LINK_LOSS = 3;
	/** The device does not have required services. */
	int REASON_NOT_SUPPORTED = 4;
	/** Connection attempt was cancelled. */
	int REASON_CANCELLED = 5;
	/**
	 * The connection timed out. The device might have reboot, is out of range, turned off
	 * or doesn't respond for another reason.
	 */
	int REASON_TIMEOUT = 10;
	/**
	 * The Android device is unable to reconnect to the peripheral because of internal failure.
	 * Most probably it cannot respond properly to PHY LE 2M update procedure, causing the
	 * remote device to terminate the connection.
	 * <p>
	 * Try disabling PHY LE 2M on the peripheral side, or update the Android version.
	 * If that's not possible, the connection to your device may not work on the given
	 * Android device at all. If the device is bonded, try removing bonding and connect,
	 * but this seems to fix the problem only before a new bond is created.
	 */
	int REASON_UNSUPPORTED_CONFIGURATION = 11;
	/**
	 * The connection could not be established, or was terminated, because the app is missing
	 * a permission required to use Bluetooth, that is {@link android.Manifest.permission#BLUETOOTH_CONNECT}
	 * on Android 12 (API 31) and newer.
	 * <p>
	 * This is reported when a call to the Android Bluetooth API threw a {@link SecurityException},
	 * which happens when the permission was never granted, or was revoked by the user in the
	 * system settings. Note, that revoking a permission kills the app process, so this is most
	 * likely to be seen when the app connects automatically after being restarted.
	 * <p>
	 * Request the missing permission and try connecting again.
	 */
	int REASON_PERMISSION_DENIED = 12;

	/**
	 * Called when the Android device started connecting to given device.
	 * The {@link #onDeviceConnected(BluetoothDevice)} will be called when the device is connected,
	 * or {@link #onDeviceFailedToConnect(BluetoothDevice, int)} if connection will fail.
	 *
	 * @param device the device that got connected.
	 */
	void onDeviceConnecting(@NonNull final BluetoothDevice device);

	/**
	 * Called when the device has been connected. This does not mean that the application may start
	 * communication. Service discovery will be handled automatically after this call.
	 *
	 * @param device the device that got connected.
	 */
	void onDeviceConnected(@NonNull final BluetoothDevice device);

	/**
	 * Called when the device failed to connect.
	 * @param device the device that failed to connect.
	 * @param reason the reason of failure.
	 */
	void onDeviceFailedToConnect(@NonNull final BluetoothDevice device,
								 @DisconnectionReason final int reason);

	/**
	 * Method called when all initialization requests has been completed.
	 *
	 * @param device the device that get ready.
	 */
	void onDeviceReady(@NonNull final BluetoothDevice device);

	/**
	 * Called when user initialized disconnection.
	 *
	 * @param device the device that gets disconnecting.
	 */
	void onDeviceDisconnecting(@NonNull final BluetoothDevice device);

	/**
	 * Called when the device has disconnected (when the callback returned
	 * {@link BluetoothGattCallback#onConnectionStateChange(BluetoothGatt, int, int)} with state
	 * DISCONNECTED).
	 *
	 * @param device the device that got disconnected.
	 * @param reason reason of the disconnect (mapped from the status code reported by the GATT
	 *               callback to the library specific status codes).
	 */
	void onDeviceDisconnected(@NonNull final BluetoothDevice device,
							  @DisconnectionReason final int reason);
}

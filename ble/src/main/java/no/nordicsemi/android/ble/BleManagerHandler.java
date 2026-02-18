package no.nordicsemi.android.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.IntRange;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;

import no.nordicsemi.android.ble.annotation.ConnectionPriority;
import no.nordicsemi.android.ble.annotation.ConnectionState;
import no.nordicsemi.android.ble.annotation.LogPriority;
import no.nordicsemi.android.ble.annotation.PhyMask;
import no.nordicsemi.android.ble.annotation.PhyOption;
import no.nordicsemi.android.ble.annotation.PhyValue;
import no.nordicsemi.android.ble.annotation.WriteType;
import no.nordicsemi.android.ble.callback.ConnectionParametersUpdatedCallback;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.callback.MtuCallback;
import no.nordicsemi.android.ble.callback.SuccessCallback;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.data.DataProvider;
import no.nordicsemi.android.ble.error.GattError;
import no.nordicsemi.android.ble.observer.BondingObserver;
import no.nordicsemi.android.ble.observer.ConnectionObserver;
import no.nordicsemi.android.ble.utils.ParserUtils;

@SuppressLint("MissingPermission")
@SuppressWarnings({"WeakerAccess", "unused", "deprecation", "DeprecatedIsStillUsed"})
abstract class BleManagerHandler extends RequestHandler {
	private final static String TAG = "BleManager";

	private final static String ERROR_CONNECTION_STATE_CHANGE = "Error on connection state change";
	private final static String ERROR_DISCOVERY_SERVICE = "Error on discovering services";
	private final static String ERROR_AUTH_ERROR_WHILE_BONDED = "Phone has lost bonding information";
	private final static String ERROR_READ_CHARACTERISTIC = "Error on reading characteristic";
	private final static String ERROR_WRITE_CHARACTERISTIC = "Error on writing characteristic";
	private final static String ERROR_READ_DESCRIPTOR = "Error on reading descriptor";
	private final static String ERROR_WRITE_DESCRIPTOR = "Error on writing descriptor";
	private final static String ERROR_MTU_REQUEST = "Error on mtu request";
	private final static String ERROR_CONNECTION_PRIORITY_REQUEST = "Error on connection priority request";
	private final static String ERROR_READ_RSSI = "Error on RSSI read";
	private final static String ERROR_READ_PHY = "Error on PHY read";
	private final static String ERROR_PHY_UPDATE = "Error on PHY update";
	private final static String ERROR_RELIABLE_WRITE = "Error on Execute Reliable Write";
	private final static String ERROR_NOTIFY = "Error on sending notification/indication";

	private final Object LOCK = new Object();
	private BluetoothDevice bluetoothDevice;
	/* package */ BluetoothGatt bluetoothGatt;
	private BleManager manager;
	private BleServerManager serverManager;
	private Handler handler;

	private final Deque<Request> taskQueue = new LinkedBlockingDeque<>();
	private Deque<Request> initQueue;
	private boolean initialization;

	/**
	 * A time after which receiving 133 or 147 error is considered a timeout, instead of a
	 * different reason.
	 * A {@link BluetoothDevice#connectGatt(Context, boolean, BluetoothGattCallback)} call will
	 * fail after 30 seconds if the device won't be found until then. Other errors happen much
	 * earlier. 20 sec should be OK here.
	 */
	private final static long CONNECTION_TIMEOUT_THRESHOLD = 20000; // ms
	/**
	 * Flag set when services were discovered.
	 */
	private boolean servicesDiscovered;
	/**
	 * Flag set to true when the {@link #isRequiredServiceSupported(BluetoothGatt)} returned false.
	 */
	private boolean deviceNotSupported;
	/**
	 * Flag set when service discovery was requested.
	 */
	private boolean serviceDiscoveryRequested;
	/**
	 * A timestamp when the last connection attempt was made. This is distinguish two situations
	 * when the 133 error happens during a connection attempt: a timeout (when ~30 sec passed since
	 * connection was requested), or an error (packet collision, packet missed, etc.)
	 */
	private long connectionTime;
	/**
	 * A temporary counter to prevent requesting service discovery for old connection.
	 */
	private int connectionCount = 0;
	/**
	 * Flag set to true when the device is connected.
	 */
	private boolean connected;
	/**
	 * Flag set to true when the initialization queue is complete.
	 */
	private boolean ready;
	/**
	 * A flag indicating that an operation is currently in progress.
	 */
	private boolean operationInProgress;
	/**
	 * This flag is set to false only when the {@link ConnectRequest#shouldAutoConnect()} method
	 * returns true and the device got disconnected without calling {@link BleManager#disconnect()}
	 * method. If {@link ConnectRequest#shouldAutoConnect()} returns false (default) this is always
	 * set to true.
	 */
	private boolean userDisconnected;
	/**
	 * Flag set to true when {@link ConnectRequest#shouldAutoConnect()} method returned true.
	 * The first connection attempt is done with <code>autoConnect</code> flag set to false
	 * (to make the first connection quick) but on connection lost the manager will call
	 * {@link BleManager#connect(BluetoothDevice)} again. This time this method will call
	 * {@link BluetoothGatt#connect()} which always uses <code>autoConnect</code> equal true.
	 */
	private boolean initialConnection;
	/**
	 * The connection state. One of:
	 * {@link BluetoothGatt#STATE_CONNECTING STATE_CONNECTING},
	 * {@link BluetoothGatt#STATE_CONNECTED STATE_CONNECTED},
	 * {@link BluetoothGatt#STATE_DISCONNECTING STATE_DISCONNECTING},
	 * {@link BluetoothGatt#STATE_DISCONNECTED STATE_DISCONNECTED}
	 */
	@ConnectionState
	private int connectionState = BluetoothGatt.STATE_DISCONNECTED;
	/**
	 * This flag is required to resume operations after the connection priority request was made.
	 * It is used only on Android Oreo and newer, as only there there is onConnectionUpdated
	 * callback. However, as this callback is triggered every time the connection parameters
	 * change, even when such request wasn't made, this flag ensures the nextRequest() method
	 * won't be called during another operation.
	 */
	private boolean connectionPriorityOperationInProgress = false;
	/**
	 * A flag indicating that Reliable Write is in progress.
	 */
	private boolean reliableWriteInProgress;
	/**
	 * The current MTU (Maximum Transfer Unit). The maximum number of bytes that can be sent in
	 * a single packet is MTU-3.
	 */
	@IntRange(from = 23, to = 515)
	private int mtu = 23;
	/**
	 * Current connection parameters. Those values are only available starting from Android Oreo.
	 */
	private int interval, latency, timeout;
	/**
	 * Samsung S8 with Android 9 fails to reconnect to devices requesting PHY LE 2M just after
	 * connection. Workaround would be to disable PHY LE 2M on the device side.
	 */
	private boolean earlyPhyLe2MRequest;
	/**
	 * Last received battery value or -1 if value wasn't received.
	 *
	 * @deprecated Battery value should be kept in the profile manager instead. See BatteryManager
	 * class in Android nRF Toolbox app.
	 */
	@IntRange(from = -1, to = 100)
	@Deprecated
	private int batteryValue = -1;
	/** Values of non-shared characteristics. Each connected device has its own copy of such. */
	private Map<BluetoothGattCharacteristic, byte[]> characteristicValues;
	/** Values of non-shared descriptors. Each connected device has its own copy of such. */
	private Map<BluetoothGattDescriptor, byte[]> descriptorValues;
	/**
	 * Temporary values of characteristic to support Reliable Write. The temp value will be
	 * set as valid when the write request is executed, or discarded when aborted.
	 */
	private Deque<Pair<Object /* BluetoothGattCharacteristic of BluetoothGattDescriptor> */, byte[]>> preparedValues;
	private int prepareError;
	/**
	 * The connect request. This is instantiated in {@link BleManager#connect(BluetoothDevice, int)}
	 * and nullified after the device is ready.
	 * <p>
	 * This request has a separate reference, as it is notified when the device becomes ready,
	 * after the initialization requests are done.
	 */
	private ConnectRequest connectRequest;
	/**
	 * Currently performed request or null in idle state.
	 */
	private Request request;
	/**
	 * Currently performer request set, or null if none.
	 */
	private RequestQueue requestQueue;
	/**
	 * A map of {@link ValueChangedCallback}s for handling notifications, indications and
	 * write callbacks to server characteristic and descriptors.
	 */
	@NonNull
	private final HashMap<Object, ValueChangedCallback> valueChangedCallbacks = new HashMap<>();
	/**
	 * A map of {@link DataProvider}s serving data to server characteristic and descriptors.
	 */
	@NonNull
	private final HashMap<Object, DataProvider> dataProviders = new HashMap<>();
	/**
	 * Connection priority callback, available from Android Oreo.
	 */
	@Nullable
	private ConnectionParametersUpdatedCallback connectionParametersUpdatedCallback;
	/**
	 * A special handler for Battery Level notifications.
	 */
	@Nullable
	@Deprecated
	private ValueChangedCallback batteryLevelNotificationCallback;
	/**
	 * An instance of a request that waits for a notification or an indication.
	 * There may be only a single instance of such request at a time as this is a blocking request.
	 */
	@Nullable
	private AwaitingRequest<?> awaitingRequest;

	private final BroadcastReceiver bluetoothStateBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
			final int previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);

			log(Log.DEBUG, () ->
				"[Broadcast] Action received: " + BluetoothAdapter.ACTION_STATE_CHANGED +
						  ", state changed to " + state2String(state)
			);

			switch (state) {
				case BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
					if (previousState != BluetoothAdapter.STATE_TURNING_OFF
							&& previousState != BluetoothAdapter.STATE_OFF) {
						// No more calls are possible
						operationInProgress = true;
						emptyTasks(FailCallback.REASON_BLUETOOTH_DISABLED);
						ready = false;

						final BluetoothDevice device = bluetoothDevice;
						if (device != null) {
							// Signal the current request, if any
							if (request != null && request.type != Request.Type.DISCONNECT) {
								request.notifyFail(device, FailCallback.REASON_BLUETOOTH_DISABLED);
								request = null;
							}
							if (awaitingRequest != null) {
								awaitingRequest.notifyFail(device, FailCallback.REASON_BLUETOOTH_DISABLED);
								awaitingRequest = null;
							}
							if (connectRequest != null) {
								connectRequest.notifyFail(device, FailCallback.REASON_BLUETOOTH_DISABLED);
								connectRequest = null;
							}
						}

						// The connection is killed by the system, no need to disconnect gently.
						userDisconnected = true;
						// Allow new requests when Bluetooth is enabled again. close() doesn't do it.
						// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/25
						// and: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/41
						operationInProgress = false;
						// This will call close()
						if (device != null) {
							notifyDeviceDisconnected(device, ConnectionObserver.REASON_TERMINATE_LOCAL_HOST);
						}
						connected = false;
						connectionState = BluetoothGatt.STATE_DISCONNECTED;
					} else {
						// Calling close() will prevent the STATE_OFF event from being logged
						// (this receiver will be unregistered). But it doesn't matter.
						close();
					}
				}
			}
		}

		private String state2String(final int state) {
			return switch (state) {
				case BluetoothAdapter.STATE_TURNING_ON -> "TURNING ON";
				case BluetoothAdapter.STATE_ON -> "ON";
				case BluetoothAdapter.STATE_TURNING_OFF -> "TURNING OFF";
				case BluetoothAdapter.STATE_OFF -> "OFF";
				default -> "UNKNOWN (" + state + ")";
			};
		}
	};

	private final BroadcastReceiver mBondingBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
			final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
			final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

			// Skip other devices.
			if (bluetoothDevice == null || device == null
					|| !device.getAddress().equals(bluetoothDevice.getAddress()))
				return;


			log(Log.DEBUG, () ->
					"[Broadcast] Action received: " + BluetoothDevice.ACTION_BOND_STATE_CHANGED +
						", bond state changed to: " + ParserUtils.bondStateToString(bondState) +
						" (" + bondState + ")");

			switch (bondState) {
				case BluetoothDevice.BOND_NONE -> {
					if (previousBondState == BluetoothDevice.BOND_BONDING) {
						postCallback(c -> c.onBondingFailed(device));
						postBondingStateChange(o -> o.onBondingFailed(device));
						log(Log.WARN, () -> "Bonding failed");
						if (request != null && (
								request.type == Request.Type.CREATE_BOND ||
								request.type == Request.Type.ENSURE_BOND ||
								// The following requests may trigger bonding.
								request.type == Request.Type.WRITE ||
								request.type == Request.Type.WRITE_DESCRIPTOR ||
								request.type == Request.Type.READ ||
								request.type == Request.Type.READ_DESCRIPTOR)) {
							request.notifyFail(device, FailCallback.REASON_REQUEST_FAILED);
							request = null;
						}
						// If the device started to pair just after the connection was
						// established the services were not discovered. We may try to discover services
						// despite the fail bonding process.
						// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/335
						if (!servicesDiscovered && !serviceDiscoveryRequested) {
							final BluetoothGatt bluetoothGatt = BleManagerHandler.this.bluetoothGatt;
							if (bluetoothGatt != null) {
								serviceDiscoveryRequested = true;
								log(Log.VERBOSE, () -> "Discovering services...");
								log(Log.DEBUG, () -> "gatt.discoverServices()");
								bluetoothGatt.discoverServices();
							}
							return;
						}
					} else if (previousBondState == BluetoothDevice.BOND_BONDED) {
						// Removing the bond will cause disconnection.
						userDisconnected = true;

						if (request != null && request.type == Request.Type.REMOVE_BOND) {
							// The device has already disconnected by now.
							log(Log.INFO, () -> "Bond information removed");
							request.notifySuccess(device);
							request = null;
						}
						// When the bond information has been removed (either with Remove Bond request
						// or in Android Settings), the BluetoothGatt object should be closed, so
						// the library won't reconnect to the device automatically.
						// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/157
						if (!isConnected())
							close();

						// Due to https://github.com/NordicSemiconductor/Android-BLE-Library/issues/363,
						// the call to close() here has been placed behind an if statement.
						// Instead, the 'userDisconnected' flag is set to true (here and in
						// 'internalRemoveBond()'.
						// When the device gets disconnected, close() method will be called from
						// 'notifyDeviceDisconnected(...)'.
					}
				}
				case BluetoothDevice.BOND_BONDING -> {
					postCallback(c -> c.onBondingRequired(device));
					postBondingStateChange(o -> o.onBondingRequired(device));
					return;
				}
				case BluetoothDevice.BOND_BONDED -> {
					log(Log.INFO, () -> "Device bonded");
					postCallback(c -> c.onBonded(device));
					postBondingStateChange(o -> o.onBonded(device));
					if (request != null && (request.type == Request.Type.CREATE_BOND || request.type == Request.Type.ENSURE_BOND)) {
						request.notifySuccess(device);
						request = null;
						break;
					}
					// If the device started to pair just after the connection was
					// established the services were not discovered.
					if (!servicesDiscovered && !serviceDiscoveryRequested) {
						final BluetoothGatt bluetoothGatt = BleManagerHandler.this.bluetoothGatt;
						if (bluetoothGatt != null) {
							serviceDiscoveryRequested = true;
							log(Log.VERBOSE, () -> "Discovering services...");
							log(Log.DEBUG, () -> "gatt.discoverServices()");
							bluetoothGatt.discoverServices();
						}
						return;
					}
					// On older Android versions, after executing a command on secured attribute
					// of a device that is not bonded, let's say a write characteristic operation,
					// the system will start bonding. The BOND_BONDING and BOND_BONDED events will
					// be received, but the command will not be repeated automatically.
					//
					// Test results:
					// Devices that require repeating the last task:
					// - Nexus 4 with Android 5.1.1
					// - Samsung S6 with 5.0.1
					// - Samsung S8 with Android 7.0
					// - Nexus 9 with Android 7.1.1
					// Devices that repeat the request automatically:
					// - Pixel 2 with Android 8.1.0
					// - Samsung S8 with Android 8.0.0
					//
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
						if (request != null) {
							// Repeat the last command in that case.
							enqueueFirst(request);
							break;
						}
					}
					// No need to repeat the request.
					return;
				}
			}
			nextRequest(true);
		}
	};

	/**
	 * Initializes the object.
	 *
	 * @param manager The BLE manager.
	 */
	void init(@NonNull final BleManager manager, @NonNull final Handler handler) {
		this.manager = manager;
		this.handler = handler;
	}

	/**
	 * Binds the server with the BLE manager handler. Call with null to unbind the server.
	 *
	 * @param server the server to bind; null to unbind the server.
	 */
	void useServer(@Nullable final BleServerManager server) {
		this.serverManager = server;
	}

	/**
	 * If doing a server-only connection, use this instead of {@link BleManager#connect(BluetoothDevice)}
	 *  inside of your {@link no.nordicsemi.android.ble.observer.ServerObserver#onDeviceConnectedToServer(BluetoothDevice)}
	 *  handler.
	 */
	void attachClientConnection(BluetoothDevice clientDevice) {
		final BleServerManager serverManager = this.serverManager;
		if (serverManager == null) {
			log(Log.ERROR, () -> "Server not bound to the manager");
			return;
		}

		// should either setup as server only (this method) or two way connection (connect method), not both
		if (this.bluetoothDevice != null) {
			log(Log.ERROR, () -> "attachClientConnection called on existing connection, call ignored");
		} else {
			this.bluetoothDevice = clientDevice;
			this.connectionState = BluetoothProfile.STATE_CONNECTED;
			this.connected = true;
			// If using two way connection via connect(), the server attributes would be setup after discovery.
			// Since we are opting to use server only connection, we must do this here instead.
			initializeServerAttributes();
			serverManager.useConnection(clientDevice, false);
			// the other path also calls this part of the callbacks
			manager.initialize();
		}
	}

	private void initializeServerAttributes() {
		final BleServerManager serverManager = this.serverManager;
		if (serverManager != null) {
			final BluetoothGattServer server = serverManager.getServer();
			if (server != null) {
				for (final BluetoothGattService service: server.getServices()) {
					for (final BluetoothGattCharacteristic characteristic: service.getCharacteristics()) {
						if (!serverManager.isShared(characteristic)) {
							if (characteristicValues == null)
								characteristicValues = new HashMap<>();
							characteristicValues.put(characteristic, characteristic.getValue());
						}
						for (final BluetoothGattDescriptor descriptor: characteristic.getDescriptors()) {
							if (!serverManager.isShared(descriptor)) {
								if (descriptorValues == null)
									descriptorValues = new HashMap<>();
								descriptorValues.put(descriptor, descriptor.getValue());
							}
						}
					}
				}
				manager.onServerReady(server);
			}
		}
	}

	/**
	 * Closes and releases resources.
	 */
	void close() {
		try {
			final Context context = manager.getContext();
			context.unregisterReceiver(bluetoothStateBroadcastReceiver);
			context.unregisterReceiver(mBondingBroadcastReceiver);
		} catch (final Exception e) {
			// the receiver must have been not registered or unregistered before.
		}
		synchronized (LOCK) {
			final boolean wasConnected = connected;
			final BluetoothDevice oldBluetoothDevice = bluetoothDevice;
			if (bluetoothGatt != null) {
				if (manager.shouldClearCacheWhenDisconnected()) {
					if (internalRefreshDeviceCache()) {
						log(Log.INFO, () -> "Cache refreshed");
					} else {
						log(Log.WARN, () -> "Refreshing failed");
					}
				}
				log(Log.DEBUG, () -> "gatt.close()");
				try {
					bluetoothGatt.close();
				} catch (final Throwable t) {
					// ignore
				}
				bluetoothGatt = null;
			}
			reliableWriteInProgress = false;
			initialConnection = false;
			// close() is called in notifyDeviceDisconnected, which may enqueue new requests.
			// Setting this flag to false would allow to enqueue a new request before the
			// current one ends processing. The following line should not be uncommented.
			// mGattCallback.operationInProgress = false;
			emptyTasks(FailCallback.REASON_DEVICE_DISCONNECTED);
			initialization = false;
			bluetoothDevice = null;
			connected = false;
			connectionState = BluetoothProfile.STATE_DISCONNECTED;
			mtu = 23;
			interval = latency = timeout = 0;
			if (wasConnected && oldBluetoothDevice != null) {
				postCallback(c -> c.onDeviceDisconnected(oldBluetoothDevice));
				postConnectionStateChange(o -> o.onDeviceDisconnected(oldBluetoothDevice, ConnectionObserver.REASON_SUCCESS));
			}
		}
	}

	/**
	 * This method clears the task queues and notifies removed requests of cancellation.
	 * @param status the reason of cancellation.
	 */
	private void emptyTasks(final int status) {
		final BluetoothDevice oldBluetoothDevice = bluetoothDevice;
		if (initQueue != null) {
			for (final Request task : initQueue) {
				if (oldBluetoothDevice != null)
					task.notifyFail(oldBluetoothDevice, status);
				else
					task.notifyInvalidRequest();
			}
			initQueue = null;
		}
		for (final Request task : taskQueue) {
			if (oldBluetoothDevice != null) {
				if (status == FailCallback.REASON_BLUETOOTH_DISABLED ||
						task.characteristic != null ||
						task.descriptor != null) {
					task.notifyFail(oldBluetoothDevice, status);
				} else {
					task.notifyFail(oldBluetoothDevice, FailCallback.REASON_CANCELLED);
				}
			} else {
				task.notifyInvalidRequest();
			}
		}
		taskQueue.clear();
	}

	public BluetoothDevice getBluetoothDevice() {
		return bluetoothDevice;
	}

	/**
	 * Returns the value of the server characteristic. For characteristics that are not shared,
	 * the value may be different for each connected device.
	 *
	 * @param serverCharacteristic The characteristic to get value of.
	 * @return The value.
	 */
	@Nullable
	public final byte[] getCharacteristicValue(@NonNull final BluetoothGattCharacteristic serverCharacteristic) {
		if (characteristicValues != null && characteristicValues.containsKey(serverCharacteristic))
			return characteristicValues.get(serverCharacteristic);
		return serverCharacteristic.getValue();
	}

	/**
	 * Returns the value of the server descriptor. For descriptor that are not shared,
	 * the value may be different for each connected device.
	 *
	 * @param serverDescriptor The descriptor to get value of.
	 * @return The value.
	 */
	@Nullable
	public final byte[] getDescriptorValue(@NonNull final BluetoothGattDescriptor serverDescriptor) {
		if (descriptorValues != null && descriptorValues.containsKey(serverDescriptor))
			return descriptorValues.get(serverDescriptor);
		return serverDescriptor.getValue();
	}

	// Requests implementation

	private boolean internalConnect(@NonNull final BluetoothDevice device,
									@Nullable final ConnectRequest connectRequest) {
		final boolean bluetoothEnabled = BluetoothAdapter.getDefaultAdapter().isEnabled();
		if (connected || !bluetoothEnabled) {
			final BluetoothDevice currentDevice = bluetoothDevice;
			if (bluetoothEnabled && currentDevice != null && currentDevice.equals(device)) {
				if (this.connectRequest != null) {
					this.connectRequest.notifySuccess(device);
				}
			} else {
				// We can't return false here, as the request would be notified with
				// bluetoothDevice instance instead, and that may be null or a wrong device.
				if (this.connectRequest != null) {
					this.connectRequest.notifyFail(device,
							bluetoothEnabled ?
									FailCallback.REASON_REQUEST_FAILED :
									FailCallback.REASON_BLUETOOTH_DISABLED);
				} // else, the request was already failed by the Bluetooth state receiver
			}
			this.connectRequest = null;
			nextRequest(true);
			return true;
		}

		final Context context = manager.getContext();
		synchronized (LOCK) {
			if (bluetoothGatt != null) {
				// There are 2 ways of reconnecting to the same device:
				// 1. Reusing the same BluetoothGatt object and calling connect() - this will force
				//    the autoConnect flag to true
				// 2. Closing it and reopening a new instance of BluetoothGatt object.
				// The gatt.close() is an asynchronous method. It requires some time before it's
				// finished and device.connectGatt(...) can't be called immediately or service
				// discovery may never finish on some older devices (Nexus 4, Android 5.0.1).
				// If shouldAutoConnect() method returned false we can't call gatt.connect() and
				// have to close gatt and open it again.
				if (!initialConnection) {
					log(Log.DEBUG, () -> "gatt.close()");
					try {
						bluetoothGatt.close();
					} catch (final Throwable t) {
						// ignore
					}
					bluetoothGatt = null;
					try {
						log(Log.DEBUG, () -> "wait(200)");
						Thread.sleep(200); // Is 200 ms enough?
						// If the connection was closed or cancelled during this 200 ms, assume success.
						if (bluetoothGatt != null || (connectRequest != null && connectRequest.finished))
							return true;
					} catch (final InterruptedException e) {
						// Ignore
					}
				} else {
					initialConnection = false;
					connectionTime = 0L; // no timeout possible when autoConnect used
					connectionState = BluetoothGatt.STATE_CONNECTING;
					log(Log.VERBOSE, () -> "Connecting...");
					postCallback(c -> c.onDeviceConnecting(device));
					postConnectionStateChange(o -> o.onDeviceConnecting(device));
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
						int preferredPhy = PhyRequest.PHY_LE_1M_MASK;
						if(connectRequest != null) {
							preferredPhy = connectRequest.getPreferredPhy();
						}
						final int finalPreferredPhy = preferredPhy;
						var gatt = bluetoothGatt;
						log(Log.DEBUG, () -> "gatt.close()");
						gatt.close();
						log(Log.DEBUG, () ->
								"gatt = device.connectGatt(autoConnect = true, TRANSPORT_LE, "
										+ ParserUtils.phyMaskToString(finalPreferredPhy) + ")");
						bluetoothGatt = device.connectGatt(context, true, gattCallback,
								BluetoothDevice.TRANSPORT_LE, preferredPhy, handler);
					} else {
						// Instead, the gatt.connect() method will be used to reconnect to the same device.
						// This method forces autoConnect = true (except on Android 14) even if the gatt was
						// created with this flag set to false.
						log(Log.DEBUG, () -> "gatt.connect()");
						bluetoothGatt.connect();
					}
					return true;
				}
			} else {
				if (connectRequest != null) {
					// Register bonding broadcast receiver
					ContextCompat.registerReceiver(context, bluetoothStateBroadcastReceiver,
							new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
					ContextCompat.registerReceiver(context, mBondingBroadcastReceiver,
							new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
				}
			}
		}

		// This should not happen in normal circumstances, but may, when Bluetooth was turned off
		// when retrying to create a connection.
		if (connectRequest == null)
			return false;

		final boolean shouldAutoConnect = connectRequest.shouldAutoConnect();
		final boolean autoConnect;
		if (shouldAutoConnect) {
			// If shouldAutoConnectCreateDirectConnectionFirst() returns true, the first connection
			// will always be done with autoConnect = false to make the connection quick.
			// If the shouldAutoConnect() method returned true, the manager will automatically try
			// to reconnect to this device on link loss.
			initialConnection = connectRequest.shouldAutoConnectCreateDirectConnectionFirst();
			autoConnect = !initialConnection;
		} else {
			autoConnect = false;
		}
		// We will receive Link Loss events only when the device is connected with autoConnect=true.
		userDisconnected = !shouldAutoConnect;

		bluetoothDevice = device;
		if (!autoConnect) {
			log(Log.VERBOSE, () -> connectRequest.isFirstAttempt() ? "Connecting..." : "Retrying...");
			connectionState = BluetoothGatt.STATE_CONNECTING;
			postCallback(c -> c.onDeviceConnecting(device));
			postConnectionStateChange(o -> o.onDeviceConnecting(device));
		}
		connectionTime = SystemClock.elapsedRealtime();
		earlyPhyLe2MRequest = false;
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
			// connectRequest will never be null here.
			final int preferredPhy = connectRequest.getPreferredPhy();
			log(Log.DEBUG, () ->
					"gatt = device.connectGatt(autoConnect = " + autoConnect + ", TRANSPORT_LE, "
							+ ParserUtils.phyMaskToString(preferredPhy) + ")");

			bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback,
					BluetoothDevice.TRANSPORT_LE, preferredPhy, handler);
		} else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
			// connectRequest will never be null here.
			final int preferredPhy = connectRequest.getPreferredPhy();
			log(Log.DEBUG, () ->
					"gatt = device.connectGatt(autoConnect = " + autoConnect + ", TRANSPORT_LE, "
							+ ParserUtils.phyMaskToString(preferredPhy) + ")");
			// A variant of connectGatt with Handled can't be used here.
			// Check https://github.com/NordicSemiconductor/Android-BLE-Library/issues/54
			// This bug specifically occurs in SDK 26 and is fixed in SDK 27
			bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback,
					BluetoothDevice.TRANSPORT_LE, preferredPhy/*, handler*/);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			log(Log.DEBUG, () -> "gatt = device.connectGatt(autoConnect = " + autoConnect + ", TRANSPORT_LE)");
			bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback,
					BluetoothDevice.TRANSPORT_LE);
		} else {
			log(Log.DEBUG, () -> "gatt = device.connectGatt(autoConnect = " + autoConnect + ")");
			bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback);
		}

		if (autoConnect && this.connectRequest != null) {
			this.connectRequest.notifySuccess(device);
			this.connectRequest = null;
		}
		return true;
	}

	private void internalDisconnect(final int reason) {
		userDisconnected = true;
		initialConnection = false;
		ready = false;

		final BleServerManager serverManager = this.serverManager;
		final BluetoothDevice bluetoothDevice = this.bluetoothDevice;
		if (serverManager != null && bluetoothDevice != null) {
			log(Log.VERBOSE, () -> "Cancelling server connection...");
			log(Log.DEBUG, () -> "server.cancelConnection(device)");
			serverManager.cancelConnection(bluetoothDevice);
		}

		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt != null) {
			final boolean wasConnected = connected;
			connectionState = BluetoothGatt.STATE_DISCONNECTING;
			log(Log.VERBOSE, () -> wasConnected ? "Disconnecting..." : "Cancelling connection...");
			final BluetoothDevice device = gatt.getDevice();
			if (wasConnected) {
				postCallback(c -> c.onDeviceDisconnecting(device));
				postConnectionStateChange(o -> o.onDeviceDisconnecting(device));
			}
			log(Log.DEBUG, () -> "gatt.disconnect()");
			try {
				gatt.disconnect();
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
			}
			if (wasConnected)
				return;

			// If the device wasn't connected, there will be no callback after calling
			// gatt.disconnect(), the connection attempt will be stopped.
			connectionState = BluetoothGatt.STATE_DISCONNECTED;
			log(Log.INFO, () -> "Disconnected");
			close();
			postCallback(c -> c.onDeviceDisconnected(device));
			postConnectionStateChange(o -> o.onDeviceDisconnected(device, reason));
		}
		// request may be of type DISCONNECT or CONNECT (timeout).
		// For the latter, it has already been notified with REASON_TIMEOUT.
		final Request r = request;
		if (r != null && r.type == Request.Type.DISCONNECT) {
			if (bluetoothDevice != null || gatt != null)
				r.notifySuccess(bluetoothDevice != null ? bluetoothDevice : gatt.getDevice());
			else
				r.notifyInvalidRequest();
		}
		nextRequest(true);
	}

	@RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
	private boolean internalCreateBond(final boolean ensure) {
		final BluetoothDevice device = bluetoothDevice;
		if (device == null)
			return false;

		if (ensure)
			log(Log.VERBOSE, () -> "Ensuring bonding...");
		else
			log(Log.VERBOSE, () -> "Starting bonding...");

		// Warning: The check below only ensures that the bond information is present on the
		//          Android side, not on both. If the bond information has been remove from the
		//          peripheral side, the code below will notify bonding as success, but in fact the
		//          link will not be encrypted! Currently there is no way to ensure that the link
		//          is secure.
		//          Android, despite reporting bond state as BONDED, creates an unencrypted link
		//          and does not report this as a problem. Calling createBond() on a valid,
		//          encrypted link, to ensure that the link is encrypted, returns false (error).
		//          The same result is returned if only the Android side has bond information,
		//          making both cases indistinguishable.
		//
		// Solution: To make sure that sensitive data are sent only on encrypted link make sure
		//           the characteristic/descriptor is protected and reading/writing to it will
		//           initiate bonding request. To make sure link is encrypted, use ensureBond()
		//           method in BleManager, which will remove old and recreate bonding until this
		//           Android bug is fixed.
		if (!ensure && device.getBondState() == BluetoothDevice.BOND_BONDED) {
			log(Log.WARN, () -> "Bond information present on client, skipping bonding");
			request.notifySuccess(device);
			nextRequest(true);
			return true;
		}
		final boolean result = createBond(device);
		if (ensure && !result) {
			// This will be added as a second.
			// Copy all callbacks from the current request and clear them in the original.
			final Request bond = Request.createBond().setRequestHandler(this);
			// bond.beforeCallback was already fired.
			bond.successCallback = request.successCallback;
			bond.invalidRequestCallback = request.invalidRequestCallback;
			bond.failCallback = request.failCallback;
			bond.internalSuccessCallback = request.internalSuccessCallback;
			bond.internalFailCallback = request.internalFailCallback;
			request.successCallback = null;
			request.invalidRequestCallback = null;
			request.failCallback = null;
			request.internalSuccessCallback = null;
			request.internalFailCallback = null;
			enqueueFirst(bond);
			// This will be added as first.
			enqueueFirst(Request.removeBond().setRequestHandler(this));
			nextRequest(true);
			return true;
		}
		return result;
	}

	@RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
	private boolean createBond(@NonNull final BluetoothDevice device) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			log(Log.DEBUG, () -> "device.createBond()");
			return device.createBond();
		} else {
			/*
			 * There is a createBond() method in BluetoothDevice class but for now it's hidden.
			 * We will call it using reflections. It has been revealed in KitKat (Api19).
			 */
			try {
				final Method createBond = device.getClass().getMethod("createBond");
				log(Log.DEBUG, () -> "device.createBond() (hidden)");
				return createBond.invoke(device) == Boolean.TRUE;
			} catch (final Exception e) {
				Log.w(TAG, "An exception occurred while creating bond", e);
				return false;
			}
		}
	}

	@RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
	private boolean internalRemoveBond() {
		final BluetoothDevice device = bluetoothDevice;
		if (device == null)
			return false;

		log(Log.VERBOSE, () -> "Removing bond information...");

		if (device.getBondState() == BluetoothDevice.BOND_NONE) {
			log(Log.WARN, () -> "Device is not bonded");
			request.notifySuccess(device);
			nextRequest(true);
			return true;
		}

		/*
		 * There is a removeBond() method in BluetoothDevice class but for now it's hidden.
		 * We will call it using reflections.
		 */
		try {
			//noinspection JavaReflectionMemberAccess
			final Method removeBond = device.getClass().getMethod("removeBond");
			log(Log.DEBUG, () -> "device.removeBond() (hidden)");
			// Removing a call will initiate disconnection.
			userDisconnected = true;
			return removeBond.invoke(device) == Boolean.TRUE;
		} catch (final Exception e) {
			Log.w(TAG, "An exception occurred while removing bond", e);
		}
		return false;
	}

	/**
	 * When the device is bonded and has the Generic Attribute service and the Service Changed
	 * characteristic this method enables indications on this characteristic.
	 * In case one of the requirements is not fulfilled this method returns <code>false</code>.
	 *
	 * @return <code>true</code> when the request has been sent, <code>false</code> when the device
	 * is not bonded, does not have the Generic Attribute service, the GA service does not have
	 * the Service Changed characteristic or this characteristic does not have the CCCD.
	 */
	private boolean ensureServiceChangedEnabled() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		// The Service Changed indications have sense only on bonded devices.
		final BluetoothDevice device = gatt.getDevice();
		if (device.getBondState() != BluetoothDevice.BOND_BONDED)
			return false;

		final BluetoothGattService gaService = gatt.getService(BleManager.GENERIC_ATTRIBUTE_SERVICE);
		if (gaService == null)
			return false;

		final BluetoothGattCharacteristic scCharacteristic =
				gaService.getCharacteristic(BleManager.SERVICE_CHANGED_CHARACTERISTIC);
		if (scCharacteristic == null)
			return false;

		log(Log.INFO, () -> "Service Changed characteristic found on a bonded device");
		return internalEnableIndications(scCharacteristic);
	}

	private boolean internalEnableNotifications(@Nullable final BluetoothGattCharacteristic characteristic) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || characteristic == null || !connected)
			return false;

		final BluetoothGattDescriptor descriptor = getCccd(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
		if (descriptor != null) {
			log(Log.DEBUG, () -> "gatt.setCharacteristicNotification(" + characteristic.getUuid() + ", true)");
			try {
				gatt.setCharacteristicNotification(characteristic, true);
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}

			log(Log.VERBOSE, () -> "Enabling notifications for " + characteristic.getUuid());
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					log(Log.DEBUG, () ->
							"gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb, value=0x01-00)");
					return gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
				} else {
					log(Log.DEBUG, () -> "descriptor.setValue(0x01-00)");
					descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
					log(Log.DEBUG, () -> "gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb)");
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
						return gatt.writeDescriptor(descriptor);
					} else {
						return internalWriteDescriptorWorkaround(descriptor);
					}
				}
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}
		}
		return false;
	}

	private boolean internalDisableNotifications(@Nullable final BluetoothGattCharacteristic characteristic) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || characteristic == null || !connected)
			return false;

		final BluetoothGattDescriptor descriptor = getCccd(characteristic,
				BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE);
		if (descriptor != null) {
			log(Log.DEBUG, () -> "gatt.setCharacteristicNotification(" + characteristic.getUuid() + ", false)");
			try {
				gatt.setCharacteristicNotification(characteristic, false);
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}

			log(Log.VERBOSE, () -> "Disabling notifications and indications for " + characteristic.getUuid());
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					log(Log.DEBUG, () ->
							"gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb, value=0x00-00)");
					return gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
				} else {
					log(Log.DEBUG, () -> "descriptor.setValue(0x00-00)");
					descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
					log(Log.DEBUG, () -> "gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb)");
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
						return gatt.writeDescriptor(descriptor);
					} else {
						return internalWriteDescriptorWorkaround(descriptor);
					}
				}
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}
		}
		return false;
	}

	private boolean internalEnableIndications(@Nullable final BluetoothGattCharacteristic characteristic) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || characteristic == null || !connected)
			return false;

		final BluetoothGattDescriptor descriptor = getCccd(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE);
		if (descriptor != null) {
			log(Log.DEBUG, () -> "gatt.setCharacteristicNotification(" + characteristic.getUuid() + ", true)");
			try {
				gatt.setCharacteristicNotification(characteristic, true);
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}

			log(Log.VERBOSE, () -> "Enabling indications for " + characteristic.getUuid());
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					log(Log.DEBUG, () ->
							"gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb, value=0x02-00)");
					return gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
				} else {
					log(Log.DEBUG, () -> "descriptor.setValue(0x02-00)");
					descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
					log(Log.DEBUG, () -> "gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb)");
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
						return gatt.writeDescriptor(descriptor);
					} else {
						return internalWriteDescriptorWorkaround(descriptor);
					}
				}
			} catch (final SecurityException e) {
				log(Log.ERROR, e::getLocalizedMessage);
				return false;
			}
		}
		return false;
	}

	private boolean internalDisableIndications(@Nullable final BluetoothGattCharacteristic characteristic) {
		// This writes exactly the same settings so do not duplicate code.
		return internalDisableNotifications(characteristic);
	}

	private boolean internalSendNotification(@Nullable final BluetoothGattCharacteristic serverCharacteristic,
											 final boolean confirm, @Nullable final byte[] data) {
		if (serverManager == null || serverManager.getServer() == null || serverCharacteristic == null)
			return false;
		final int requiredProperty = confirm ? BluetoothGattCharacteristic.PROPERTY_INDICATE : BluetoothGattCharacteristic.PROPERTY_NOTIFY;
		if ((serverCharacteristic.getProperties() & requiredProperty) == 0)
			return false;
		final BluetoothGattDescriptor cccd = serverCharacteristic.getDescriptor(BleManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
		if (cccd == null)
			return false;
		// If notifications/indications were enabled, send the notification/indication.
		final byte[] value = descriptorValues != null && descriptorValues.containsKey(cccd) ? descriptorValues.get(cccd) : cccd.getValue();
		if (value != null && value.length == 2 && value[0] != 0) {
			log(Log.VERBOSE, () -> "[Server] Sending " + (confirm ? "indication" : "notification") + " to " + serverCharacteristic.getUuid());
			boolean result;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				log(Log.DEBUG, () -> "[Server] gattServer.notifyCharacteristicChanged(" + serverCharacteristic.getUuid() +
						", confirm=" + confirm +
						", value=" + ParserUtils.parseDebug(data) + ")");
				return serverManager.getServer().notifyCharacteristicChanged(bluetoothDevice, serverCharacteristic, confirm, data != null ? data : new byte[0])
						== BluetoothStatusCodes.SUCCESS;
			} else {
				log(Log.DEBUG, () -> "[Server] characteristic.setValue(" + ParserUtils.parseDebug(data) + ")");
				serverCharacteristic.setValue(data);
				log(Log.DEBUG, () -> "[Server] gattServer.notifyCharacteristicChanged(" + serverCharacteristic.getUuid() + ", confirm=" + confirm + ")");
				result = serverManager.getServer().notifyCharacteristicChanged(bluetoothDevice, serverCharacteristic, confirm);

				// The onNotificationSent callback is not called before Android Lollipop.
				if (result && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
					notifyNotificationSent(bluetoothDevice);
					nextRequest(true);
				}
			}
			return result;
		} else {
			notifyNotificationsDisabled(bluetoothDevice);
		}
		// Otherwise, assume the data was sent. The remote side has not registered for them.
		nextRequest(true);
		return true;
	}

	/**
	 * Returns the Client Characteristic Config Descriptor if the characteristic has the
	 * required property. It may return null if the CCCD is not there.
	 *
	 * @param characteristic   the characteristic to look the CCCD in.
	 * @param requiredProperty the required property: {@link BluetoothGattCharacteristic#PROPERTY_NOTIFY}
	 *                         or {@link BluetoothGattCharacteristic#PROPERTY_INDICATE}.
	 * @return The CCC descriptor or null if characteristic is null, if it doesn't have the
	 * required property, or if the CCCD is missing.
	 */
	private static BluetoothGattDescriptor getCccd(@Nullable final BluetoothGattCharacteristic characteristic,
												   final int requiredProperty) {
		if (characteristic == null)
			return null;

		// Check characteristic property
		final int properties = characteristic.getProperties();
		if ((properties & requiredProperty) == 0)
			return null;

		return characteristic.getDescriptor(BleManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
	}

	private boolean internalReadCharacteristic(@Nullable final BluetoothGattCharacteristic characteristic) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || characteristic == null || !connected)
			return false;

		// Check characteristic property.
		final int properties = characteristic.getProperties();
		if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) == 0)
			return false;

		try {
			log(Log.VERBOSE, () -> "Reading characteristic " + characteristic.getUuid());
			log(Log.DEBUG, () -> "gatt.readCharacteristic(" + characteristic.getUuid() + ")");
			return gatt.readCharacteristic(characteristic);
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	private boolean internalWriteCharacteristic(
			@Nullable final BluetoothGattCharacteristic characteristic,
			@Nullable final byte[] data,
			@WriteType final int writeType
	) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || characteristic == null || !connected)
			return false;

		// Check characteristic property.
		final int properties = characteristic.getProperties();
		if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE |
				BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0)
			return false;

		try {
			final byte[] notNullData = data != null ? data : new byte[] {};
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				log(Log.VERBOSE, () ->
						"Writing characteristic " + characteristic.getUuid() +
								" (" + ParserUtils.writeTypeToString(writeType) + ")");
				log(Log.DEBUG, () -> "gatt.writeCharacteristic(" + characteristic.getUuid() +
						", value=" + ParserUtils.parseDebug(notNullData) +
						", " + ParserUtils.writeTypeToString(writeType) + ")");
				return gatt.writeCharacteristic(characteristic, notNullData, writeType) == BluetoothStatusCodes.SUCCESS;
			} else {
				log(Log.VERBOSE, () ->
						"Writing characteristic " + characteristic.getUuid() +
								" (" + ParserUtils.writeTypeToString(writeType) + ")");
				log(Log.DEBUG, () -> "characteristic.setValue(" + ParserUtils.parseDebug(notNullData) + ")");
				characteristic.setValue(notNullData);
				log(Log.DEBUG, () -> "characteristic.setWriteType(" + ParserUtils.writeTypeToString(writeType) + ")");
				characteristic.setWriteType(writeType);
				log(Log.DEBUG, () -> "gatt.writeCharacteristic(" + characteristic.getUuid() + ")");
				return gatt.writeCharacteristic(characteristic);
			}
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	private boolean internalReadDescriptor(@Nullable final BluetoothGattDescriptor descriptor) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || descriptor == null || !connected)
			return false;

		try {
			log(Log.VERBOSE, () -> "Reading descriptor " + descriptor.getUuid());
			log(Log.DEBUG, () -> "gatt.readDescriptor(" + descriptor.getUuid() + ")");
			return gatt.readDescriptor(descriptor);
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	private boolean internalWriteDescriptor(
			@Nullable final BluetoothGattDescriptor descriptor,
			@Nullable final byte[] data
	) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || descriptor == null || !connected)
			return false;

		try {
			final byte[] notNullData = data != null ? data : new byte[] {};
			log(Log.VERBOSE, () -> "Writing descriptor " + descriptor.getUuid());
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				log(Log.DEBUG, () -> "gatt.writeDescriptor(" + descriptor.getUuid() +
						", value=" + ParserUtils.parseDebug(notNullData) + ")");
				return gatt.writeDescriptor(descriptor, notNullData) == BluetoothStatusCodes.SUCCESS;
			} else {
				log(Log.DEBUG, () -> "descriptor.setValue(" + descriptor.getUuid() + ")");
				descriptor.setValue(notNullData);
				log(Log.DEBUG, () -> "gatt.writeDescriptor(" + descriptor.getUuid() + ")");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					return internalWriteDescriptorWorkaround(descriptor);
				} else {
					return gatt.writeDescriptor(descriptor);
				}
			}
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	/**
	 * There was a bug in Android up to 6.0 where the descriptor was written using parent
	 * characteristic's write type, instead of always Write With Response, as the spec says.
	 * <p>
	 * See: <a href="https://android.googlesource.com/platform/frameworks/base/+/942aebc95924ab1e7ea1e92aaf4e7fc45f695a6c%5E%21/#F0">
	 * https://android.googlesource.com/platform/frameworks/base/+/942aebc95924ab1e7ea1e92aaf4e7fc45f695a6c%5E%21/#F0</a>
	 *
	 * @param descriptor the descriptor to be written
	 * @return the result of {@link BluetoothGatt#writeDescriptor(BluetoothGattDescriptor)}
	 */
	private boolean internalWriteDescriptorWorkaround(@Nullable final BluetoothGattDescriptor descriptor) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || descriptor == null || !connected)
			return false;

		final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
		final int originalWriteType = parentCharacteristic.getWriteType();
		parentCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
		final boolean result = gatt.writeDescriptor(descriptor);
		parentCharacteristic.setWriteType(originalWriteType);
		return result;
	}

	private boolean internalBeginReliableWrite() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		// Reliable Write can't be before the old one isn't executed or aborted.
		if (reliableWriteInProgress)
			return true;

		log(Log.VERBOSE, () -> "Beginning reliable write...");
		log(Log.DEBUG, () -> "gatt.beginReliableWrite()");
		try {
			return reliableWriteInProgress = gatt.beginReliableWrite();
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	private boolean internalExecuteReliableWrite() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		if (!reliableWriteInProgress)
			return false;

		log(Log.VERBOSE, () -> "Executing reliable write...");
		log(Log.DEBUG, () -> "gatt.executeReliableWrite()");
		try {
			return gatt.executeReliableWrite();
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	private boolean internalAbortReliableWrite() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		if (!reliableWriteInProgress)
			return false;

		try {
			log(Log.VERBOSE, () -> "Aborting reliable write...");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				log(Log.DEBUG, () -> "gatt.abortReliableWrite()");
				gatt.abortReliableWrite();
			} else {
				log(Log.DEBUG, () -> "gatt.abortReliableWrite(device)");
				gatt.abortReliableWrite(gatt.getDevice());
			}
			return true;
		} catch (final SecurityException e) {
			log(Log.ERROR, e::getLocalizedMessage);
			return false;
		}
	}

	@Deprecated
	private boolean internalReadBatteryLevel() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		final BluetoothGattService batteryService = gatt.getService(BleManager.BATTERY_SERVICE);
		if (batteryService == null)
			return false;

		final BluetoothGattCharacteristic batteryLevelCharacteristic =
				batteryService.getCharacteristic(BleManager.BATTERY_LEVEL_CHARACTERISTIC);
		return internalReadCharacteristic(batteryLevelCharacteristic);
	}

	@Deprecated
	private boolean internalSetBatteryNotifications(final boolean enable) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		final BluetoothGattService batteryService = gatt.getService(BleManager.BATTERY_SERVICE);
		if (batteryService == null)
			return false;

		final BluetoothGattCharacteristic batteryLevelCharacteristic =
				batteryService.getCharacteristic(BleManager.BATTERY_LEVEL_CHARACTERISTIC);
		if (enable)
			return internalEnableNotifications(batteryLevelCharacteristic);
		else
			return internalDisableNotifications(batteryLevelCharacteristic);
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private boolean internalRequestMtu(@IntRange(from = 23, to = 517) final int mtu) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		log(Log.VERBOSE, () -> "Requesting new MTU...");
		log(Log.DEBUG, () -> "gatt.requestMtu(" + mtu + ")");
		return gatt.requestMtu(mtu);
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private boolean internalRequestConnectionPriority(@ConnectionPriority final int priority) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		// 5 seconds in Android Oreo and newer, 20 seconds in older versions.
		final int supervisionTimeout = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 5 : 20;
		log(Log.VERBOSE, () -> {
			String text = switch (priority) {
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH ->
						Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
								? "HIGH (11.25–15ms, 0, " + supervisionTimeout + "s)"
								: "HIGH (7.5–10ms, 0, " + supervisionTimeout + "s)";
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_LOW_POWER ->
						"LOW POWER (100–125ms, 2, " + supervisionTimeout + "s)";
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED ->
						"BALANCED (30–50ms, 0, " + supervisionTimeout + "s)";
				default -> throw new IllegalStateException("Unexpected value: " + priority);
			};
			return "Requesting connection priority: " + text + "...";
		});
		log(Log.DEBUG, () -> {
			String text = switch (priority) {
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH -> "HIGH";
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_LOW_POWER -> "LOW POWER";
				case ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED -> "BALANCED";
				default -> throw new IllegalStateException("Unexpected value: " + priority);
			};
			return "gatt.requestConnectionPriority(" + text + ")";
		});
		return gatt.requestConnectionPriority(priority);
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private boolean internalSetPreferredPhy(@PhyMask final int txPhy, @PhyMask final int rxPhy,
											@PhyOption final int phyOptions) {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		log(Log.VERBOSE, () -> "Requesting preferred PHYs...");
		log(Log.DEBUG, () ->
				"gatt.setPreferredPhy(" + ParserUtils.phyMaskToString(txPhy) + ", "
					+ ParserUtils.phyMaskToString(rxPhy) + ", coding option = "
					+ ParserUtils.phyCodedOptionToString(phyOptions) + ")");
		gatt.setPreferredPhy(txPhy, rxPhy, phyOptions);
		return true;
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private boolean internalReadPhy() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		log(Log.VERBOSE, () -> "Reading PHY...");
		log(Log.DEBUG, () -> "gatt.readPhy()");
		gatt.readPhy();
		return true;
	}

	private boolean internalReadRssi() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null || !connected)
			return false;

		log(Log.VERBOSE, () -> "Reading remote RSSI...");
		log(Log.DEBUG, () -> "gatt.readRemoteRssi()");
		return gatt.readRemoteRssi();
	}

	/**
	 * Sets and returns a callback that will respond to value changes.
	 *
	 * @param attribute attribute to bind the callback with. If null, the returned
	 *                  callback will not be null, but will not be used.
	 * @return The callback.
	 */
	@NonNull
	ValueChangedCallback getValueChangedCallback(@Nullable final Object attribute) {
		ValueChangedCallback callback = valueChangedCallbacks.get(attribute);
		if (callback == null) {
			callback = new ValueChangedCallback(this);
			if (attribute != null) {
				synchronized (valueChangedCallbacks) {
					valueChangedCallbacks.put(attribute, callback);
				}
			}
		} else if (bluetoothDevice != null) {
			callback.notifyClosed();
		}
		// TODO If attribute is null, the notifyDone(device) will never be called.
		return callback;
	}

	/**
	 * Removes the callback set using {@link #getValueChangedCallback(Object)}.
	 *
	 * @param attribute attribute to unbind the callback from.
	 */
	void removeValueChangedCallback(@Nullable final Object attribute) {
		synchronized (valueChangedCallbacks) {
			final ValueChangedCallback callback = valueChangedCallbacks.remove(attribute);
			if (callback != null) {
				callback.notifyClosed();
			}
		}
	}

	/**
	 * Sets the data provider for the given server characteristic.
	 * @param serverCharacteristic the server characteristic to add the data provider to.
	 * @param dataProvider the data provider to set.
	 */
	void setCharacteristicValue(@Nullable final BluetoothGattCharacteristic serverCharacteristic,
								@Nullable final DataProvider dataProvider) {
		if (serverCharacteristic == null)
			return;
		if (dataProvider == null) {
			dataProviders.remove(serverCharacteristic);
		} else {
			dataProviders.put(serverCharacteristic, dataProvider);
		}
	}

	/**
	 * Sets the data provider for the given server descriptor.
	 * @param serverDescriptor the server descriptor to add the data provider to.
	 * @param dataProvider the data provider to set.
	 */
	void setDescriptorValue(@Nullable final BluetoothGattDescriptor serverDescriptor,
							@Nullable final DataProvider dataProvider) {
		if (serverDescriptor == null)
			return;
		if (dataProvider == null) {
			dataProviders.remove(serverDescriptor);
		} else {
			dataProviders.put(serverDescriptor, dataProvider);
		}
	}

	/**
	 * Sets the connection priority callback.
	 * @param callback the callback
	 */
	@RequiresApi(api = Build.VERSION_CODES.O)
	void setConnectionParametersListener(@Nullable final ConnectionParametersUpdatedCallback callback) {
		connectionParametersUpdatedCallback = callback;

		// Notify the listener immediately.
		if (callback != null && bluetoothDevice != null && interval > 0) {
			callback.onConnectionUpdated(bluetoothDevice, interval, latency, timeout);
		}
	}

	@Deprecated
	DataReceivedCallback getBatteryLevelCallback() {
		return (device, data) -> {
			if (data.size() == 1) {
				//noinspection DataFlowIssue
				final int batteryLevel = data.getIntValue(Data.FORMAT_UINT8, 0);
				log(Log.INFO, () -> "Battery Level received: " + batteryLevel + "%");
				batteryValue = batteryLevel;
				onBatteryValueReceived(bluetoothGatt, batteryLevel);
				postCallback(c -> c.onBatteryValueReceived(device, batteryLevel));
			}
		};
	}

	@Deprecated
	void setBatteryLevelNotificationCallback() {
		if (batteryLevelNotificationCallback == null) {
			batteryLevelNotificationCallback = new ValueChangedCallback(this)
					.with((device, data) -> {
						if (data.size() == 1) {
							//noinspection DataFlowIssue
							final int batteryLevel = data.getIntValue(Data.FORMAT_UINT8, 0);
							batteryValue = batteryLevel;
							onBatteryValueReceived(bluetoothGatt, batteryLevel);
							postCallback(c -> c.onBatteryValueReceived(device, batteryLevel));
						}
					});
		}
	}

	/**
	 * Clears the device cache.
	 */
	@SuppressWarnings("JavaReflectionMemberAccess")
	private boolean internalRefreshDeviceCache() {
		final BluetoothGatt gatt = bluetoothGatt;
		if (gatt == null) // no need to be connected
			return false;

		log(Log.VERBOSE, () -> "Refreshing device cache...");
		log(Log.DEBUG, () -> "gatt.refresh() (hidden)");
		/*
		 * There is a refresh() method in BluetoothGatt class but for now it's hidden.
		 * We will call it using reflections.
		 */
		try {
			final Method refresh = gatt.getClass().getMethod("refresh");
			return refresh.invoke(gatt) == Boolean.TRUE;
		} catch (final Exception e) {
			Log.w(TAG, "An exception occurred while refreshing device", e);
			log(Log.WARN, () -> "gatt.refresh() method not found");
		}
		return false;
	}

	// Request Handler methods

	/**
	 * Enqueues the given request at the front of the the init or task queue, depending
	 * on whether the initialization is in progress, or not.
	 * <p>
	 * This method sets the {@link #operationInProgress} to false, assuming the newly added
	 * request will be executed immediately after this method ends.
	 *
	 * @param request the request to be added.
	 */
	private void enqueueFirst(@NonNull final Request request) {
		final RequestQueue rq = requestQueue;
		if (rq == null) {
			final Deque<Request> queue = initialization && initQueue != null ? initQueue : taskQueue;
			queue.addFirst(request);
		} else {
			rq.addFirst(request);
		}
		request.enqueued = true;
		// This ensures that the request that was put as first will be executed.
		// The reason this was added is stated in
		// https://github.com/NordicSemiconductor/Android-BLE-Library/issues/200
		// Basically, an operation done in several requests (like WriteRequest with split())
		// must be able to be performed despite awaiting request.
		operationInProgress = false;
		// nextRequest(...) must be called after enqueuing this request.
	}

	@Override
	final void enqueue(@NonNull final Request request) {
		if (!request.enqueued) {
			final Deque<Request> queue = initialization && initQueue != null ? initQueue : taskQueue;
			queue.add(request);
			request.enqueued = true;
		}
		nextRequest(false);
	}

	@Override
	final void cancelQueue() {
		emptyTasks(FailCallback.REASON_CANCELLED);
		initialization = false;

		final BluetoothDevice device = bluetoothDevice;
		if (device == null)
			return;

		if (operationInProgress) {
			cancelCurrent();
		}

		if (connectRequest != null) {
			connectRequest.notifyFail(device, FailCallback.REASON_CANCELLED);
			connectRequest = null;
			internalDisconnect(ConnectionObserver.REASON_CANCELLED);
		}
	}

	@Override
	final void cancelCurrent() {
		final BluetoothDevice device = bluetoothDevice;
		if (device == null)
			return;

		log(Log.WARN, () -> "Request cancelled");
		if (request instanceof final TimeoutableRequest r) {
			r.notifyFail(device, FailCallback.REASON_CANCELLED);
		}
		if (awaitingRequest != null) {
			awaitingRequest.notifyFail(device, FailCallback.REASON_CANCELLED);
			awaitingRequest = null;
		}
		if (requestQueue instanceof final ReliableWriteRequest rwr) {
			// Cancelling a Reliable Write request requires sending Abort command.
			// Instead of notifying failure, we will remove all enqueued tasks and
			// let the nextRequest to sent Abort command.
			rwr.notifyAndCancelQueue(device);
		} else if (requestQueue != null) {
			requestQueue.notifyFail(device, FailCallback.REASON_CANCELLED);
			requestQueue = null;
		}
		nextRequest(request == null || request.finished);
	}

	@Override
	final void onRequestTimeout(@NonNull final BluetoothDevice device, @NonNull final TimeoutableRequest tr) {
		if (tr instanceof final SleepRequest sr) {
			sr.notifySuccess(device);
		} else {
			log(Log.WARN, () -> "Request timed out");
		}
		if (request instanceof final TimeoutableRequest r) {
			r.notifyFail(device, FailCallback.REASON_TIMEOUT);
		}
		if (awaitingRequest != null) {
			awaitingRequest.notifyFail(device, FailCallback.REASON_TIMEOUT);
			awaitingRequest = null;
		}
		tr.notifyFail(device, FailCallback.REASON_TIMEOUT);
		if (tr.type == Request.Type.CONNECT) {
			connectRequest = null;
			internalDisconnect(ConnectionObserver.REASON_TIMEOUT);
			// The method above will call mGattCallback.nextRequest(true) so we have to return here.
			return;
		}
		if (tr.type == Request.Type.DISCONNECT) {
			close();
			return;
		}
		nextRequest(request == null || request.finished);
	}

	@Override
	public void post(@NonNull final Runnable r) {
		handler.post(r);
	}

	@Override
	public void postDelayed(@NonNull final Runnable r, final long delayMillis) {
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				r.run();
			}
		}, delayMillis);
	}

	@Override
	public void removeCallbacks(@NonNull final Runnable r) {
		handler.removeCallbacks(r);
	}

	// Helper methods
	@Deprecated
	private interface CallbackRunnable {
		void run(@NonNull final BleManagerCallbacks callbacks);
	}

	@Deprecated
	private void postCallback(@NonNull final CallbackRunnable r) {
		final BleManagerCallbacks callbacks = manager.callbacks;
		if (callbacks != null) {
			post(() -> r.run(callbacks));
		}
	}

	private interface BondingObserverRunnable {
		void run(@NonNull final BondingObserver observer);
	}

	private void postBondingStateChange(@NonNull final BondingObserverRunnable r) {
		final BondingObserver observer = manager.bondingObserver;
		if (observer != null) {
			post(() -> r.run(observer));
		}
	}

	private interface ConnectionObserverRunnable {
		void run(@NonNull final ConnectionObserver observer);
	}

	private void postConnectionStateChange(@NonNull final ConnectionObserverRunnable r) {
		final ConnectionObserver observer = manager.connectionObserver;
		if (observer != null) {
			post(() -> r.run(observer));
		}
	}

	/**
	 * Method returns the connection state:
	 * {@link BluetoothProfile#STATE_CONNECTING STATE_CONNECTING},
	 * {@link BluetoothProfile#STATE_CONNECTED STATE_CONNECTED},
	 * {@link BluetoothProfile#STATE_DISCONNECTING STATE_DISCONNECTING},
	 * {@link BluetoothProfile#STATE_DISCONNECTED STATE_DISCONNECTED}
	 *
	 * @return The connection state.
	 */
	@ConnectionState
	final int getConnectionState() {
		return connectionState;
	}

	/**
	 * This method returns true if the device is connected. Services could have not been
	 * discovered yet.
	 */
	final boolean isConnected() {
		return connected;
	}

	/**
	 * Returns the last received value of Battery Level characteristic, or -1 if such
	 * does not exist, hasn't been read or notification wasn't received yet.
	 */
	@Deprecated
	final int getBatteryValue() {
		return batteryValue;
	}

	/**
	 * Returns true if the device is connected and the initialization has finished,
	 * that is when {@link #onDeviceReady()} was called.
	 */
	final boolean isReady() {
		return ready;
	}

	/**
	 * Returns true if {@link BluetoothGatt#beginReliableWrite()} has been called and
	 * the Reliable Write hasn't been executed nor aborted yet.
	 */
	final boolean isReliableWriteInProgress() {
		return reliableWriteInProgress;
	}

	/**
	 * Returns the current MTU (Maximum Transfer Unit).
	 */
	@IntRange(from = 23, to = 515)
	final int getMtu() {
		return mtu;
	}

	final void overrideMtu(@IntRange(from = 23, to = 517) final int mtu) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			this.mtu = Math.min(515, mtu);
		}
	}

	/**
	 * This method should return <code>true</code> when the gatt device supports the
	 * required services.
	 *
	 * @param gatt the gatt device with services discovered
	 * @return <code>True</code> when the device has the required service.
	 * @deprecated Use {@link BleManager#isRequiredServiceSupported(BluetoothGatt)} instead.
	 */
	@Deprecated
	protected abstract boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt);

	/**
	 * This method should return <code>true</code> when the gatt device supports the
	 * optional services. The default implementation returns <code>false</code>.
	 *
	 * @param gatt the gatt device with services discovered
	 * @return <code>True</code> when the device has the optional service.
	 * @deprecated Use {@link BleManager#isOptionalServiceSupported(BluetoothGatt)} instead.
	 */
	@Deprecated
	protected boolean isOptionalServiceSupported(@NonNull final BluetoothGatt gatt) {
		return false;
	}

	/**
	 * This method should return a list of requests needed to initialize the profile.
	 * Enabling Service Change indications for bonded devices and reading the Battery Level
	 * value and enabling Battery Level notifications is handled before executing this queue.
	 * The queue should not have requests that are not available, e.g. should not read an
	 * optional service when it is not supported by the connected device.
	 * <p>
	 * This method is called when the services has been discovered and the device is supported
	 * (has required service).
	 *
	 * @param gatt the gatt device with services discovered
	 * @return The queue of requests.
	 * @deprecated Use {@link #initialize()} instead.
	 */
	@Deprecated
	protected Deque<Request> initGatt(@NonNull final BluetoothGatt gatt) {
		return null;
	}

	/**
	 * This method should set up the request queue needed to initialize the profile.
	 * Enabling Service Change indications for bonded devices is handled before executing this
	 * queue. The queue may have requests that are not available, e.g. read an optional
	 * service when it is not supported by the connected device. Such call will trigger
	 * {@link Request#fail(FailCallback)}.
	 * <p>
	 * This method is called when the services has been discovered and
	 * the device is supported (has required service).
	 * <p>
	 * @deprecated Use {@link BleManager#initialize()} instead.
	 */
	@Deprecated
	protected void initialize() {
		// empty initialization queue
	}

	/**
	 * In this method the manager should get references to server characteristics and descriptors
	 * that will use. The method is called after the service discovery of a remote device has
	 * finished and {@link #isRequiredServiceSupported(BluetoothGatt)} returned true.
	 * <p>
	 * The references obtained in this method should be released in {@link #onDeviceDisconnected()}.
	 * <p>
	 * This method is called only when the server was set by
	 * {@link BleManager#useServer(BleServerManager)} and opened using {@link BleServerManager#open()}.
	 *
	 * @param server The GATT Server instance. Use {@link BluetoothGattServer#getService(UUID)} to
	 *               obtain service instance.
	 * @deprecated Use {@link BleManager#onServerReady(BluetoothGattServer)} instead.
	 */
	@Deprecated
	protected void onServerReady(@NonNull final BluetoothGattServer server) {
		// empty initialization
	}

	/**
	 * Called when the initialization queue is complete.
	 * @deprecated Use {@link BleManager#onDeviceReady()} instead.
	 */
	@Deprecated
	protected void onDeviceReady() {
		// empty
	}

	/**
	 * Called each time the task queue gets cleared.
	 * @deprecated Use {@link BleManager#onManagerReady()} instead.
	 */
	@Deprecated
	protected void onManagerReady() {
		// empty
	}

	/**
	 * This method should nullify all services and characteristics of the device.
	 * <p>
	 * It's called when the device is no longer connected, either due to user action
	 * or a link loss, or when the services have changed and new service discovery will be
	 * performed.
	 *
	 * @deprecated Use {@link #onServicesInvalidated()} instead.
	 */
	@Deprecated
	protected void onDeviceDisconnected() {
		// empty
	}

	/**
	 * This method should nullify all services and characteristics of the device.
	 * <p>
	 * It's called when the services were invalidated and can no longer be used. Most probably the
	 * device has disconnected, Service Changed indication was received, or
	 * {@link BleManager#refreshDeviceCache()} request was executed, which has invalidated cached
	 * services.
	 * @deprecated Use {@link BleManager#onServicesInvalidated()} instead.
	 */
	@Deprecated
	protected abstract void onServicesInvalidated();

	void notifyDeviceDisconnected(@NonNull final BluetoothDevice device, final int status) {
		if (connectionState == BluetoothProfile.STATE_DISCONNECTED)
			return;

		final boolean wasConnected = connected;
		final boolean hadDiscoveredServices = servicesDiscovered;
		connected = false;
		ready = false;
		servicesDiscovered = false;
		serviceDiscoveryRequested = false;
		deviceNotSupported = false;
		mtu = 23;
		interval = latency = timeout = 0;
		connectionState = BluetoothGatt.STATE_DISCONNECTED;
		checkCondition();
		if (!wasConnected) {
			log(Log.WARN, () -> "Connection attempt timed out");
			close();
			// Close will not notify the observer as the device was not connected.
			postCallback(c -> c.onDeviceDisconnected(device));
			postConnectionStateChange(o -> o.onDeviceFailedToConnect(device, status));
			// ConnectRequest was already notified
		} else if (userDisconnected) {
			log(Log.INFO, () -> "Disconnected");
			// If Remove Bond was called, the broadcast may be called AFTER the device has disconnected.
			// In that case, we can't call close() here, as that would unregister the broadcast
			// receiver. Instead, close() will be called from the receiver.
			final Request request = this.request;
			if (request == null || request.type != Request.Type.REMOVE_BOND)
				close();
			postCallback(c -> c.onDeviceDisconnected(device));
			postConnectionStateChange(o -> o.onDeviceDisconnected(device, status));
			if (request != null && request.type == Request.Type.DISCONNECT) {
				request.notifySuccess(device);
				this.request = null;
			}
		} else {
			log(Log.WARN, () -> "Connection lost");
			postCallback(c -> c.onLinkLossOccurred(device));
			// When the device indicated disconnection, return the REASON_TERMINATE_PEER_USER.
			// Otherwise, return REASON_LINK_LOSS.
			// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/284
			final int reason = status == ConnectionObserver.REASON_TERMINATE_PEER_USER ?
					ConnectionObserver.REASON_TERMINATE_PEER_USER : ConnectionObserver.REASON_LINK_LOSS;
			postConnectionStateChange(o -> o.onDeviceDisconnected(device, reason));
			// We are not closing the connection here as the device should try to reconnect
			// automatically.
			// This may be only called when the shouldAutoConnect() method returned true.
		}
		synchronized (valueChangedCallbacks) {
			for (final ValueChangedCallback callback : valueChangedCallbacks.values()) {
				callback.notifyClosed();
			}
			valueChangedCallbacks.clear();
		}
		dataProviders.clear();
		batteryLevelNotificationCallback = null;
		batteryValue = -1;
		if (hadDiscoveredServices) {
			manager.onServicesInvalidated();
		}
		onDeviceDisconnected();
	}

	/**
	 * Callback reporting the result of a characteristic read operation.
	 *
	 * @param gatt           GATT client
	 * @param characteristic Characteristic that was read from the associated remote device.
	 * @deprecated Use {@link ReadRequest#with(DataReceivedCallback)} instead.
	 */
	@Deprecated
	protected void onCharacteristicRead(@NonNull final BluetoothGatt gatt,
										@NonNull final BluetoothGattCharacteristic characteristic) {
		// do nothing
	}

	/**
	 * Callback indicating the result of a characteristic write operation.
	 * <p>If this callback is invoked while a reliable write transaction is
	 * in progress, the value of the characteristic represents the value
	 * reported by the remote device. An application should compare this
	 * value to the desired value to be written. If the values don't match,
	 * the application must abort the reliable write transaction.
	 *
	 * @param gatt           GATT client
	 * @param characteristic Characteristic that was written to the associated remote device.
	 * @deprecated Use {@link WriteRequest#done(SuccessCallback)} instead.
	 */
	@Deprecated
	protected void onCharacteristicWrite(@NonNull final BluetoothGatt gatt,
										 @NonNull final BluetoothGattCharacteristic characteristic) {
		// do nothing
	}

	/**
	 * Callback reporting the result of a descriptor read operation.
	 *
	 * @param gatt       GATT client
	 * @param descriptor Descriptor that was read from the associated remote device.
	 * @deprecated Use {@link ReadRequest#with(DataReceivedCallback)} instead.
	 */
	@Deprecated
	protected void onDescriptorRead(@NonNull final BluetoothGatt gatt,
									@NonNull final BluetoothGattDescriptor descriptor) {
		// do nothing
	}

	/**
	 * Callback indicating the result of a descriptor write operation.
	 * <p>If this callback is invoked while a reliable write transaction is in progress,
	 * the value of the characteristic represents the value reported by the remote device.
	 * An application should compare this value to the desired value to be written.
	 * If the values don't match, the application must abort the reliable write transaction.
	 *
	 * @param gatt       GATT client
	 * @param descriptor Descriptor that was written to the associated remote device.
	 * @deprecated Use {@link WriteRequest} and {@link SuccessCallback} instead.
	 */
	@Deprecated
	protected void onDescriptorWrite(@NonNull final BluetoothGatt gatt,
									 @NonNull final BluetoothGattDescriptor descriptor) {
		// do nothing
	}

	/**
	 * Callback reporting the value of Battery Level characteristic which could have
	 * been received by Read or Notify operations.
	 * <p>
	 * This method will not be called if {@link BleManager#readBatteryLevel()} and
	 * {@link BleManager#enableBatteryLevelNotifications()} were overridden.
	 * </p>
	 *
	 * @param gatt  GATT client
	 * @param value the battery value in percent
	 * @deprecated Use {@link ReadRequest#with(DataReceivedCallback)} and
	 * BatteryLevelDataCallback from BLE-Common-Library instead.
	 */
	@Deprecated
	protected void onBatteryValueReceived(@NonNull final BluetoothGatt gatt,
										  @IntRange(from = 0, to = 100) final int value) {
		// do nothing
	}

	/**
	 * Callback indicating a notification has been received.
	 *
	 * @param gatt           GATT client
	 * @param characteristic Characteristic from which the notification came.
	 * @deprecated Use {@link ReadRequest#with(DataReceivedCallback)} instead.
	 */
	@Deprecated
	protected void onCharacteristicNotified(@NonNull final BluetoothGatt gatt,
											@NonNull final BluetoothGattCharacteristic characteristic) {
		// do nothing
	}

	/**
	 * Callback indicating an indication has been received.
	 *
	 * @param gatt           GATT client
	 * @param characteristic Characteristic from which the indication came.
	 * @deprecated Use {@link ReadRequest#with(DataReceivedCallback)} instead.
	 */
	@Deprecated
	protected void onCharacteristicIndicated(@NonNull final BluetoothGatt gatt,
											 @NonNull final BluetoothGattCharacteristic characteristic) {
		// do nothing
	}

	/**
	 * Method called when the MTU request has finished with success. The MTU value may
	 * be different than requested one.
	 *
	 * @param gatt GATT client
	 * @param mtu  the new MTU (Maximum Transfer Unit)
	 * @deprecated Use {@link MtuRequest#with(MtuCallback)} instead.
	 */
	@Deprecated
	protected void onMtuChanged(@NonNull final BluetoothGatt gatt,
								@IntRange(from = 23, to = 515) final int mtu) {
		// do nothing
	}

	/**
	 * Callback indicating the connection parameters were updated. Works on Android 8+.
	 *
	 * @param gatt     GATT client.
	 * @param interval Connection interval used on this connection, 1.25ms unit.
	 *                 Valid range is from 6 (7.5ms) to 3200 (4000ms).
	 * @param latency  Slave latency for the connection in number of connection events.
	 *                 Valid range is from 0 to 499.
	 * @param timeout  Supervision timeout for this connection, in 10ms unit.
	 *                 Valid range is from 10 (0.1s) to 3200 (32s).
	 * @deprecated Use {@link ConnectionPriorityRequest#with(ConnectionParametersUpdatedCallback)} instead.
	 */
	@Deprecated
	@TargetApi(Build.VERSION_CODES.O)
	protected void onConnectionUpdated(@NonNull final BluetoothGatt gatt,
									   @IntRange(from = 6, to = 3200) final int interval,
									   @IntRange(from = 0, to = 499) final int latency,
									   @IntRange(from = 10, to = 3200) final int timeout) {
		// do nothing
	}

	private void onError(final BluetoothDevice device, final String message, final int errorCode) {
		log(Log.ERROR, () -> "Error (0x" + Integer.toHexString(errorCode) + "): " + GattError.parse(errorCode));
		postCallback(c -> c.onError(device, message, errorCode));
	}

	private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

		@Override
		public void onConnectionStateChange(@NonNull final BluetoothGatt gatt,
											final int status, final int newState) {
			log(Log.DEBUG, () ->
					"[Callback] Connection state changed with status: " + status +
					" and new state: " + newState + " (" + ParserUtils.stateToString(newState) + ")");

			if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
				// Sometimes, when a notification/indication is received after the device got
				// disconnected, the Android calls onConnectionStateChanged again, with state
				// STATE_CONNECTED.
				// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/43
				if (bluetoothDevice == null) {
					Log.e(TAG, "Device received notification after disconnection.");
					log(Log.DEBUG, () -> "gatt.close()");
					try {
						gatt.close();
					} catch (final Throwable t) {
						// ignore
					}
					return;
				}

				// Notify the parent activity/service.
				log(Log.INFO, () -> "Connected to " + gatt.getDevice().getAddress());
				connected = true;
				connectionTime = 0L;
				connectionState = BluetoothGatt.STATE_CONNECTED;
				postCallback(c -> c.onDeviceConnected(gatt.getDevice()));
				postConnectionStateChange(o -> o.onDeviceConnected(gatt.getDevice()));

				if (!serviceDiscoveryRequested) {
					final boolean bonded = gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED;
					final int delay = manager.getServiceDiscoveryDelay(bonded);
					if (delay > 0)
						log(Log.DEBUG, () -> "wait(" + delay + ")");

					final int connectionCount = ++BleManagerHandler.this.connectionCount;
					postDelayed(() -> {
						if (connectionCount != BleManagerHandler.this.connectionCount) {
							// Ensure that we will not try to discover services for a lost connection.
							return;
						}
						// Some proximity tags (e.g. nRF PROXIMITY Pebble) initialize bonding
						// automatically when connected. Wait with the discovery until bonding is
						// complete. It will be initiated again in the bond state broadcast receiver
						// on the top of this file.
						if (connected && !servicesDiscovered && !serviceDiscoveryRequested &&
								gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDING) {
							serviceDiscoveryRequested = true;
							log(Log.VERBOSE, () -> "Discovering services...");
							log(Log.DEBUG, () -> "gatt.discoverServices()");
							gatt.discoverServices();
						}
					}, delay);
				}
			} else {
				if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					final Request r = BleManagerHandler.this.request;
					final ConnectRequest cr = connectRequest;
					final AwaitingRequest<?> ar = awaitingRequest;

					final long now = SystemClock.elapsedRealtime();
					final boolean canTimeout = connectionTime > 0;
					final boolean timeout = canTimeout && now > connectionTime + CONNECTION_TIMEOUT_THRESHOLD;

					if (status != BluetoothGatt.GATT_SUCCESS)
						log(Log.WARN, () ->
								"Error: (0x" + Integer.toHexString(status) + "): " +
								GattError.parseConnectionError(status));

					// In case of a connection error, retry if required.
					if (status != BluetoothGatt.GATT_SUCCESS && canTimeout && !timeout
							&& cr != null && cr.canRetry()) {
						final int delay = cr.getRetryDelay();
						if (delay > 0)
							log(Log.DEBUG, () -> "wait(" + delay + ")");
						postDelayed(() -> {
                            // fix: check if cr is not changed after delay
                            if(cr == connectRequest) {
                                internalConnect(gatt.getDevice(), cr);
                                // If ConnectRequest was cancelled during wait(200) in internalConnect(),
                                // the gatt will be null, but the state is still CONNECTING.
                                // We need to notify observers about cancellation.
                                if (bluetoothGatt == null) {
                                    connectionState = BluetoothGatt.STATE_DISCONNECTED;
                                    log(Log.INFO, () -> "Disconnected");
                                    postCallback(c -> c.onDeviceDisconnected(gatt.getDevice()));
                                    postConnectionStateChange(o -> o.onDeviceFailedToConnect(gatt.getDevice(), ConnectionObserver.REASON_CANCELLED));
                                    onDeviceDisconnected();
                                }
                            }
						}, delay);
						return;
					}

					if (cr != null && cr.shouldAutoConnect() && initialConnection) {
						log(Log.DEBUG, () -> "autoConnect = false called failed; retrying with autoConnect = true" + (connected ? "; reset connected to false" : ""));

						// fix：https://github.com/NordicSemiconductor/Android-BLE-Library/issues/497
						// if DISCONNECTED is received between connect and initialize, need to reset connected to make internalConnect work
						if (connected) {
							connected = false;
							connectionState = BluetoothGatt.STATE_DISCONNECTED;
						}

						post(() -> internalConnect(gatt.getDevice(), cr));
						return;
					}

					operationInProgress = true; // no more calls are possible
					emptyTasks(FailCallback.REASON_DEVICE_DISCONNECTED);
					ready = false;

					// Store the current value of the connected and deviceNotSupported flags...
					final boolean wasConnected = connected;
					final boolean notSupported = deviceNotSupported;
					// ...because the next method sets them to false.

					// notifyDeviceDisconnected(...) may call close()

					if (status == GattError.GATT_CONN_TIMEOUT && earlyPhyLe2MRequest) {
						notifyDeviceDisconnected(gatt.getDevice(), ConnectionObserver.REASON_UNSUPPORTED_CONFIGURATION);
					} else if (timeout) {
						notifyDeviceDisconnected(gatt.getDevice(), ConnectionObserver.REASON_TIMEOUT);
					} else if (notSupported) {
						notifyDeviceDisconnected(gatt.getDevice(), ConnectionObserver.REASON_NOT_SUPPORTED);
					} else if (r != null && r.type == Request.Type.DISCONNECT) {
						notifyDeviceDisconnected(gatt.getDevice(), ConnectionObserver.REASON_SUCCESS);
					} else {
						// Note, that even if the status is SUCCESS, the reported reason won't be success.
						notifyDeviceDisconnected(gatt.getDevice(), mapDisconnectStatusToReason(status));
					}

					// Signal the current request, if any.
					if (r != null) {
						if (r.type != Request.Type.DISCONNECT && r.type != Request.Type.REMOVE_BOND) {
							// The CONNECT request is notified below.
							// The DISCONNECT request is notified below in
							// notifyDeviceDisconnected(BluetoothDevice).
							// The REMOVE_BOND request will be notified when the bond state changes
							// to BOND_NONE in the broadcast received on the top of this file.
							r.notifyFail(gatt.getDevice(),
									status == BluetoothGatt.GATT_SUCCESS ?
											FailCallback.REASON_DEVICE_DISCONNECTED : status);
							request = null;
						}
					}
					if (ar != null) {
						ar.notifyFail(gatt.getDevice(), FailCallback.REASON_DEVICE_DISCONNECTED);
						awaitingRequest = null;
					}
					if (cr != null) {
						int reason;
						if (status == GattError.GATT_CONN_TIMEOUT && earlyPhyLe2MRequest)
							reason = FailCallback.REASON_UNSUPPORTED_CONFIGURATION;
						else if (notSupported)
							reason = FailCallback.REASON_DEVICE_NOT_SUPPORTED;
						else if (status == BluetoothGatt.GATT_SUCCESS)
							reason = FailCallback.REASON_DEVICE_DISCONNECTED;
						else if ((status == GattError.GATT_ERROR || status == GattError.GATT_TIMEOUT) && timeout)
							reason = FailCallback.REASON_TIMEOUT;
						else
							reason = status;
						cr.notifyFail(gatt.getDevice(), reason);
						connectRequest = null;
					}

					// Reset flag, so the next Connect could be enqueued.
					operationInProgress = false;

					// return because Request.Type.REMOVE_BOND not handled
					if (r != null && r.type == Request.Type.REMOVE_BOND) {
						return;
					}

					// Try to reconnect if the initial connection was lost because of a link loss,
					// and shouldAutoConnect() returned true during connection attempt.
					// This time it will set the autoConnect flag to true (gatt.connect() forces
					// autoConnect true).
					if (wasConnected && initialConnection) {
						internalConnect(gatt.getDevice(), null);
					} else {
						initialConnection = false;
						nextRequest(false);
					}

					if (wasConnected || status == BluetoothGatt.GATT_SUCCESS)
						return;
				} else {
					if (status != BluetoothGatt.GATT_SUCCESS)
						log(Log.ERROR, () ->
								"Error (0x" + Integer.toHexString(status) + "): " +
								GattError.parseConnectionError(status));
				}
				postCallback(c -> c.onError(gatt.getDevice(), ERROR_CONNECTION_STATE_CHANGE, status));
			}
		}

		@Override
		public void onServicesDiscovered(@NonNull final BluetoothGatt gatt, final int status) {
			if (!serviceDiscoveryRequested)
				return;
			serviceDiscoveryRequested = false;
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () -> "Services discovered");
				servicesDiscovered = true;
				if (manager.isRequiredServiceSupported(gatt)) {
					log(Log.VERBOSE, () -> "Primary service found");
					deviceNotSupported = false;
					final boolean optionalServicesFound = manager.isOptionalServiceSupported(gatt);
					if (optionalServicesFound)
						log(Log.VERBOSE, () -> "Secondary service found");

					// Notify the parent activity.
					postCallback(c -> c.onServicesDiscovered(gatt.getDevice(), optionalServicesFound));

					// Initialize server attributes.
					initializeServerAttributes();

					// Obtain the queue of initialization requests.
					// First, let's call the deprecated initGatt(...).
					operationInProgress = true;
					initialization = true;
					initQueue = initGatt(gatt);

					final boolean deprecatedApiUsed = initQueue != null;
					if (deprecatedApiUsed) {
						for (final Request request : initQueue) {
							request.setRequestHandler(BleManagerHandler.this);
							request.enqueued = true;
						}
					}

					if (initQueue == null)
						initQueue = new LinkedBlockingDeque<>();

					// Before we start executing the initialization queue some other tasks
					// need to be done.
					// Note, that operations are added in reverse order to the front of the queue.

					// 1. On devices running Android 4.3-5.x, 8.x and 9.0 the Service Changed
					//    characteristic needs to be enabled by the app (for bonded devices).
					//    The request will be ignored if there is no Service Changed characteristic.
					// This "fix" broke this in Android 8:
					// https://android-review.googlesource.com/c/platform/system/bt/+/239970
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
							|| Build.VERSION.SDK_INT == Build.VERSION_CODES.O
							|| Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1
							|| Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
						enqueueFirst(Request.newEnableServiceChangedIndicationsRequest()
								.setRequestHandler(BleManagerHandler.this));
						// The above enqueueFirst sets this flag to false.
						operationInProgress = true;
					}

					// Deprecated:
					if (deprecatedApiUsed) {
						// All Battery Service handling will be removed from BleManager in the future.
						// If you want to read/enable notifications on Battery Level characteristic
						// do this in initialize(...).

						// 2. Read Battery Level characteristic (if such does not exist, this will
						//    be skipped)
						manager.readBatteryLevel();
						// 3. Enable Battery Level notifications if required (if this char. does not
						//    exist, this operation will be skipped)
						if (manager.callbacks != null &&
							manager.callbacks.shouldEnableBatteryLevelNotifications(gatt.getDevice()))
							manager.enableBatteryLevelNotifications();
					}
					// End

					manager.initialize();
					initialization = false;
					nextRequest(true);
				} else {
					log(Log.WARN, () -> "Device is not supported");
					deviceNotSupported = true;
					postCallback(c -> c.onDeviceNotSupported(gatt.getDevice()));
					internalDisconnect(ConnectionObserver.REASON_NOT_SUPPORTED);
				}
			} else {
				Log.e(TAG, "onServicesDiscovered error " + status);
				onError(gatt.getDevice(), ERROR_DISCOVERY_SERVICE, status);
				if (connectRequest != null) {
					connectRequest.notifyFail(gatt.getDevice(), FailCallback.REASON_REQUEST_FAILED);
					connectRequest = null;
				}
				internalDisconnect(ConnectionObserver.REASON_UNKNOWN);
			}
		}

		// @Override
		/**
		 * Callback indicating service changed event is received.
		 * <p>
		 * Receiving this event means that the GATT database is out of sync with the remote device.
		 * <p>
		 * Requires API 31+.
		 */
		@Keep
		public void onServiceChanged(@NonNull final BluetoothGatt gatt) {
			log(Log.INFO, () -> "Service changed, invalidating services");

			// Forbid enqueuing more operations.
			operationInProgress = true;
			// Invalidate all services and characteristics
			manager.onServicesInvalidated();
			onDeviceDisconnected();
			// Clear queues, services are no longer valid.
			emptyTasks(FailCallback.REASON_NULL_ATTRIBUTE);
			// And discover services again
			serviceDiscoveryRequested = true;
			servicesDiscovered = false;
			log(Log.VERBOSE, () -> "Discovering Services...");
			log(Log.DEBUG, () -> "gatt.discoverServices()");
			gatt.discoverServices();
		}

		@Override
		public void onCharacteristicRead(final BluetoothGatt gatt,
										 final BluetoothGattCharacteristic characteristic,
										 final int status) {
			onCharacteristicRead(gatt, characteristic, characteristic.getValue(), status);
		}

		@Override
		public void onCharacteristicRead(@NonNull final BluetoothGatt gatt,
										 @NonNull final BluetoothGattCharacteristic characteristic,
										 @NonNull byte[] data, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () ->
						"Read Response received from " + characteristic.getUuid() +
						", value: " + ParserUtils.parse(data));

				BleManagerHandler.this.onCharacteristicRead(gatt, characteristic);
				if (request instanceof final ReadRequest rr) {
					final boolean matches = rr.matches(data);
					if (matches) {
						rr.notifyValueChanged(gatt.getDevice(), data);
					}
					if (!matches || rr.hasMore()) {
						enqueueFirst(rr);
					} else {
						rr.notifySuccess(gatt.getDevice());
					}
				}
			} else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
					|| status == 8 /* GATT INSUF AUTHORIZATION */
					|| status == 137 /* GATT AUTH FAIL */) {
				// This is called when bonding attempt failed, but the app is still trying to read.
				// We need to cancel the request here, as bonding won't start.
				log(Log.WARN, () -> "Authentication required (" + status + ")");
				if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
					// This should never happen but it used to: http://stackoverflow.com/a/20093695/2115352
					Log.w(TAG, ERROR_AUTH_ERROR_WHILE_BONDED);
					postCallback(c -> c.onError(gatt.getDevice(), ERROR_AUTH_ERROR_WHILE_BONDED, status));
				}
				if (request instanceof final ReadRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
				}
			} else {
				Log.e(TAG, "onCharacteristicRead error " + status + ", bond state: " + gatt.getDevice().getBondState());
				if (request instanceof final ReadRequest rr) {
					rr.notifyFail(gatt.getDevice(), status);
				}
				awaitingRequest = null;
				onError(gatt.getDevice(), ERROR_READ_CHARACTERISTIC, status);
			}
			checkCondition();
			nextRequest(true);
		}

		@Override
		public void onCharacteristicWrite(final BluetoothGatt gatt,
										  final BluetoothGattCharacteristic characteristic,
										  final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				// When writing without response, the characteristic value is not updated on Android 13+.
				// The data written was logged out when the request was executed.
				log(Log.INFO, () -> "Data written to " + characteristic.getUuid());

				BleManagerHandler.this.onCharacteristicWrite(gatt, characteristic);
				if (request instanceof final WriteRequest wr) {
					// Notify the listeners about the packet being sent.
					// This method also compares the data written with the data received in the callback
					// if the write type is WRITE_TYPE_DEFAULT.
					final boolean valid = wr.notifyPacketSent(gatt.getDevice(), characteristic.getValue());
					if (!valid && requestQueue instanceof final ReliableWriteRequest rwr) {
						wr.notifyFail(gatt.getDevice(), FailCallback.REASON_VALIDATION);
						rwr.notifyAndCancelQueue(gatt.getDevice());
					} else if (wr.hasMore()) {
						enqueueFirst(wr);
					} else {
						wr.notifySuccess(gatt.getDevice());
					}
				}
			} else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
					|| status == 8 /* GATT INSUF AUTHORIZATION */
					|| status == 137 /* GATT AUTH FAIL */) {
				// This is called when bonding attempt failed, but the app is still trying to write.
				// We need to cancel the request here, as bonding won't start.
				log(Log.WARN, () -> "Authentication required (" + status + ")");
				if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
					// This should never happen but it used to: http://stackoverflow.com/a/20093695/2115352
					Log.w(TAG, ERROR_AUTH_ERROR_WHILE_BONDED);
					postCallback(c -> c.onError(gatt.getDevice(), ERROR_AUTH_ERROR_WHILE_BONDED, status));
				}
				if (request instanceof final WriteRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
					// Automatically abort Reliable Write when write error happen
					if (requestQueue instanceof final ReliableWriteRequest rwr)
						rwr.notifyAndCancelQueue(gatt.getDevice());
				}
			} else {
				Log.e(TAG, "onCharacteristicWrite error " + status + ", bond state: " + gatt.getDevice().getBondState());
				if (request instanceof final WriteRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
					// Automatically abort Reliable Write when write error happen
					if (requestQueue instanceof final ReliableWriteRequest rwr)
						rwr.notifyAndCancelQueue(gatt.getDevice());
				}
				awaitingRequest = null;
				onError(gatt.getDevice(), ERROR_WRITE_CHARACTERISTIC, status);
			}
			checkCondition();
			nextRequest(true);
		}

		@Override
		public void onReliableWriteCompleted(@NonNull final BluetoothGatt gatt,
											 final int status) {
			final boolean execute = request.type == Request.Type.EXECUTE_RELIABLE_WRITE;
			reliableWriteInProgress = false;
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (execute) {
					log(Log.INFO, () -> "Reliable Write executed");
					request.notifySuccess(gatt.getDevice());
				} else {
					log(Log.WARN, () -> "Reliable Write aborted");
					request.notifySuccess(gatt.getDevice());
					requestQueue.notifyFail(gatt.getDevice(), FailCallback.REASON_REQUEST_FAILED);
				}
			} else {
				Log.e(TAG, "onReliableWriteCompleted execute " + execute + ", error " + status);
				request.notifyFail(gatt.getDevice(), status);
				onError(gatt.getDevice(), ERROR_RELIABLE_WRITE, status);
			}
			checkCondition();
			nextRequest(true);
		}

		@Override
		public void onDescriptorRead(final BluetoothGatt gatt,
									 final BluetoothGattDescriptor descriptor,
									 final int status) {
			onDescriptorRead(gatt, descriptor, status, descriptor.getValue());
		}

		@Override
		public void onDescriptorRead(final @NonNull BluetoothGatt gatt,
									 final @NonNull BluetoothGattDescriptor descriptor,
									 final int status, final @NonNull byte[] data) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () -> "Read Response received from descr. " + descriptor.getUuid() +
						", value: " + ParserUtils.parse(data));

				BleManagerHandler.this.onDescriptorRead(gatt, descriptor);
				if (request instanceof final ReadRequest rr) {
					rr.notifyValueChanged(gatt.getDevice(), data);
					if (rr.hasMore()) {
						enqueueFirst(rr);
					} else {
						rr.notifySuccess(gatt.getDevice());
					}
				}
			} else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
					|| status == 8 /* GATT INSUF AUTHORIZATION */
					|| status == 137 /* GATT AUTH FAIL */) {
				// This is called when bonding attempt failed, but the app is still trying to read.
				// We need to cancel the request here, as bonding won't start.
				log(Log.WARN, () -> "Authentication required (" + status + ")");
				if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
					// This should never happen but it used to: http://stackoverflow.com/a/20093695/2115352
					Log.w(TAG, ERROR_AUTH_ERROR_WHILE_BONDED);
					postCallback(c -> c.onError(gatt.getDevice(), ERROR_AUTH_ERROR_WHILE_BONDED, status));
				}
				if (request instanceof final ReadRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
				}
			} else {
				Log.e(TAG, "onDescriptorRead error " + status + ", bond state: " + gatt.getDevice().getBondState());
				if (request instanceof final ReadRequest rr) {
					rr.notifyFail(gatt.getDevice(), status);
				}
				awaitingRequest = null;
				onError(gatt.getDevice(), ERROR_READ_DESCRIPTOR, status);
			}
			checkCondition();
			nextRequest(true);
		}

		@Override
		public void onDescriptorWrite(final BluetoothGatt gatt,
									  final BluetoothGattDescriptor descriptor,
									  final int status) {
			final byte[] data = descriptor.getValue();

			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () -> "Data written to descr. " + descriptor.getUuid());

				if (isServiceChangedCCCD(descriptor)) {
					log(Log.INFO, () -> "Service Changed notifications enabled");
				} else if (isCCCD(descriptor)) {
					if (data != null && data.length == 2 && data[1] == 0x00) {
						switch (data[0]) {
							case 0x00 -> log(Log.INFO, () -> "Notifications and indications disabled");
							case 0x01 -> log(Log.INFO, () -> "Notifications enabled");
							case 0x02 -> log(Log.INFO, () -> "Indications enabled");
						}
						BleManagerHandler.this.onDescriptorWrite(gatt, descriptor);
					}
				} else {
					BleManagerHandler.this.onDescriptorWrite(gatt, descriptor);
				}
				if (request instanceof final WriteRequest wr) {
					final boolean valid = wr.notifyPacketSent(gatt.getDevice(), data);
					if (!valid && requestQueue instanceof final ReliableWriteRequest rwr) {
						wr.notifyFail(gatt.getDevice(), FailCallback.REASON_VALIDATION);
						rwr.notifyAndCancelQueue(gatt.getDevice());
					} else if (wr.hasMore()) {
						enqueueFirst(wr);
					} else {
						wr.notifySuccess(gatt.getDevice());
					}
				}
			} else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
					|| status == 8 /* GATT INSUF AUTHORIZATION */
					|| status == 137 /* GATT AUTH FAIL */) {
				// This is called when bonding attempt failed, but the app is still trying to write.
				// We need to cancel the request here, as bonding won't start.
				log(Log.WARN, () -> "Authentication required (" + status + ")");
				if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
					// This should never happen but it used to: http://stackoverflow.com/a/20093695/2115352
					Log.w(TAG, ERROR_AUTH_ERROR_WHILE_BONDED);
					postCallback(c -> c.onError(gatt.getDevice(), ERROR_AUTH_ERROR_WHILE_BONDED, status));
				}
				if (request instanceof final WriteRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
					// Automatically abort Reliable Write when write error happen
					if (requestQueue instanceof final ReliableWriteRequest rwr)
						rwr.notifyAndCancelQueue(gatt.getDevice());
				}
			} else {
				Log.e(TAG, "onDescriptorWrite error " + status + ", bond state: " + gatt.getDevice().getBondState());
				if (request instanceof final WriteRequest wr) {
					wr.notifyFail(gatt.getDevice(), status);
					// Automatically abort Reliable Write when write error happen
					if (requestQueue instanceof final ReliableWriteRequest rwr)
						rwr.notifyAndCancelQueue(gatt.getDevice());
				}
				awaitingRequest = null;
				onError(gatt.getDevice(), ERROR_WRITE_DESCRIPTOR, status);
			}
			checkCondition();
			nextRequest(true);
		}


		@Override
		public void onCharacteristicChanged(final BluetoothGatt gatt,
											final BluetoothGattCharacteristic characteristic) {
			onCharacteristicChanged(gatt, characteristic, characteristic.getValue());
		}

		@Override
		public void onCharacteristicChanged(
				@NonNull final BluetoothGatt gatt,
				@NonNull final BluetoothGattCharacteristic characteristic,
				@NonNull final byte[] data) {
			if (isServiceChangedCharacteristic(characteristic)) {
				// Android S added onServiceChanged() callback, which should be called in this
				// situation. Again, this has not been tested.
				if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
					log(Log.INFO, () -> "Service Changed indication received");
					// For older APIs, trigger service discovery.
					// TODO this should be tested. Should services be invalidated?
					// Forbid enqueuing more operations.
					operationInProgress = true;
					// Invalidate all services and characteristics
					manager.onServicesInvalidated();
					onDeviceDisconnected();
					// Clear queues, services are no longer valid.
					emptyTasks(FailCallback.REASON_NULL_ATTRIBUTE);
					serviceDiscoveryRequested = true;
					log(Log.VERBOSE, () -> "Discovering Services...");
					log(Log.DEBUG, () -> "gatt.discoverServices()");
					gatt.discoverServices();
				}
				return;
			}

			final BluetoothGattDescriptor cccd =
					characteristic.getDescriptor(BleManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
			final boolean notifications = cccd == null || cccd.getValue() == null ||
					cccd.getValue().length != 2 || cccd.getValue()[0] == 0x01;

			if (notifications) {
				log(Log.INFO, () -> "Notification received from " +
						characteristic.getUuid() + ", value: " + ParserUtils.parse(data));
				onCharacteristicNotified(gatt, characteristic);
			} else { // indications
				log(Log.INFO, () -> "Indication received from " +
						characteristic.getUuid() + ", value: " + ParserUtils.parse(data));
				onCharacteristicIndicated(gatt, characteristic);
			}
			if (batteryLevelNotificationCallback != null && isBatteryLevelCharacteristic(characteristic)) {
				batteryLevelNotificationCallback.notifyValueChanged(gatt.getDevice(), data);
			}
			// Notify the notification registered listener, if set
			final ValueChangedCallback request = valueChangedCallbacks.get(characteristic);
			if (request != null && request.matches(data)) {
				request.notifyValueChanged(gatt.getDevice(), data);
			}
			// If there is a value change request,
			if (awaitingRequest instanceof final WaitForValueChangedRequest valueChangedRequest
					// registered for this characteristic
					&& awaitingRequest.characteristic == characteristic
					// and didn't have a trigger, or the trigger was started
					// (not necessarily completed)
					&& !awaitingRequest.isTriggerPending()) {
				if (valueChangedRequest.matches(data)) {
					// notify that new data was received.
					valueChangedRequest.notifyValueChanged(gatt.getDevice(), data);

					// If no more data are expected
					if (valueChangedRequest.isComplete()) {
						log(Log.INFO, () -> "Wait for value changed complete");
						// notify success,
						valueChangedRequest.notifySuccess(gatt.getDevice());
						// and proceed to the next request only if the trigger has completed.
						// Otherwise, the next request will be started when the request's callback
						// will be received.
						awaitingRequest = null;
						if (valueChangedRequest.isTriggerCompleteOrNull()) {
							nextRequest(true);
						}
					}
				}
			}
			if (checkCondition()) {
				nextRequest(true);
			}
		}

		@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
		@Override
		public void onMtuChanged(@NonNull final BluetoothGatt gatt,
								 @IntRange(from = 23, to = 517) final int mtu,
								 final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () -> "MTU changed to: " + mtu);
				BleManagerHandler.this.mtu = Math.min(515, mtu);
				BleManagerHandler.this.onMtuChanged(gatt, BleManagerHandler.this.mtu);
				if (request instanceof final MtuRequest mr) {
					mr.notifyMtuChanged(gatt.getDevice(), BleManagerHandler.this.mtu);
					mr.notifySuccess(gatt.getDevice());
				}
			} else {
				Log.e(TAG, "onMtuChanged error: " + status + ", mtu: " + mtu);
				if (request instanceof final MtuRequest mr) {
					mr.notifyFail(gatt.getDevice(), status);
					awaitingRequest = null;
				}
				onError(gatt.getDevice(), ERROR_MTU_REQUEST, status);
			}
			checkCondition();
			// If the device was already connected using another client (BluetoothGatt object),
			// which had requested MTU change, just after connection this new MTU may be reported
			// to this client. This happens even before service discovery, effectively reporting
			// the device ready (as init queue is still null at this time).
			// This check should help.
			if (servicesDiscovered) {
				nextRequest(true);
			}
		}

		/**
		 * Callback indicating the connection parameters were updated. Works on Android 8+.
		 *
		 * @param gatt     GATT client involved.
		 * @param interval Connection interval used on this connection, 1.25ms unit.
		 *                 Valid range is from 6 (7.5ms) to 3200 (4000ms).
		 * @param latency  Slave latency for the connection in number of connection events.
		 *                 Valid range is from 0 to 499.
		 * @param timeout  Supervision timeout for this connection, in 10ms unit.
		 *                 Valid range is from 10 (0.1s) to 3200 (32s)
		 * @param status   {@link BluetoothGatt#GATT_SUCCESS} if the connection has been updated
		 *                 successfully.
		 */
		@RequiresApi(api = Build.VERSION_CODES.O)
		// @Override
		@Keep
		public void onConnectionUpdated(@NonNull final BluetoothGatt gatt,
										@IntRange(from = 6, to = 3200) final int interval,
										@IntRange(from = 0, to = 499) final int latency,
										@IntRange(from = 10, to = 3200) final int timeout,
										final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () ->
						"Connection parameters updated " +
						"(interval: " + (interval * 1.25) + "ms," +
						" latency: " + latency + ", timeout: " + (timeout * 10) + "ms)");
				BleManagerHandler.this.interval = interval;
				BleManagerHandler.this.latency = latency;
				BleManagerHandler.this.timeout = timeout;
				// Notify the listener, if set.
				BleManagerHandler.this.onConnectionUpdated(gatt, interval, latency, timeout);
				final ConnectionParametersUpdatedCallback cpuc = connectionParametersUpdatedCallback;
				if (cpuc != null) {
					cpuc.onConnectionUpdated(gatt.getDevice(), interval, latency, timeout);
				}
				// This callback may be called af any time, also when some other request is executed
				if (request instanceof final ConnectionPriorityRequest cpr) {
					cpr.notifyConnectionPriorityChanged(gatt.getDevice(), interval, latency, timeout);
					cpr.notifySuccess(gatt.getDevice());
				}
				if (serviceDiscoveryRequested) {
					log(Log.VERBOSE, () -> "Discovering services...");
					log(Log.DEBUG, () -> "gatt.discoverServices()");
					bluetoothGatt.discoverServices();
				}
			} else if (status == 0x3b) { // HCI_ERR_UNACCEPT_CONN_INTERVAL
				Log.e(TAG, "onConnectionUpdated received status: Unacceptable connection interval, " +
						"interval: " + interval + ", latency: " + latency + ", timeout: " + timeout);
				log(Log.WARN, () ->
						"Connection parameters update failed with status: " +
						"UNACCEPT CONN INTERVAL (0x3b) (interval: " + (interval * 1.25) + "ms, " +
						"latency: " + latency + ", timeout: " + (timeout * 10) + "ms)");

				// This callback may be called af any time, also when some other request is executed
				if (request instanceof final ConnectionPriorityRequest cpr) {
					cpr.notifyFail(gatt.getDevice(), status);
					awaitingRequest = null;
				}
			} else {
				Log.e(TAG, "onConnectionUpdated received status: " + status + ", " +
						"interval: " + interval + ", latency: " + latency + ", timeout: " + timeout);
				log(Log.WARN, () ->
						"Connection parameters update failed with " +
						"status " + status + " (interval: " + (interval * 1.25) + "ms, " +
						"latency: " + latency + ", timeout: " + (timeout * 10) + "ms)");

				// This callback may be called af any time, also when some other request is executed
				if (request instanceof final ConnectionPriorityRequest cpr) {
					cpr.notifyFail(gatt.getDevice(), status);
					awaitingRequest = null;
				}
				postCallback(c -> c.onError(gatt.getDevice(), ERROR_CONNECTION_PRIORITY_REQUEST, status));
			}
			if (connectionPriorityOperationInProgress) {
				connectionPriorityOperationInProgress = false;
				checkCondition();
				nextRequest(true);
			}
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		@Override
		public void onPhyUpdate(@NonNull final BluetoothGatt gatt,
								@PhyValue final int txPhy, @PhyValue final int rxPhy,
								final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () ->
						"PHY updated (TX: " + ParserUtils.phyToString(txPhy) +
						", RX: " + ParserUtils.phyToString(rxPhy) + ")");
				// Samsung S8 fails to reconnect when PHY LE 2M request is sent before service discovery.
				earlyPhyLe2MRequest = earlyPhyLe2MRequest ||
						(txPhy == BluetoothDevice.PHY_LE_2M && !servicesDiscovered);
				if (request instanceof final PhyRequest pr) {
					pr.notifyPhyChanged(gatt.getDevice(), txPhy, rxPhy);
					pr.notifySuccess(gatt.getDevice());
				}
			} else {
				log(Log.WARN, () -> "PHY updated failed with status " + status);
				if (request instanceof final PhyRequest pr) {
					pr.notifyFail(gatt.getDevice(), status);
					awaitingRequest = null;
				}
				postCallback(c -> c.onError(gatt.getDevice(), ERROR_PHY_UPDATE, status));
			}
			// PHY update may be requested by the other side, or the Android, without explicitly
			// requesting it. Proceed with the queue only when update was requested.
			if (checkCondition() || request instanceof PhyRequest) {
				nextRequest(true);
			}
		}

		@RequiresApi(api = Build.VERSION_CODES.O)
		@Override
		public void onPhyRead(@NonNull final BluetoothGatt gatt,
							  @PhyValue final int txPhy, @PhyValue final int rxPhy,
							  final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () ->
						"PHY read (TX: " + ParserUtils.phyToString(txPhy) +
						", RX: " + ParserUtils.phyToString(rxPhy) + ")");
				if (request instanceof final PhyRequest pr) {
					pr.notifyPhyChanged(gatt.getDevice(), txPhy, rxPhy);
					request.notifySuccess(gatt.getDevice());
				}
			} else {
				log(Log.WARN, () -> "PHY read failed with status " + status);
				if (request instanceof final PhyRequest pr) {
					pr.notifyFail(gatt.getDevice(), status);
				}
				awaitingRequest = null;
				postCallback(c -> c.onError(gatt.getDevice(), ERROR_READ_PHY, status));
			}
			checkCondition();
			nextRequest(true);
		}

		@Override
		public void onReadRemoteRssi(@NonNull final BluetoothGatt gatt,
									 @IntRange(from = -128, to = 20) final int rssi,
									 final int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				log(Log.INFO, () -> "Remote RSSI received: " + rssi + " dBm");
				if (request instanceof final ReadRssiRequest rrr) {
					rrr.notifyRssiRead(gatt.getDevice(), rssi);
					rrr.notifySuccess(gatt.getDevice());
				}
			} else {
				log(Log.WARN, () -> "Reading remote RSSI failed with status " + status);
				if (request instanceof final ReadRssiRequest rrr) {
					rrr.notifyFail(gatt.getDevice(), status);
				}
				awaitingRequest = null;
				postCallback(c -> c.onError(gatt.getDevice(), ERROR_READ_RSSI, status));
			}
			checkCondition();
			nextRequest(true);
		}
	};

	private int mapDisconnectStatusToReason(final int status) {
		return switch (status) {
			case GattError.GATT_CONN_TERMINATE_LOCAL_HOST ->
					ConnectionObserver.REASON_TERMINATE_LOCAL_HOST;
			case GattError.GATT_CONN_TERMINATE_PEER_USER ->
					ConnectionObserver.REASON_TERMINATE_PEER_USER;
			// When a remote device disconnects, some phones return the TIMEOUT error, while other
			// just SUCCESS. Anyway, in both cases the device has not been disconnected by the
			// user, so the reason should be the TIMEOUT.
			case GattError.GATT_CONN_TIMEOUT, GattError.GATT_SUCCESS ->
					ConnectionObserver.REASON_TIMEOUT;
			default -> ConnectionObserver.REASON_UNKNOWN;
		};
	}

	final void onCharacteristicReadRequest(@NonNull final BluetoothGattServer server,
										   @NonNull final BluetoothDevice device,
										   final int requestId, final int offset,
										   @NonNull final BluetoothGattCharacteristic characteristic) {
		log(Log.DEBUG, () -> "[Server callback] Read request for characteristic " + characteristic.getUuid()
				+ " (requestId=" + requestId + ", offset: " + offset + ")");
		if (offset == 0)
			log(Log.INFO, () -> "[Server] READ request for characteristic " + characteristic.getUuid() + " received");

		// The data can be obtained fro 3 different places:
		// 1. The Data provider, assigned to the characteristic
		// 2. The characteristic itself
		// 3. The WaitForReadRequest object

		// First, let's check the data provider. We do it only when offset == 0 and then
		// save the value in the characteristic.
		final DataProvider dataProvider = dataProviders.get(characteristic);
		byte[] data = offset == 0 && dataProvider != null ? dataProvider.getData(device) : null;
		if (data != null) {
			// If the data were returned, store them for later use.
			// The client can request the data in multiple packets.
			assign(characteristic, data);
		} else {
			// If there was no provider or the data were null, or the offset is greater than 0,
			// get the value from the descriptor.
			data = characteristicValues == null || !characteristicValues.containsKey(characteristic)
					? characteristic.getValue()
					: characteristicValues.get(characteristic);
		}

		WaitForReadRequest waitForReadRequest = null;
		// Then, try to get the data from the WaitForReadRequest if the request awaits,
		if (awaitingRequest instanceof WaitForReadRequest
				// is registered for this characteristic
				&& awaitingRequest.characteristic == characteristic
				// and didn't have a trigger, or the trigger was started
				// (not necessarily completed)
				&& !awaitingRequest.isTriggerPending()) {
			waitForReadRequest = (WaitForReadRequest) awaitingRequest;
			// The data set in the WaitForReadRequest have priority over the data provider
			// and the value of the characteristic.
			waitForReadRequest.setDataIfNull(data);
			data = waitForReadRequest.getData(mtu);
		}

		// If data are longer than MTU - 1, cut the array.
		// Only ATT_MTU - 1 bytes can be sent in a single response.
		// If the data are longer, the client will request another read with an offset.
		if (data != null && data.length > mtu - 1) {
			data = Bytes.copy(data, offset, mtu - 1);
		}

		sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, offset, data);

		if (waitForReadRequest != null) {
			waitForReadRequest.notifyPacketRead(device, data);

			// If the request is complete, start next one.
			if (!waitForReadRequest.hasMore() && (data == null || data.length < mtu - 1)) {
				log(Log.INFO, () -> "Wait for read complete");
				waitForReadRequest.notifySuccess(device);
				awaitingRequest = null;
				nextRequest(true);
			}
		} else if (checkCondition()) {
			nextRequest(true);
		}
	}

	final void onCharacteristicWriteRequest(@NonNull final BluetoothGattServer server,
											@NonNull final BluetoothDevice device, final int requestId,
											@NonNull final BluetoothGattCharacteristic characteristic,
											final boolean preparedWrite, final boolean responseNeeded,
											final int offset, @NonNull final byte[] value) {
		log(Log.DEBUG, () ->
				"[Server callback] Write " + (responseNeeded ? "request" : "command")
				+ " to characteristic " + characteristic.getUuid()
				+ " (requestId=" + requestId + ", prepareWrite=" + preparedWrite + ", responseNeeded="
				+ responseNeeded + ", offset: " + offset + ", value=" + ParserUtils.parseDebug(value) + ")");
		if (offset == 0) {
			log(Log.INFO, () -> {
				final String type = responseNeeded ? "WRITE REQUEST" : "WRITE COMMAND";
				final String option = preparedWrite ? "Prepare " : "";
				return "[Server] " + option + type + " for characteristic " + characteristic.getUuid()
						+ " received, value: " + ParserUtils.parse(value);
			});
		}

		if (responseNeeded) {
			sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, offset, value);
		}

		// If Prepare Write or Long Write is sent, store the data in a temporary queue until it's executed.
		if (preparedWrite) {
			if (preparedValues == null) {
				preparedValues = new LinkedList<>();
			}
			if (offset == 0) {
				// Add new value to the operations.
				preparedValues.offer(new Pair<>(characteristic, value));
			} else {
				// Concatenate the value to the end of previous value, if the previous request was
				// also for the same characteristic.
				final Pair<Object, byte[]> last = preparedValues.peekLast();
				if (last != null && characteristic.equals(last.first)) {
					preparedValues.pollLast();
					preparedValues.offer(new Pair<>(characteristic, Bytes.concat(last.second, value, offset)));
				} else {
					prepareError = BluetoothGatt.GATT_INVALID_OFFSET;
				}
			}
		} else {
			// Otherwise, save the data immediately.
			if (assignAndNotify(device, characteristic, value) || checkCondition()) {
				nextRequest(true);
			}
		}
	}

	final void onDescriptorReadRequest(@NonNull final BluetoothGattServer server,
									   @NonNull final BluetoothDevice device, final int requestId, final int offset,
									   @NonNull final BluetoothGattDescriptor descriptor) {
		log(Log.DEBUG, () ->
				"[Server callback] Read request for descriptor " + descriptor.getUuid() +
				" (requestId=" + requestId + ", offset: " + offset + ")");
		if (offset == 0)
			log(Log.INFO, () -> "[Server] READ request for descriptor " + descriptor.getUuid() + " received");

		// The data can be obtained fro 3 different places:
		// 1. The Data provider, assigned to the descriptor
		// 2. The descriptor itself
		// 3. The WaitForReadRequest object

		// First, let's check the data provider. We do it only when offset == 0 and then
		// save the value in the characteristic.
		final DataProvider dataProvider = dataProviders.get(descriptor);
		byte[] data = offset == 0 && dataProvider != null ? dataProvider.getData(device) : null;
		if (data != null) {
			// If the data were returned, store them for later use.
			// The client can request the data in multiple packets.
			assign(descriptor, data);
		} else {
			// If there was no provider or the data were null, or the offset is greater than 0,
			// get the value from the descriptor.
			data = descriptorValues == null || !descriptorValues.containsKey(descriptor)
					? descriptor.getValue()
					: descriptorValues.get(descriptor);
		}

		WaitForReadRequest waitForReadRequest = null;
		// Then, try to get the data from the WaitForReadRequest if the request awaits,
		if (awaitingRequest instanceof WaitForReadRequest
				// is registered for this descriptor
				&& awaitingRequest.descriptor == descriptor
				// and didn't have a trigger, or the trigger was started
				// (not necessarily completed)
				&& !awaitingRequest.isTriggerPending()) {
			waitForReadRequest = (WaitForReadRequest) awaitingRequest;
			// The data set in the WaitForReadRequest have priority over the data provider
			// and the value of the characteristic.
			waitForReadRequest.setDataIfNull(data);
			data = waitForReadRequest.getData(mtu);
		}

		// If data are longer than MTU - 1, cut the array.
		// Only ATT_MTU - 1 bytes can be sent in a single response.
		// If the data are longer, the client will request another read with an offset.
		if (data != null && data.length > mtu - 1) {
			data = Bytes.copy(data, offset, mtu - 1);
		}

		sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, offset, data);

		if (waitForReadRequest != null) {
			waitForReadRequest.notifyPacketRead(device, data);

			// If the request is complete, start next one.
			if (!waitForReadRequest.hasMore() && (data == null || data.length < mtu - 1)) {
				waitForReadRequest.notifySuccess(device);
				awaitingRequest = null;
				nextRequest(true);
			}
		} else if (checkCondition()) {
			nextRequest(true);
		}
	}

	final void onDescriptorWriteRequest(@NonNull final BluetoothGattServer server,
										@NonNull final BluetoothDevice device, final int requestId,
										@NonNull final BluetoothGattDescriptor descriptor,
										final boolean preparedWrite, final boolean responseNeeded,
										final int offset, @NonNull final byte[] value) {
		log(Log.DEBUG, () ->
				"[Server callback] Write " + (responseNeeded ? "request" : "command")
				+ " to descriptor " + descriptor.getUuid()
				+ " (requestId=" + requestId + ", prepareWrite=" + preparedWrite + ", responseNeeded="
				+ responseNeeded + ", offset: " + offset + ", value=" + ParserUtils.parseDebug(value) + ")");
		if (offset == 0) {
			log(Log.INFO, () -> {
				final String type = responseNeeded ? "WRITE REQUEST" : "WRITE COMMAND";
				final String option = preparedWrite ? "Prepare " : "";
				return "[Server] " + option + type + " request for descriptor " + descriptor.getUuid()
						+ " received, value: " + ParserUtils.parse(value);
			});
		}

		if (responseNeeded) {
			sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, offset, value);
		}

		// If Prepare Write or Long Write is sent, store the data in a temporary queue until it's executed.
		if (preparedWrite) {
			if (preparedValues == null) {
				preparedValues = new LinkedList<>();
			}
			if (offset == 0) {
				// Add new value to the operations.
				preparedValues.offer(new Pair<>(descriptor, value));
			} else {
				// Concatenate the value to the end of previous value, if the previous request was
				// also for the same descriptor.
				final Pair<Object, byte[]> last = preparedValues.peekLast();
				if (last != null && descriptor.equals(last.first)) {
					preparedValues.pollLast();
					preparedValues.offer(new Pair<>(descriptor, Bytes.concat(last.second, value, offset)));
				} else {
					prepareError = BluetoothGatt.GATT_INVALID_OFFSET;
				}
			}
		} else {
			// Otherwise, save the data immediately.
			if (assignAndNotify(device, descriptor, value) || checkCondition()) {
				nextRequest(true);
			}
		}
	}

	final void onExecuteWrite(@NonNull final BluetoothGattServer server,
							  @NonNull final BluetoothDevice device, final int requestId,
							  final boolean execute) {
		log(Log.DEBUG, () ->
				"[Server callback] Execute write request (requestId=" + requestId + ", execute=" + execute + ")");
		if (execute) {
			final Deque<Pair<Object, byte[]>> values = preparedValues;
			log(Log.INFO, () -> "[Server] Execute write request received");
			preparedValues = null;
			if (prepareError != 0) {
				sendResponse(server, device, prepareError, requestId, 0, null);
				prepareError = 0;
				return;
			}
			sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, 0, null);

			if (values == null || values.isEmpty()) {
				return;
			}
			boolean startNextRequest = false;
			for (final Pair<Object, byte[]> value: values) {
				if (value.first instanceof final BluetoothGattCharacteristic characteristic) {
					startNextRequest = assignAndNotify(device, characteristic, value.second) || startNextRequest;
				} else if (value.first instanceof final BluetoothGattDescriptor descriptor){
					startNextRequest = assignAndNotify(device, descriptor, value.second) || startNextRequest;
				}
			}
			if (checkCondition() || startNextRequest) {
				nextRequest(true);
			}
		} else {
			log(Log.INFO, () -> "[Server] Cancel write request received");
			preparedValues = null;
			sendResponse(server, device, BluetoothGatt.GATT_SUCCESS, requestId, 0, null);
		}
	}

	final void onNotificationSent(@NonNull final BluetoothGattServer server,
								  @NonNull final BluetoothDevice device, final int status) {
		log(Log.DEBUG, () -> "[Server callback] Notification sent (status=" + status + ")");
		if (status == BluetoothGatt.GATT_SUCCESS) {
			notifyNotificationSent(device);
		} else {
			Log.e(TAG, "onNotificationSent error " + status);
			if (request instanceof final WriteRequest wr) {
				wr.notifyFail(device, status);
			}
			awaitingRequest = null;
			onError(device, ERROR_NOTIFY, status);
		}
		checkCondition();
		nextRequest(true);
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
	final void onMtuChanged(@NonNull final BluetoothGattServer server,
							@NonNull final BluetoothDevice device,
							final int mtu) {
		log(Log.INFO, () -> "[Server] MTU changed to: " + mtu);
		BleManagerHandler.this.mtu = Math.min(515, mtu);
		nextRequest(checkCondition());
	}

	private void notifyNotificationSent(@NonNull final BluetoothDevice device) {
		if (request instanceof final WriteRequest wr) {
			switch (wr.type) {
				case NOTIFY -> log(Log.INFO, () -> "[Server] Notification sent");
				case INDICATE -> log(Log.INFO, () -> "[Server] Indication sent");
			}
			//noinspection DataFlowIssue
			wr.notifyPacketSent(device, wr.characteristic.getValue());
			if (wr.hasMore()) {
				enqueueFirst(wr);
			} else {
				wr.notifySuccess(device);
			}
		}
	}

	private void notifyNotificationsDisabled(@NonNull final BluetoothDevice device) {
		if (request instanceof final WriteRequest wr) {
			switch (wr.type) {
				case NOTIFY -> log(Log.WARN, () -> "[Server] Notifications disabled");
				case INDICATE -> log(Log.WARN, () -> "[Server] Indications disabled");
			}
			wr.notifyFail(device, FailCallback.REASON_NOT_ENABLED);
		}
	}

	private void assign(@NonNull final BluetoothGattCharacteristic characteristic,
						@NonNull final byte[] value) {
		final boolean isShared = characteristicValues == null || !characteristicValues.containsKey(characteristic);
		if (isShared) {
			characteristic.setValue(value);
		} else {
			characteristicValues.put(characteristic, value);
		}
	}

	private boolean assignAndNotify(@NonNull final BluetoothDevice device,
									@NonNull final BluetoothGattCharacteristic characteristic,
									@NonNull final byte[] value) {
		assign(characteristic, value);
		// Notify listener
		ValueChangedCallback callback;
		if ((callback = valueChangedCallbacks.get(characteristic)) != null) {
			callback.notifyValueChanged(device, value);
		}

		// Check if a request awaits,
		if (awaitingRequest instanceof final WaitForValueChangedRequest waitForWrite
				// is registered for this characteristic
				&& awaitingRequest.characteristic == characteristic
				// and didn't have a trigger, or the trigger was started
				// (not necessarily completed)
				&& !awaitingRequest.isTriggerPending()) {
			if (waitForWrite.matches(value)) {
				// notify that new data was received.
				waitForWrite.notifyValueChanged(device, value);

				// If no more data are expected
				if (waitForWrite.isComplete()) {
					// notify success,
					waitForWrite.notifySuccess(device);
					// and proceed to the next request only if the trigger has completed.
					// Otherwise, the next request will be started when the request's callback
					// will be received.
					awaitingRequest = null;
					return waitForWrite.isTriggerCompleteOrNull();
				}
			}
		}
		return false;
	}

	private void assign(@NonNull final BluetoothGattDescriptor descriptor,
						@NonNull final byte[] value) {
		final boolean isShared = descriptorValues == null || !descriptorValues.containsKey(descriptor);
		if (isShared) {
			descriptor.setValue(value);
		} else {
			descriptorValues.put(descriptor, value);
		}
	}

	private boolean assignAndNotify(@NonNull final BluetoothDevice device,
									@NonNull final BluetoothGattDescriptor descriptor,
									@NonNull final byte[] value) {
		assign(descriptor, value);
		// Notify listener
		ValueChangedCallback callback;
		if ((callback = valueChangedCallbacks.get(descriptor)) != null) {
			callback.notifyValueChanged(device, value);
		}

		// Check if a request awaits,
		if (awaitingRequest instanceof final WaitForValueChangedRequest waitForWrite
				// is registered for this descriptor
				&& awaitingRequest.descriptor == descriptor
				// and didn't have a trigger, or the trigger was started
				// (not necessarily completed)
				&& !awaitingRequest.isTriggerPending()) {
			if (waitForWrite.matches(value)) {
				// notify that new data was received.
				waitForWrite.notifyValueChanged(device, value);

				// If no more data are expected
				if (waitForWrite.isComplete()) {
					// notify success,
					waitForWrite.notifySuccess(device);
					// and proceed to the next request only if the trigger has completed.
					// Otherwise, the next request will be started when the request's callback
					// will be received.
					awaitingRequest = null;
					return waitForWrite.isTriggerCompleteOrNull();
				}
			}
		}
		return false;
	}

	private void sendResponse(@NonNull final BluetoothGattServer server,
							  @NonNull final BluetoothDevice device, final int status,
							  final int requestId, final int offset,
							  @Nullable final byte[] response) {
		String msg = switch (status) {
			case BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS";
			case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED";
			case BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET";
			default -> throw new InvalidParameterException();
		};
		log(Log.DEBUG, () ->
				"server.sendResponse(" + msg + ", offset=" + offset +
						", value=" + ParserUtils.parseDebug(response) + ")");
		server.sendResponse(device, requestId, status, offset, response);
		log(Log.VERBOSE, () -> "[Server] Response sent");
	}

	private boolean checkCondition() {
		if (awaitingRequest instanceof final ConditionalWaitRequest<?> cwr) {
			if (cwr.isFulfilled()) {
				log(Log.INFO, () -> "Condition fulfilled");
				cwr.notifySuccess(bluetoothDevice);
				awaitingRequest = null;
				return true;
			}
		}
		return false;
	}

	/**
	 * Executes the next request. If the last element from the initialization queue has
	 * been executed the {@link #onDeviceReady()} callback is called.
	 */
	@SuppressLint("MissingPermission")
	private synchronized void nextRequest(final boolean force) {
		if (force && operationInProgress) {
			operationInProgress = awaitingRequest != null;
		}

		if (operationInProgress) {
			return;
		}
		final BluetoothDevice bluetoothDevice = this.bluetoothDevice;

		// Get the first request from the init queue
		Request request = null;
		try {
			// If Request set is present, try taking next request from it
			if (requestQueue != null) {
				if (requestQueue.hasMore()) {
					//noinspection DataFlowIssue
					request = requestQueue.getNext().setRequestHandler(this);
				} else {
					if (requestQueue instanceof final ReliableWriteRequest rwr) {
						if (rwr.isCancelled()) {
							requestQueue.notifyFail(bluetoothDevice, FailCallback.REASON_CANCELLED);
						}
					}
					// Set is completed. This is a NOOP if the request has failed.
					requestQueue.notifySuccess(bluetoothDevice);
					requestQueue = null;
				}
			}
			// Request wasn't obtained from the request set? Take next one from the queue.
			if (request == null) {
				request = initQueue != null ? initQueue.poll() : null;
			}
		} catch (final Exception e) {
			// On older Android versions poll() may in some cases throw NoSuchElementException,
			// as it's using removeFirst() internally.
			// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/37
		}

		// Are we done with initializing?
		if (request == null) {
			if (initQueue != null) {
				initQueue = null; // release the queue

				// Set the 'operation in progress' flag, so any request made in onDeviceReady()
				// will not start new nextRequest() call.
				operationInProgress = true;
				ready = true;
				manager.onDeviceReady();
				if (bluetoothDevice != null) {
					postCallback(c -> c.onDeviceReady(bluetoothDevice));
					postConnectionStateChange(o -> o.onDeviceReady(bluetoothDevice));
				}
				if (connectRequest != null) {
					connectRequest.notifySuccess(connectRequest.getDevice());
					connectRequest = null;
				}
			}
			// If so, we can continue with the task queue
			try {
				request = taskQueue.remove();
			} catch (final Exception e) {
				// No more tasks to perform
				operationInProgress = false;
				this.request = null;
				manager.onManagerReady();
				return;
			}
		}

		// If the request has already been cancelled, proceed to the next one.
		if (request.finished) {
			nextRequest(false);
			return;
		}

		boolean result = false;
		operationInProgress = true;
		this.request = request;

		if (request instanceof final AwaitingRequest<?> r) {
			// The WAIT_FOR_* request types may override the request with a trigger.
			// This is to ensure that the trigger is done after the awaitingRequest was set.
			int requiredProperty = switch (request.type) {
				case WAIT_FOR_NOTIFICATION -> BluetoothGattCharacteristic.PROPERTY_NOTIFY;
				case WAIT_FOR_INDICATION -> BluetoothGattCharacteristic.PROPERTY_INDICATE;
				case WAIT_FOR_READ -> BluetoothGattCharacteristic.PROPERTY_READ;
				case WAIT_FOR_WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
						| BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
						| BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
				default -> 0;
			};
			result = connected && bluetoothDevice != null
					&& (r.characteristic == null ||
					   (r.characteristic.getProperties() & requiredProperty) != 0);
			if (result) {
				if (r instanceof final ConditionalWaitRequest<?> cwr) {
					log(Log.VERBOSE, () -> "Waiting for fulfillment of condition...");
					if (cwr.isFulfilled()) {
						cwr.notifyStarted(bluetoothDevice);
						log(Log.INFO, () -> "Condition fulfilled");
						cwr.notifySuccess(bluetoothDevice);
						nextRequest(true);
						return;
					}
				}
				if (r instanceof WaitForReadRequest) {
					log(Log.VERBOSE, () -> "Waiting for read request...");
				}
				if (r instanceof WaitForValueChangedRequest) {
					log(Log.VERBOSE, () -> "Waiting for value change...");
				}
				awaitingRequest = r;

				if (r.getTrigger() != null) {
					// Call notifyStarted for the awaiting request.
					r.notifyStarted(bluetoothDevice);

					// If the request has another request set as a trigger, update the
					// request with the trigger.
					this.request = request = r.getTrigger();
				}
			}
		}
		// Call notifyStarted on the request before it's executed.
		if (request instanceof final ConnectRequest cr) {
			// When the Connect Request is started, the bluetoothDevice is not set yet.
			// It may also be a connect request to a different device, which is an error
			// that is handled in internalConnect()
			cr.notifyStarted(cr.getDevice());
		} else {
			if (bluetoothDevice != null) {
				request.notifyStarted(bluetoothDevice);
			} else {
				// The device wasn't connected before. Target is unknown.
				request.notifyInvalidRequest();

				awaitingRequest = null;
				nextRequest(true);
				return;
			}
		}

		// At this point the bluetoothDevice is either null, and the request is a ConnectRequest,
		// or not a null.
		assert bluetoothDevice != null || request.type == Request.Type.CONNECT;

		switch (request.type) {
			case CONNECT: {
				//noinspection DataFlowIssue
				final ConnectRequest cr = (ConnectRequest) request;
				connectRequest = cr;
				this.request = null;
				result = internalConnect(cr.getDevice(), cr);
				break;
			}
			case DISCONNECT: {
				internalDisconnect(ConnectionObserver.REASON_SUCCESS);
				// If a disconnect request failed, it has already been notified at this point,
				// therefore result is a success (true).
				result = true;
				break;
			}
			case ENSURE_BOND: {
				result = internalCreateBond(true);
				break;
			}
			case CREATE_BOND: {
				result = internalCreateBond(false);
				break;
			}
			case REMOVE_BOND: {
				result = internalRemoveBond();
				break;
			}
			case SET: {
				//noinspection DataFlowIssue
				requestQueue = (RequestQueue) request;
				nextRequest(true);
				return;
			}
			case READ: {
				result = internalReadCharacteristic(request.characteristic);
				break;
			}
			case WRITE: {
				//noinspection DataFlowIssue
				final WriteRequest wr = (WriteRequest) request;
				result = internalWriteCharacteristic(wr.characteristic, wr.getData(mtu), wr.getWriteType());
				break;
			}
			case READ_DESCRIPTOR: {
				result = internalReadDescriptor(request.descriptor);
				break;
			}
			case WRITE_DESCRIPTOR: {
				//noinspection DataFlowIssue
				final WriteRequest wr = (WriteRequest) request;
				result = internalWriteDescriptor(wr.descriptor, wr.getData(mtu));
				break;
			}
			case NOTIFY:
			case INDICATE: {
				//noinspection DataFlowIssue
				final WriteRequest wr = (WriteRequest) request;
				final byte[] data = wr.getData(mtu);
				if (wr.characteristic != null) {
					wr.characteristic.setValue(data);
					if (characteristicValues != null && characteristicValues.containsKey(wr.characteristic))
						characteristicValues.put(wr.characteristic, data);
				}
				result = internalSendNotification(wr.characteristic, request.type == Request.Type.INDICATE, data);
				break;
			}
			case SET_VALUE: {
				//noinspection DataFlowIssue
				final SetValueRequest svr = (SetValueRequest) request;
				if (svr.characteristic != null) {
					if (characteristicValues != null && characteristicValues.containsKey(svr.characteristic))
						characteristicValues.put(svr.characteristic, svr.getData(mtu));
					else
						svr.characteristic.setValue(svr.getData(mtu));
					result = true;
					svr.notifySuccess(bluetoothDevice);
					nextRequest(true);
				}
				break;
			}
			case SET_DESCRIPTOR_VALUE: {
				//noinspection DataFlowIssue
				final SetValueRequest svr = (SetValueRequest) request;
				if (svr.descriptor != null) {
					if (descriptorValues != null && descriptorValues.containsKey(svr.descriptor))
						descriptorValues.put(svr.descriptor, svr.getData(mtu));
					else
						svr.descriptor.setValue(svr.getData(mtu));
					result = true;
					svr.notifySuccess(bluetoothDevice);
					nextRequest(true);
				}
				break;
			}
			case BEGIN_RELIABLE_WRITE: {
				result = internalBeginReliableWrite();
				// There is no callback for begin reliable write request.
				// Notify success and start next request immediately.
				if (result) {
					this.request.notifySuccess(bluetoothDevice);
					nextRequest(true);
					return;
				}
				break;
			}
			case EXECUTE_RELIABLE_WRITE: {
				result = internalExecuteReliableWrite();
				break;
			}
			case ABORT_RELIABLE_WRITE: {
				result = internalAbortReliableWrite();
				break;
			}
			case ENABLE_NOTIFICATIONS: {
				result = internalEnableNotifications(request.characteristic);
				break;
			}
			case ENABLE_INDICATIONS: {
				result = internalEnableIndications(request.characteristic);
				break;
			}
			case DISABLE_NOTIFICATIONS: {
				result = internalDisableNotifications(request.characteristic);
				break;
			}
			case DISABLE_INDICATIONS: {
				result = internalDisableIndications(request.characteristic);
				break;
			}
			case READ_BATTERY_LEVEL: {
				result = internalReadBatteryLevel();
				break;
			}
			case ENABLE_BATTERY_LEVEL_NOTIFICATIONS: {
				result = internalSetBatteryNotifications(true);
				break;
			}
			case DISABLE_BATTERY_LEVEL_NOTIFICATIONS: {
				result = internalSetBatteryNotifications(false);
				break;
			}
			case ENABLE_SERVICE_CHANGED_INDICATIONS: {
				result = ensureServiceChangedEnabled();
				break;
			}
			case REQUEST_MTU: {
				//noinspection DataFlowIssue
				final MtuRequest mr = (MtuRequest) request;
				if (mtu != mr.getRequiredMtu()
						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					result = internalRequestMtu(mr.getRequiredMtu());
				} else {
					result = connected;
					if (result) {
						mr.notifyMtuChanged(bluetoothDevice, mtu);
						mr.notifySuccess(bluetoothDevice);
						nextRequest(true);
						return;
					}
				}
				break;
			}
			case REQUEST_CONNECTION_PRIORITY: {
				//noinspection DataFlowIssue
				final ConnectionPriorityRequest cpr = (ConnectionPriorityRequest) request;
				connectionPriorityOperationInProgress = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					result = internalRequestConnectionPriority(cpr.getRequiredPriority());

					// There is no callback for requestConnectionPriority(...) before Android Oreo.
					// Let's give it some time to finish as the request is an asynchronous operation.
					// Note:
					// According to https://github.com/NordicSemiconductor/Android-BLE-Library/issues/186
					// some Android 8+ phones don't call this callback. Let's make sure it will be
					// called in any case.
					if (result) {
						postDelayed(() -> {
							if (cpr.notifySuccess(bluetoothDevice)) {
								connectionPriorityOperationInProgress = false;
								nextRequest(true);
							}
						}, 200);
					} else {
						connectionPriorityOperationInProgress = false;
					}
				}
				break;
			}
			case SET_PREFERRED_PHY: {
				//noinspection DataFlowIssue
				final PhyRequest pr = (PhyRequest) request;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					result = internalSetPreferredPhy(
							pr.getPreferredTxPhy(),
							pr.getPreferredRxPhy(),
							pr.getPreferredPhyOptions()
					);
					if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
						// There seems to be a bug on Android 13, where there is no callback
						// for setPreferredPhy(...). onPhyUpdate(...) should be called, but it is not.
						// See: https://github.com/NordicSemiconductor/Android-BLE-Library/issues/414
						// However, the operation seems to complete successfully.
						// As a workaround, let's read the PHYs and notify the user.
						handler.postDelayed(() -> {
							if (!pr.finished) {
								log(Log.WARN, () -> "Callback not received in 1000 ms");
								internalReadPhy();
							}
						}, 1000);
					}
				} else {
					result = connected;
					if (result) {
						pr.notifyLegacyPhy(bluetoothDevice);
						pr.notifySuccess(bluetoothDevice);
						nextRequest(true);
						return;
					}
				}
				break;
			}
			case READ_PHY: {
				//noinspection DataFlowIssue
				final PhyRequest pr = (PhyRequest) request;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					result = internalReadPhy();
				} else {
					result = connected;
					if (result) {
						pr.notifyLegacyPhy(bluetoothDevice);
						pr.notifySuccess(bluetoothDevice);
						nextRequest(true);
						return;
					}
				}
				break;
			}
			case READ_RSSI: {
				final Request r = request;
				result = internalReadRssi();
				if (result) {
					postDelayed(() -> {
						// This check makes sure that only the failed request will be notified,
						// not some subsequent one.
						if (this.request == r) {
							r.notifyFail(bluetoothDevice, FailCallback.REASON_TIMEOUT);
							nextRequest(true);
						}
					}, 1000);
				}
				break;
			}
			case REFRESH_CACHE: {
				final Request r = request;
				result = internalRefreshDeviceCache();
				if (result) {
					postDelayed(() -> {
						log(Log.INFO, () -> "Cache refreshed");
						r.notifySuccess(bluetoothDevice);
						this.request = null;
						if (awaitingRequest != null) {
							awaitingRequest.notifyFail(bluetoothDevice, FailCallback.REASON_NULL_ATTRIBUTE);
							awaitingRequest = null;
						}
						emptyTasks(FailCallback.REASON_NULL_ATTRIBUTE);
						final BluetoothGatt bluetoothGatt = this.bluetoothGatt;
						if (connected && bluetoothGatt != null) {
							// Invalidate all services and characteristics
							manager.onServicesInvalidated();
							onDeviceDisconnected();
							// And discover services again
							serviceDiscoveryRequested = true;
							servicesDiscovered = false;
							log(Log.VERBOSE, () -> "Discovering Services...");
							log(Log.DEBUG, () -> "gatt.discoverServices()");
							bluetoothGatt.discoverServices();
						}
					}, 200);
				}
				break;
			}
			case SLEEP: {
				//noinspection DataFlowIssue
				final SleepRequest sr = (SleepRequest) request;
				log(Log.DEBUG, () -> "sleep(" + sr.timeout + ")");
				result = true;
				// The Sleep request will timeout after the given time,
				// and onRequestTimeout will be called.
				break;
			}
			case WAIT_FOR_NOTIFICATION:
			case WAIT_FOR_INDICATION:
				// Those were handled before.
				break;
		}
		// The result may be false if given characteristic or descriptor were not found
		// on the device, or the feature is not supported on the Android.
		// In that case, proceed with next operation and ignore the one that failed.
		if (!result && bluetoothDevice != null) {
			request.notifyFail(bluetoothDevice,
					connected ?
							FailCallback.REASON_NULL_ATTRIBUTE :
							BluetoothAdapter.getDefaultAdapter().isEnabled() ?
									FailCallback.REASON_DEVICE_DISCONNECTED :
									FailCallback.REASON_BLUETOOTH_DISABLED);
			awaitingRequest = null;
			connectionPriorityOperationInProgress = false;
			nextRequest(true);
		}
	}

	// Helper methods

	/**
	 * Returns true if this descriptor is from the Service Changed characteristic.
	 *
	 * @param descriptor the descriptor to be checked
	 * @return true if the descriptor belongs to the Service Changed characteristic
	 */
	private boolean isServiceChangedCCCD(@Nullable final BluetoothGattDescriptor descriptor) {
		return descriptor != null &&
				BleManager.SERVICE_CHANGED_CHARACTERISTIC.equals(descriptor.getCharacteristic().getUuid());
	}

	/**
	 * Returns true if this is the Service Changed characteristic.
	 *
	 * @param characteristic the characteristic to be checked
	 * @return true if it is the Service Changed characteristic
	 */
	private boolean isServiceChangedCharacteristic(@Nullable final BluetoothGattCharacteristic characteristic) {
		return characteristic != null &&
				BleManager.SERVICE_CHANGED_CHARACTERISTIC.equals(characteristic.getUuid());
	}

	/**
	 * Returns true if the characteristic is the Battery Level characteristic.
	 *
	 * @param characteristic the characteristic to be checked
	 * @return true if the characteristic is the Battery Level characteristic.
	 */
	@Deprecated
	private boolean isBatteryLevelCharacteristic(@Nullable final BluetoothGattCharacteristic characteristic) {
		return characteristic != null &&
				BleManager.BATTERY_LEVEL_CHARACTERISTIC.equals(characteristic.getUuid());
	}

	/**
	 * Returns true if this descriptor is a Client Characteristic Configuration descriptor (CCCD).
	 *
	 * @param descriptor the descriptor to be checked
	 * @return true if the descriptor is a CCCD
	 */
	private boolean isCCCD(@Nullable final BluetoothGattDescriptor descriptor) {
		return descriptor != null &&
				BleManager.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID.equals(descriptor.getUuid());
	}

	@FunctionalInterface
	private interface Loggable {
		String log();
	}

	private void log(@LogPriority final int priority, @NonNull final Loggable message) {
		if (priority >= manager.getMinLogPriority()) {
			manager.log(priority, message.log());
		}
	}
}

package com.smartdevicelink.transport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.smartdevicelink.util.DebugTool;

import java.io.IOException;

import static com.smartdevicelink.transport.USBTransport.isAccessorySupported;

/**
 * Created by swatanabe on 2018/02/20.
 */

public class UsbAccessoryDriver {
	static private UsbAccessoryDriver sInstance;
	private Context mContext;

	public enum State {
		IDLE,
		LISTENING,
		WAITING_PERMISSION,     // This state is used only by UsbAccessoryDriver.
		CONNECTING,
		CONNECTED,
		DISCONNECTED,
		STOPPING,
	}
	private State mState;
	public static final String ACTION_USB_PERMISSION =
			"com.smartdevicelink.usb.ACTION_USB_PERMISSION";

	private static final long USB_PERMISSION_DIALOG_DELAY_MSEC = 1000;
	private static final long DIALOG_MAY_BE_DISMISSED_NSEC = 1000000000; // 1 sec
	private static final long USB_PERMISSION_DIALOG_RETRY_DELAY_MSEC = 2000;
	private static final long USB_PERMISSION_DIALOG_EXTRA_DELAY_MSEC = 2000;

	// Cable check timer settings
	private static final int CABLE_CHECK_AFTER_START_RETRY = 20;
	private static final int CABLE_CHECK_INTERVAL_MSEC = 1000;

	private boolean mPowerConnected;
	private boolean mUsbChattering;
	private long mLastPowerDisconnectedNsec = -1;
	private long mPermissionRequestedTimeNsec = -1;
	private long mActivitySwitchedTimeNsec = -1;
	private long mPermissionDeniedTimeNsec = -1;

	private UsbAccessory mPermissionRequestedAccessory;
	private UsbAccessoryTimer mAccessoryTimer;
	private CableCheckTimer mCableCheckTimer = new CableCheckTimer();

	private UsbAccessory mAccessory;
	private ParcelFileDescriptor mAccessoryFD;
	private Handler mHandler = new Handler(Looper.getMainLooper());

	public interface IAccessoryHandler {
		public void accessoryConnected();
		public void accessoryDisconnected();
	}
	private IAccessoryHandler mAccessoryHandler;
	public void setAccessoryHandler(IAccessoryHandler handler) {
		mAccessoryHandler = handler;
	}

	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
		private Runnable mPowerConnectHandler;
		private Runnable mDisconnectHandler;

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			DebugTool.logInfo("Receive Broadcast: " + action.toString());
			UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
			DebugTool.logInfo("Receive Broadcast: " + accessory);
			if (accessory != null) {

				if (ACTION_USB_PERMISSION.equals(action)) {
					DebugTool.logInfo("USB accessory requested permission " + accessory.toString());
					boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
					if (granted) {

						if(isUSBCableConnected()) {
							DebugTool.logInfo("Permission granted for USB accessory");
							setState(State.CONNECTING);
							openAccessory(accessory);
						} else {
							DebugTool.logInfo("Permission granted, but cable is not connected");
							disconnect();
						}
					} else {
						DebugTool.logInfo("Permission denied for USB accessory");
						mPermissionDeniedTimeNsec = System.nanoTime();

						if (mState == State.WAITING_PERMISSION &&
								mActivitySwitchedTimeNsec >= 0 &&
								mPermissionDeniedTimeNsec - mActivitySwitchedTimeNsec < DIALOG_MAY_BE_DISMISSED_NSEC) {
							// workaround B
							DebugTool.logInfo("Retrying USB permission dialog");
							requestPermissionDialog(accessory, USB_PERMISSION_DIALOG_RETRY_DELAY_MSEC);
						} else {
							disconnect();
						}
					}
				}
			}
            /*
                ACTION_USB_ACCESSORY_DETACHED launches the monitor task to poll the USB Accessory lists.
                ACTION_POWER_DISCONNECTED launches the sequences performed by USB cable removal
                such as calling disconnect() and notifying it to the UMA app.

                The reason why we use the 2 Intents for USB cable removal is the notification
                ACTION_USB_ACCESSORY_DETACHED is sometimes delayed.
                If we use ACTION_POWER_DISCONNECTED to poll the accessory lists, we would get
                an old accessory, which will be unable to use immediately,
                when ACTION_USB_ACCESSORY_DETACHED notifies.

            */
			if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				DebugTool.logInfo("USB accessory has been detached");

				if (mState == State.CONNECTED || mState == State.CONNECTING || mState == State.WAITING_PERMISSION) {
                    /*
                        In most cases, disconnect() is launched by ACTION_POWER_DISCONNECTED.
                        disconnect() called by ACTION_USB_ACCESSORY_DETACHED is an unusual case.
                        The case is, an old USB Accessory is obtained even though USB cable is
                        CONNECTED when start() is called.
                        In that case, we want to refresh the AccessoryList but we does not expect
                        ACTION_POWER_DISCONNECTED is notified. That is the why calling disconnect()
                        by ACTION_USB_ACCESSORY_DETACHED.

                     */
					disconnect();
				}
				else {
                    /*
                        We have the issue USB Accessory Permission Dialog does not appear
                        even if Request Permission is invoked. The cause is considered
                        simultaneous starts of Android Activities
                        when USB connects.
                        In that case, we can not do anything but waiting for detached event,
                        which means expecting a user interaction to disconnect and connect USB,

                        ...and changing internal state for a next open accessory.
                    */
					mState = State.DISCONNECTED;
				}

				mCableCheckTimer.stop();

				if (mAccessoryTimer != null) {
					mAccessoryTimer.stop();
				}
				mAccessoryTimer = new UsbAccessoryTimer().start();
				if (mAccessoryHandler != null) {
					mAccessoryHandler.accessoryDisconnected();
				}
			}
			else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
				mUsbChattering = true;
				cancelDisconnectHandler();
				mPowerConnectHandler = new Runnable() {
					@Override
					public void run() {
						mPowerConnected = true;
						mUsbChattering = false;
						mPowerConnectHandler = null;
					}
				};
				mHandler.postDelayed(mPowerConnectHandler, 500);
			}
			else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
				DebugTool.logInfo("UsbAccessoryDriver: ACTION_POWER_DISCONNECTED; state = " + mState.toString());
				mUsbChattering = true;
                /*
                    Disregarding ACTION_POWER_DISCONNECTED except CONNECTED state.

                    ACTION_POWER_CONNECTED/DISCONNECTED is often chattered.
                    From the perspective of actual user cases, the event is unreliable except the case.
                 */
				/**
				 *  When USB is connected on some devices (e.g. Nexus6), the following action sequence will happen:
				 *  ACTION_POWER_CONNECTED, followed by
				 *  ACTION_POWER_DISCONNECTED, followed by
				 *  ACTION_POWER_CONNECTED
				 *  These three actions will be broadcasted very quickly (within 500 msec or so).
				 *  We need to defer actual disconnect somewhat (500 msec), and make sure USB cable is actually disconnected.
				 */
				cancelPowerConnectHandler();
				cancelDisconnectHandler();
				mDisconnectHandler = new Runnable() {
					@Override
					public void run() {
						mLastPowerDisconnectedNsec = System.nanoTime();
						mPowerConnected = false;
						mUsbChattering = false;
						if (mState == State.CONNECTED || mState == State.CONNECTING || mState == State.WAITING_PERMISSION) {
							DebugTool.logInfo("UsbAccessoryDriver: actually disconnect by ACTION_POWER_DISCONNECTED; state = " + mState.toString());
							disconnect();
						}
						mDisconnectHandler = null;

					}
				};
				mHandler.postDelayed(mDisconnectHandler, 500);
			}

			// @TODO: we may need to retry here if activity switching happened here.
		}

		private void cancelPowerConnectHandler() {
			if (mPowerConnectHandler != null) {
				mHandler.removeCallbacks(mPowerConnectHandler);
				mPowerConnectHandler = null;
			}
		}

		private void cancelDisconnectHandler() {
			if (mDisconnectHandler != null) {
				//DebugTool.logInfo("UsbAccessoryDriver: canceling mDisconnectHandler");
				mHandler.removeCallbacks(mDisconnectHandler);
				mDisconnectHandler = null;
			}
		}
	};

	public static UsbAccessoryDriver init(Context context) {
		synchronized (UsbAccessoryDriver.class) {
			if (sInstance == null)
				sInstance = new UsbAccessoryDriver(context);
		}
		return sInstance;
	}

	public static UsbAccessoryDriver getInstance() {
		if (sInstance == null)
			throw new RuntimeException("Must call init before call getInstance");

		return sInstance;
	}

	@Override
	public void finalize() throws Throwable {
		stop();
		super.finalize();
	}

	public State getState() {
		return mState;
	}

	public boolean isConnected() {
		return mState == State.CONNECTED;
	}

	public boolean start() {
		switch (mState) {
			case IDLE: {
				try {
					DebugTool.logInfo("Starting USB driver");

					// Register necessary intent filters
					registerReceiver();
					setState(State.LISTENING);

					// Look for connected accessories and connect it if compatible is found.
					// This is done in CableCheckTimer.
					mCableCheckTimer.start();
					return true;
				} catch (Exception e) {
					DebugTool.logError(e.toString());
					return false;
				}
			}
			default:
				DebugTool.logWarning("Invalid state to open USB transport.");
				break;
		}
		return false;
	}

	public boolean stop() {
		DebugTool.logInfo("UsbAccessoryDriver stop: current state=" + mState);
		synchronized (this) {
			switch (mState) {
				case LISTENING:
				case WAITING_PERMISSION:
				case CONNECTING:
				case CONNECTED:
				case DISCONNECTED: { // we need to cleanup even if Usb is DISCONNECTED state.
					DebugTool.logInfo("Stopping USB driver");
					setState(State.STOPPING);
					try {
						mContext.unregisterReceiver(mReceiver);
					} catch (IllegalArgumentException e) {
						DebugTool.logWarning("Failed to unregister receiver: " + e.toString());
					}
					disconnect();
					mUsbChattering = false; // in case mReceiver is unregistered while chattering

					// Make sure stop the timer
					if (mAccessoryTimer != null) {
						mAccessoryTimer.stop();
						mAccessoryTimer = null;
					}

					// Return to IDLE state.
					setState(State.IDLE);

					mActivitySwitchedTimeNsec = -1;
					mPermissionRequestedTimeNsec = -1;
					mPermissionDeniedTimeNsec = -1;
					mPermissionRequestedAccessory = null;
					break;
				}
				default:
					break;
			}
			return true;
		}
	}

	public boolean connect() {
		if (mState == State.LISTENING || mState == State.DISCONNECTED) {
			UsbManager usbManager = getUsbManager();
			UsbAccessory[] accessories = usbManager.getAccessoryList();
			if (accessories != null) {
				for (UsbAccessory accessory : accessories) {
					if (isAccessorySupported(accessory)) {
						DebugTool.logInfo("Connect to USB accessory: " + accessory);
						if (connectToAccessory(accessory)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public ParcelFileDescriptor getAccessoryFD() {
		return mAccessoryFD;
	}

	// ----------------------------------------------------------------------------------
	// private stuff
	// ----------------------------------------------------------------------------------

	private UsbAccessoryDriver(Context context) {
		mContext = context;
		mState = State.IDLE;
	}

	private void setState(final State state) {
		mState = state;
	}

	private void registerReceiver() {
		// Note that Android OS does not notify ACTION_USB_ACCESSORY_ATTACHED to BroadcastReceiver.
		// Only Activity can receive that intent. Instead, UMA expects that upper layer calls connect()
		// method making sure establish USB session.
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		filter.addAction(Intent.ACTION_POWER_CONNECTED);
		filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		filter.addAction(ACTION_USB_PERMISSION);
		mContext.registerReceiver(mReceiver, filter);
	}

	private UsbManager getUsbManager() {
		return (UsbManager)mContext.getSystemService(Context.USB_SERVICE);
	}

	private void openAccessory(UsbAccessory accessory) {
		switch (mState) {
			case LISTENING:
			case CONNECTING: {
				synchronized (this) {
					DebugTool.logInfo("Open USB accessory: " + accessory);
					// UsbAccessory instance can exist until USB disconnected.
					if (!accessory.equals(mAccessory)) {
						ParcelFileDescriptor accessoryFD = null;
						try {
							accessoryFD = getUsbManager().openAccessory(accessory);
						} catch (SecurityException e) {
							// this exception happens when we receive an intent sent by another UMA app
							// stay in same state and wait for another intent
							DebugTool.logWarning("Received intent from another app");
							return;
						}
						if (accessoryFD == null) {
							DebugTool.logError("Cannot open USB accessory");
							// change the state to DISCONNECTED
							disconnect();
							return;
						}
						if (mAccessoryHandler != null) {
							mAccessoryHandler.accessoryConnected();
						}
						mAccessory = accessory;
						mAccessoryFD = accessoryFD;

					}
				}
			}
		}
	}
	private boolean isUSBCableConnected() {
		IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		Intent batteryChangedIntent = mContext.registerReceiver(null, filter);
		int batteryStatus = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		return batteryStatus != BatteryManager.BATTERY_STATUS_DISCHARGING &&
				batteryStatus != BatteryManager.BATTERY_STATUS_NOT_CHARGING;
	}

	private boolean hasUsbPermission() {
		UsbManager usbManager = getUsbManager();
		UsbAccessory[] accessories = usbManager.getAccessoryList();
		for (UsbAccessory accessory : accessories) {
			if (USBTransport.isAccessorySupported(accessory)) {
				return usbManager.hasPermission(accessory);
			}
		}
		return false;
	}


	private void disconnect() {
		mCableCheckTimer.stop();

		if (mState == State.IDLE) {
			return;
		}

		// Close connection to the connected accessory
		synchronized (this) {
			if (mAccessory != null) {
				if (mAccessoryFD != null) {
					try {
						mAccessoryFD.close();
					} catch (IOException e) {
						DebugTool.logInfo(e.toString());
						mAccessoryFD = null;
					}
				}
				mAccessory = null;
			}
		}

		// Ensure that unregister receiver
		DebugTool.logInfo("UsbAccessoryDriver.disconnect gets called");
		setState(State.DISCONNECTED);
	}

	private boolean connectToAccessory(UsbAccessory accessory) {
		switch (mState) {
			case LISTENING:
			case DISCONNECTED: {
				final UsbManager usbManager = getUsbManager();
				if (usbManager.hasPermission(accessory)) {
					DebugTool.logInfo("Already has permission to use USB accessory");
					setState(State.CONNECTING);
					openAccessory(accessory);
				} else {
					// Prevent the case opening USB accessory permission dialog twice.
					setState(State.WAITING_PERMISSION);

					// Delay showing the USB permission dialog.
					requestPermissionDialog(accessory, USB_PERMISSION_DIALOG_DELAY_MSEC);
				}
				return true;
			}
			default:
				DebugTool.logWarning("connectToAccessory() called on invalid state:" + mState);
				return false;
		}
	}

	private void requestPermissionDialog(final UsbAccessory accessory, long delayMsec) {
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				long currentTimeNsec = System.nanoTime();
				if (mActivitySwitchedTimeNsec >= 0 &&
						currentTimeNsec - mActivitySwitchedTimeNsec < DIALOG_MAY_BE_DISMISSED_NSEC) {
					// workaround C
					DebugTool.logInfo("Request permission to use USB accessory after " +
							USB_PERMISSION_DIALOG_EXTRA_DELAY_MSEC + " msec");
					requestPermissionDialog(accessory, USB_PERMISSION_DIALOG_EXTRA_DELAY_MSEC);
					return;
				}

				// Note: this check is not perfect. It is possible that the second dialog is requested
				// and then the user taps "OK" button on the first dialog.
				if (!getUsbManager().hasPermission(accessory)) {
					if (mState == State.WAITING_PERMISSION || mState == State.CONNECTING) {
						// Request permission to use accessory
						DebugTool.logInfo("Request permission to use USB accessory");
						Runnable showDialog = new Runnable() {
							@Override
							public void run() {
								PendingIntent permissionIntent = PendingIntent.getBroadcast(
										mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
								getUsbManager().requestPermission(accessory, permissionIntent);
								mPermissionRequestedTimeNsec = System.nanoTime();
								mPermissionRequestedAccessory = accessory;
							}
						};

						showDialog.run();
					} else {
						DebugTool.logInfo("USB already disconnected: " + mState);
					}
				} else {
					DebugTool.logInfo("Already has permission with previous dialog, cancel showing another one");
					// This case most likely caused by wrong state. Let's get back to LISTENNING
					ensureConnectionState(accessory);
				}
			}
		}, delayMsec);
	}

	private void ensureConnectionState(final UsbAccessory accessory) {
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (getState() != State.CONNECTED && !getUsbManager().hasPermission(accessory)) {
					// we will restart from LISTENING
					setState(State.LISTENING);
					connectToAccessory(accessory);
				}
			}
		}, 1000);
	}
	// Note that Android OS only notify ACTION_USB_ACCESSORY_ATTACHED to Activity.
	// As a result, Service cannot receive that intent. So our approach is to schedule timer to check
	// USB connection status.
	private class UsbAccessoryTimer {
		private boolean mRunning;

		public UsbAccessoryTimer start() {
			DebugTool.logInfo("Start USB connection timer");
			mRunning = true;
			mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (!mUsbChattering && isUSBCableConnected() && connect()) {
						stop();
					}
					if (mRunning) {
						mHandler.postDelayed(this, 1000);
					}
				}
			}, 1000);

			return this;
		}

		public UsbAccessoryTimer stop() {
			DebugTool.logInfo("Stop USB connection timer");
			mRunning = false;
			return this;
		}

		public boolean isRunning() {
			return mRunning;
		}
	}

	// This timer is started when UsbAccessoryDriver.start() is called. It will continue running until
	// isUSBCableConnected() returns true or we reach to the max. iteration.
	// By design, UsbAccessoryTimer and this timer should not run at the same time.
	private class CableCheckTimer implements Runnable {
		private boolean mRunning;
		private int mCount;

		// It is expected that start() and stop() are called by the main thread.
		public void start() {
			if (!mRunning) {
				mRunning = true;
				mCount = 0;
				mHandler.post(this);
			}
		}

		public void stop() {
			if (mRunning) {
				DebugTool.logInfo("Stop USB cable checking timer");
				stopInternal();
			}
		}

		@Override
		public void run() {
			if (mState != State.LISTENING) {
				DebugTool.logInfo("Cable checking timer stopped (state=" + mState + ")");
				stopInternal();
				return;
			}

			UsbManager usbManager = getUsbManager();
			UsbAccessory[] accessories = usbManager.getAccessoryList();
			if (accessories != null) {
				for (UsbAccessory accessory : accessories) {
					if (isAccessorySupported(accessory)) {
                        /*
                            If a USB Accessory is obtained even though the USB cable is
                            disconnected, the accessory is assumed as old one,
                            which is derived from previous connection.
                            Thus the accessory is ignored and Accessory retriever is
                            launched by the next DETACHED event.

                        */
						if(isUSBCableConnected()) {
							DebugTool.logInfo("Connect to USB accessory: " + accessory);
							if (connectToAccessory(accessory)) {
								stopInternal();
								return;
							}
						} else {
							if (mCount < CABLE_CHECK_AFTER_START_RETRY) {
								if (mCount == 0) {
									DebugTool.logInfo("Cable checking timer started");
								}
								mCount++;
								mHandler.postDelayed(this, CABLE_CHECK_INTERVAL_MSEC);
								return;
							} else {
								DebugTool.logInfo("Cable checking timer stopped (timeout)");
								stopInternal();
								return;
							}
						}
					}
				}
			}

			// If there was no compatible accessory connected on the phone
			// schedule timer to check USB accessory state.
			if (mCount > 0) {
				DebugTool.logInfo("Cable checking timer stopped, switching to accessory timer");
			}
			stopInternal();
			mAccessoryTimer = new UsbAccessoryTimer().start();
		}

		private void stopInternal() {
			mHandler.removeCallbacks(this);
			mRunning = false;
		}
	}
}

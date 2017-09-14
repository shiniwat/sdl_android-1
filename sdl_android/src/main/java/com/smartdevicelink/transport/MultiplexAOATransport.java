/*
 * Copyright (c) 2017, Xevo Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.smartdevicelink.transport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.util.DebugTool;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

//import com.smartdevicelink.util.DebugTool;



public class MultiplexAOATransport {
	private final String TAG = "MultiplexAOATransport";
	public static final int MUX_STATE_NONE 			= 0;    // we're doing nothing
	public static final int MUX_STATE_LISTEN 		= 1;    // now listening for incoming connections
	public static final int MUX_STATE_CONNECTING	= 2; 	// now initiating an outgoing connection
	public static final int MUX_STATE_CONNECTED 	= 3;  	// now connected to a remote device
	public static final int MUX_STATE_ERROR 		= 4;  	// Something bad happend, we wil not try to restart the thread

	private enum UsbState {
		/**
		 * Transport initialized; no connections.
		 */
		IDLE,

		/**
		 * USB accessory not attached; SdlProxy wants connection as soon as
		 * accessory is attached.
		 */
		LISTENING,

		/**
		 * USB accessory attached; permission granted; data IO in progress.
		 */
		CONNECTED
	}


	private final UsbManager mUsbManager;
	private static int mMuxState;
	private UsbState mUsbState;
	private Handler mHandler;
	private static MultiplexAOATransport mInstance = null;
	private Context mContext;
	private ParcelFileDescriptor mParcelFD;
	private static UsbAccessory mAccessory = null;
	private InputStream mInputStream;
	private OutputStream mOutputStream;
	private Thread mReaderThread = null;

	private static final String ACTION_USB_PERMISSION = "com.smartdevicelink.USB_PERMISSION";
	private final static String ACCESSORY_MANUFACTURER = "SDL";
	private final static String ACCESSORY_MODEL = "Core";
	private final static String ACCESSORY_VERSION = "1.0";

	/**
	 * Constructor: prepares the new AOA transport session.
	 * @param handler: A hanlder to send message back to UI
	 */
	private MultiplexAOATransport(Context context, Handler handler) {
		DebugTool.logInfo("MultiplexAOATrasport:ctor");
		mContext = context;
		mMuxState = MUX_STATE_NONE;
		mUsbState = UsbState.IDLE;
		mUsbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
		mHandler = handler;
	}

	// singleton pattern
	public synchronized  static MultiplexAOATransport getInstance(Context context, Handler handler) {
		if (mInstance == null) {
			mInstance = new MultiplexAOATransport(context, handler);
		}
		return mInstance;
	}

	public void start() {
		if (mMuxState != MUX_STATE_CONNECTED) {
			setMuxState(MUX_STATE_LISTEN);
			mUsbState = UsbState.LISTENING; // @REVIEW
		}
		registerReciever(mContext);
	}

	public void stop() {
		setMuxState(MUX_STATE_NONE);
		mAccessory = null;
		unregisterReceiver(mContext);
	}

	private void registerReciever(Context context) {
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		filter.addAction(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		context.registerReceiver(mUSBReceiver, filter);
		DebugTool.logInfo("registerReceiver");
	}

	private void unregisterReceiver(Context context) {
		if (mUSBReceiver != null) {
			context.unregisterReceiver(mUSBReceiver);
		}
	}

	private final BroadcastReceiver mUSBReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			DebugTool.logInfo("mUSBReceiver onReceive:action=" + action);
			UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
			if (accessory != null) {
				// Because AOA router service is launched in response to UAB_ACCESSORY_ATTACHED broadcast,
				// the following code won't be called.
				if (action.equals(USBTransport.ACTION_USB_ACCESSORY_ATTACHED)) {
					if (isAccessorySupported(accessory)) {
						connectToAccessory(accessory);
					}
				} else if (action.equals(UsbManager.ACTION_USB_ACCESSORY_DETACHED)) {
					if (mReaderThread != null) {
						mReaderThread.interrupt();
						try {
							mReaderThread.join(1000);
						} catch(InterruptedException ie) {}
						mReaderThread = null;
					}
					// tell service that we are disconnected.
					setMuxState(MUX_STATE_NONE);
					mUsbState = UsbState.LISTENING;
					mAccessory = null;
					mHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							setMuxState(MUX_STATE_LISTEN);
						}
					}, 1000);
				} else if (action.equals(ACTION_USB_PERMISSION)) {

				}
			}
		}
	};

	public static boolean isAccessorySupported(UsbAccessory accessory) {
		boolean manufacturerMatches =
				ACCESSORY_MANUFACTURER.equals(accessory.getManufacturer());
		boolean modelMatches = ACCESSORY_MODEL.equals(accessory.getModel());
		boolean versionMatches =
				ACCESSORY_VERSION.equals(accessory.getVersion());
		return manufacturerMatches && modelMatches && versionMatches;
	}

	public void connectToAccessory(UsbAccessory accessory) {
		if (mAccessory != null) {
			return;
		}
		DebugTool.logInfo("connectToAccessory");
		if (mUsbState == UsbState.LISTENING) {
			if (mUsbManager.hasPermission(accessory)) {
				openAccessory(accessory);
			} else {
				DebugTool.logInfo("About requestPermission");
				PendingIntent intent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
				mUsbManager.requestPermission(accessory, intent);
			}
		}
	}

	private void openAccessory(UsbAccessory accessory) {
		if (mAccessory != null) {
			return;
		}
		DebugTool.logInfo("MultiplexAOAransport: openAccessory");
		if (mUsbState == UsbState.LISTENING) {
			mAccessory = accessory;
			startReaderThread();
			// also send broadcast
			Intent intent = new Intent(TransportConstants.AOA_ROUTER_OPEN_ACCESSORY);
			mContext.sendBroadcast(intent);
		}
	}

	private void startReaderThread() {
		DebugTool.logInfo("startReaderThread");
		mReaderThread = new Thread((new MultiplexUSBTransportReader()));
		mReaderThread.setDaemon(true);
		mReaderThread.setName(MultiplexUSBTransportReader.class.getName());
		mReaderThread.start();
	}

	public int getMuxState() {
		return mMuxState;
	}

	public synchronized void setMuxState(int newState) {
		int previousState = mMuxState;
		mMuxState = newState;
		mHandler.obtainMessage(SdlRouterService.MESSAGE_STATE_CHANGE, newState, 0).sendToTarget();
	}

	protected boolean sendBytesOverTransport(SdlPacket packet) {
		byte[] msgBytes = packet.constructPacket();
		boolean result = false;
		final int state = mMuxState;
		switch(state) {
			case MUX_STATE_CONNECTED:
				if (mOutputStream != null) {
					try {
						mOutputStream.write(msgBytes, 0, msgBytes.length);
						result = true;

						//DebugTool.logInfo("Bytes successfully sent");
					} catch (IOException e) {
						//final String msg = "Failed to send bytes over USB";
						//DebugTool.logWarning(msg, e);
						e.printStackTrace();
					}
				} else {
					final String msg =
							"Can't send bytes when output stream is null";
					//DebugTool.logWarning(msg);
				}
				break;
			default:
				break;
		}
		return result;
	}

	public void write(byte[] out,  int offset, int count) {
		// Create temporary object
		// Synchronize a copy of the ConnectedThread
		synchronized (this) {
			if (mMuxState != MUX_STATE_CONNECTED) return;
		}
		if (mOutputStream != null) {
			try {
				mOutputStream.write(out, offset, count);
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
	}

	private class MultiplexUSBTransportReader implements Runnable {
		SdlPsm mPsm;

		@Override
		public void run() {
			mPsm = new SdlPsm();
			mPsm.reset();
			// try delaying somewhat
			if (connect()) {
				readFromTransport();
			}
		}

		private boolean connect() {
			DebugTool.logInfo("MultiplexUSBTransportReader::connect; mUsbState=" + mUsbState.toString());
			if (Thread.interrupted()) {
				return false;
			}
			if (mUsbState == UsbState.LISTENING && mAccessory != null) {
				synchronized (this) {
					mUsbState = UsbState.CONNECTED;
					setMuxState(MUX_STATE_CONNECTED);
					mParcelFD = mUsbManager.openAccessory(mAccessory);
					if (mParcelFD != null) {
						DebugTool.logInfo("ParcelFD=" + mParcelFD.toString());
						FileDescriptor fd = mParcelFD.getFileDescriptor();
						mInputStream = new FileInputStream(fd);
						DebugTool.logInfo("input stream=" + mInputStream.toString());
						mOutputStream = new FileOutputStream(fd);
					}
				}
			}
			return true;
		}

		private void readFromTransport() {
			final int READ_BUFFER_SIZE = 4096;
			byte[] buffer = new byte[READ_BUFFER_SIZE];
			int bytesRead;
			boolean stateProgress = false;
			int error = 0;
			while(!Thread.interrupted()) {
				try {
					if (mInputStream == null) {
						Thread.sleep(100, 0);
						continue;
					}
					bytesRead = mInputStream.read(buffer);
					if (bytesRead == -1) {
						return;
					} else if (bytesRead > 0){
						for (int i=0; i<bytesRead; i++) {
							byte input = buffer[i];
							stateProgress = mPsm.handleByte(input);
							if (!stateProgress) {
								mPsm.reset();
								buffer = new byte[READ_BUFFER_SIZE];
							}
							if (mPsm.getState() == SdlPsm.FINISHED_STATE) {
								synchronized (this) {
									mHandler.obtainMessage(SdlRouterService.MESSAGE_READ, mPsm.getFormedPacket()).sendToTarget();
								}
								mPsm.reset();
								buffer = new byte[READ_BUFFER_SIZE];
							}
						}
					}
				} catch(IOException e) {
					Log.e(TAG, e.getMessage());
					break;
				} catch(InterruptedException ie) {
					break;
				}
			}
		}
	}
}


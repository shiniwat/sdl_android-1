package com.smartdevicelink.transport;

/**
 * Created by swatanabe on 2018/02/05.
 */

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.smartdevicelink.util.DebugTool;

public class BluetoothTransportWriter implements ITransportWriter {
	private static MultiplexBluetoothTransport mSerialService = null;
	private final boolean legacyModeEnabled = false;
	private final String TAG = "BTTransportWriter";
	private Handler mHandlerBT;

	public BluetoothTransportWriter(Handler bluetoothHandler) {
		mHandlerBT = bluetoothHandler;
	}
	public synchronized void init(){
		if(legacyModeEnabled){
			DebugTool.logInfo("Not starting own bluetooth during legacy mode");
			return;
		}
		//init serial service
		if(mSerialService == null || mSerialService.getState() == MultiplexBluetoothTransport.STATE_ERROR){
			Log.i(TAG, "Initializing bluetooth transport");
			mSerialService = new MultiplexBluetoothTransport(mHandlerBT);
		}
		if (mSerialService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't started already
			if (mSerialService.getState() == MultiplexBluetoothTransport.STATE_NONE) {
				// Start the Bluetooth services
				Log.i(TAG, "Starting bluetooth transport");
				mSerialService.start();
			}

		}
	}

	public synchronized void close(){
		if(mSerialService != null){
			mSerialService.stop();
			mSerialService = null;
		}
	}

	public synchronized boolean bluetoothConnect(BluetoothDevice device){
		DebugTool.logInfo("Connecting to device: " + device.getName().toString());
		if(mSerialService == null || !mSerialService.isConnected())
		{	// Set up the Bluetooth serial object
			mSerialService = new MultiplexBluetoothTransport(mHandlerBT);
		}
		// We've been given a device - let's connect to it
		if(mSerialService.getState()!=MultiplexBluetoothTransport.STATE_CONNECTING){//mSerialService.stop();
			mSerialService.connect(device);
			if(mSerialService.getState() == MultiplexBluetoothTransport.STATE_CONNECTING){
				return true;
			}
		}

		DebugTool.logInfo("Bluetooth SPP Connect Attempt Completed");
		return false;
	}

	public boolean isConnected() {
		return mSerialService.isConnected();
	}

	public void setStateManually(int state) {
		mSerialService.setStateManually(state);
	}
	/**
	 * IWransportWriter stuff
	 * @param bundle
	 * @return
	 */
	@SuppressWarnings("unused") //The return false after the packet null check is not dead code. Read the getByteArray method from bundle
	public boolean writeBytesToTransport(Bundle bundle){
		if(bundle == null){
			return false;
		}
		if(mSerialService !=null && mSerialService.getState()==MultiplexBluetoothTransport.STATE_CONNECTED){
			byte[] packet = bundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
			if(packet!=null){
				int offset = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, 0); //If nothing, start at the begining of the array
				int count = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, packet.length);  //In case there isn't anything just send the whole packet.
				mSerialService.write(packet,offset,count);
				return true;
			}
			return false;
			/*--
		}else if(sendThroughAltTransport(bundle)){
			return true; --*/
		}
		else{
			Log.e(TAG, "Can't send data, no transport connected");
			return false;
		}
	}

	/**
	 *
	 * @param bytes
	 * @param offset
	 * @param count
	 * @return true if write successful
	 */
	public boolean manuallyWriteBytes(byte[] bytes, int offset, int count){
		DebugTool.logInfo(String.format("manuallyWriteBytes: count=%d", count));
		if(mSerialService !=null && mSerialService.getState()==MultiplexBluetoothTransport.STATE_CONNECTED){
			if(bytes!=null){
				mSerialService.write(bytes,offset,count);
				return true;
			}
			return false;
			/*--
		}else if(sendThroughAltTransport(bytes,offset,count)){
			return true; --*/
		}else{
			return false;
		}
	}

}
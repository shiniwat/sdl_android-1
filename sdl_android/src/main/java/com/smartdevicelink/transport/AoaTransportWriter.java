package com.smartdevicelink.transport;

import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.smartdevicelink.util.DebugTool;

import static com.smartdevicelink.transport.USBTransport.isAccessorySupported;

/**
 * Created by swatanabe on 2018/02/06.
 */

public class AoaTransportWriter implements ITransportWriter {
	private static MultiplexAOATransport mSerialService = null;
	//private final boolean legacyModeEnabled = false; // we won't support legacy mode.
	private final String TAG = "AoaTransportWriter";
	private Handler mHandlerAoa;
	private final Context mContext;

	public AoaTransportWriter(Handler slipHandler, Context context) {
		mHandlerAoa = slipHandler;
		mContext = context;
	}
	public synchronized void init(){
		//init serial service
		if(mSerialService == null){
			Log.i(TAG, "Initializing aoa transport");
			mSerialService = MultiplexAOATransport.getInstance(mContext, mHandlerAoa);
		}
		if (mSerialService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't started already
			// Start the slip services
			Log.i(TAG, "Starting slip transport");
			mSerialService.start(mContext);
		}
	}

	public synchronized void close(){
		if(mSerialService != null){
			mSerialService.stop();
			mSerialService = null;
		}
	}

	public boolean isConnected() {
		return mSerialService.isConnected();
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
		if(mSerialService !=null && mSerialService.getState()==MultiplexAOATransport.MUX_STATE_CONNECTED){
			byte[] packet = bundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
			int offset = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, 0); //If nothing, start at the begining of the array
			int count = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, packet.length);  //In case there isn't anything just send the whole packet.
			if(packet!=null){
				//DebugTool.logInfo(String.format("writeBytesToTransport packet length=%d", count));
				mSerialService.write(packet,offset,count);
				return true;
			}
			return false;
			/*-- @TODO: sendThroughAltTransport
		}else if(sendThroughAltTransport(bundle)){
			return true;
			---*/
		}
		else{
			Log.e(TAG, "Can't send data, no transport connected; mMuxSlipTransport=" + ((mSerialService == null) ? "null" : mSerialService.getState()));
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

	/**
	 * accessory
	 *
	 */
	private void requestAccessory(Context context) {
		//@TODO: the method will be removed
	}
}

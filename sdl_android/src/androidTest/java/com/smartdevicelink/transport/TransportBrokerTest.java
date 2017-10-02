package com.smartdevicelink.transport;

import android.bluetooth.BluetoothAdapter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.test.AndroidTestCase;

import com.smartdevicelink.test.SdlUnitTestContants;
import com.smartdevicelink.test.util.DeviceUtil;
import com.smartdevicelink.transport.enums.TransportType;

public class TransportBrokerTest extends AndroidTestCase {
	RouterServiceValidator rsvp;
	//		public TransportBrokerThread(Context context, String appId, ComponentName service){

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		rsvp = new RouterServiceValidator(this.mContext);
		rsvp.validate(TransportType.MULTIPLEX);
		
	}
	
	private void sleep(){
		try{
			Thread.sleep(500);
		}catch(Exception e){}
	}
	
	public void testStart(){
		if (Looper.myLooper() == null) {
			Looper.prepare();
		}
		TransportBroker broker = new TransportBroker(mContext, SdlUnitTestContants.TEST_APP_ID,rsvp.getServices().get(0));
		if(!DeviceUtil.isEmulator()){ // Cannot perform MBT operations in emulator
			assertTrue(broker.start(TransportType.MULTIPLEX));
		}
		broker.stop();

	}
	
	public void testSendPacket(){
		if (Looper.myLooper() == null) {
			Looper.prepare();
		}

		TransportBroker broker = new TransportBroker(mContext, SdlUnitTestContants.TEST_APP_ID,rsvp.getServices().get(0));

		if(!DeviceUtil.isEmulator()){ // Cannot perform MBT operations in emulator
			assertTrue(broker.start(TransportType.MULTIPLEX));
		}
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if(!DeviceUtil.isEmulator()){ // Cannot perform BT adapter operations in emulator
			assertNotNull(adapter);
			assertTrue(adapter.isEnabled());
		}
		//Not ideal, but not implementing callbacks just for unit tests
		int count = 0;
		while(broker.routerServiceMessenger == null && count<10){
			sleep();
			count++;
		}
		if(!DeviceUtil.isEmulator()){ // Cannot perform BT adapter operations in emulator
			assertNotNull(broker.routerServiceMessenger);
		}

		//assertFalse(broker.sendPacketToRouterService(null, 0, 0));
		//assertFalse(broker.sendPacketToRouterService(new byte[3], -1, 0));
		//assertFalse(broker.sendPacketToRouterService(new byte[3], 0, 4));
		//assertTrue(broker.sendPacketToRouterService(new byte[3],0, 3));

		broker.stop();

	}
	
	public void testOnPacketReceived(){
		if (Looper.myLooper() == null) {
			Looper.prepare();
		}
		TransportBroker broker = new TransportBroker(mContext, SdlUnitTestContants.TEST_APP_ID, rsvp.getServices().get(0));
		if(!DeviceUtil.isEmulator()){ // Cannot perform MBT operations in emulator
			assertTrue(broker.start(TransportType.MULTIPLEX));
		}

	}
	
	public void testSendMessageToRouterService(){
		if (Looper.myLooper() == null) {
			Looper.prepare();
		}

		TransportBroker broker = new TransportBroker(mContext, SdlUnitTestContants.TEST_APP_ID, rsvp.getServices().get(0));
		Handler handler = new Handler();
		Message message = new Message();
		broker.routerServiceMessenger = null;
		broker.isBound = true;

		assertFalse(broker.sendMessageToRouterService(message));

		broker.routerServiceMessenger = new Messenger(handler); //So it's not ambiguous

		broker.isBound = false;

		assertFalse(broker.sendMessageToRouterService(message));

		broker.isBound = true;
		broker.registeredWithRouterService = true;

		message = null;

		assertFalse(broker.sendMessageToRouterService(message));

		message = new Message();

		assertTrue(broker.sendMessageToRouterService(message));

	}

}

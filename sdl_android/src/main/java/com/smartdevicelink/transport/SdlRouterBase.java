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

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.smartdevicelink.R;
import com.smartdevicelink.marshal.JsonRPCMarshaller;
import com.smartdevicelink.protocol.BinaryFrameHeader;
import com.smartdevicelink.protocol.ProtocolMessage;
import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.protocol.SdlPacketFactory;
import com.smartdevicelink.protocol.enums.FrameType;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.protocol.enums.MessageType;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.proxy.rpc.UnregisterAppInterface;
import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.transport.utl.ByteAraryMessageAssembler;
import com.smartdevicelink.transport.utl.ByteArrayMessageSpliter;
import com.smartdevicelink.util.BitConverter;
import com.smartdevicelink.util.DebugTool;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.smartdevicelink.transport.TransportConstants.CONNECTED_DEVICE_STRING_EXTRA_NAME;
import static com.smartdevicelink.transport.TransportConstants.FORMED_PACKET_EXTRA_NAME;

abstract public class SdlRouterBase extends Service {
	private final String TAG = "SdlRouterBase";

	protected static final int ROUTER_SERVICE_VERSION_NUMBER = 4;
	public static final String REGISTER_NEWER_SERVER_INSTANCE_ACTION		= "com.sdl.android.newservice";

	// constants
	private static final long CLIENT_PING_DELAY = 1000;
	/**
	 * @deprecated use {@link TransportConstants#START_ROUTER_SERVICE_ACTION} instead
	 */
	public static final String START_SERVICE_ACTION							= "sdl.router.startservice";
	public static final String REGISTER_WITH_ROUTER_ACTION 					= "com.sdl.android.register";

	private final int UNREGISTER_APP_INTERFACE_CORRELATION_ID = 65530;
	private static final int FOREGROUND_SERVICE_ID = 849;

	boolean initPassed = false;
	TransportType connectedTransportType = null;

	final Object SESSION_LOCK = new Object(), REGISTERED_APPS_LOCK = new Object(), PING_COUNT_LOCK = new Object();
	public static HashMap<String, RegisteredApp> registeredApps;
	private SparseArray<String> sessionMap;
	private SparseArray<Integer> sessionHashIdMap;

	PacketWriteTaskMaster packetWriteTaskMaster = null;
	public int cachedModuleVersion = -1;
	public boolean isTransportConnected = false;
	private ITransportWriter mWriter;
	public String connectedDeviceName = "";			//The name of the connected Device

	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;

	/**
	 * Executor for making sure clients are still running during trying times
	 */
	private ScheduledExecutorService clientPingExecutor = null;
	Intent pingIntent = null;
	private boolean isPingingClients = false;
	int pingCount = 0;

	/**
	 * This flag is to keep track of if we are currently acting as a foreground service
	 */
	private boolean isForeground = false;

	public void setTransportWriter(ITransportWriter writer) {
		mWriter = writer;
	}

	/**
	 *  Service stuff
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		synchronized(SESSION_LOCK){
			this.sessionMap = new SparseArray<String>();
			this.sessionHashIdMap = new SparseArray<Integer>();
		}
		synchronized(REGISTERED_APPS_LOCK){
			registeredApps = new HashMap<String,RegisteredApp>();
		}
	}

	@Override
	public void onDestroy() {
		synchronized(SESSION_LOCK){
			if(this.sessionMap!=null){
				this.sessionMap.clear();
				this.sessionMap = null;
			}
			if(this.sessionHashIdMap!=null){
				this.sessionHashIdMap.clear();
				this.sessionHashIdMap = null;
			}
		}
		if(registeredApps!=null){
			synchronized(REGISTERED_APPS_LOCK){
				registeredApps.clear();
				registeredApps = null;
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(registeredApps == null){
			synchronized(REGISTERED_APPS_LOCK){
				registeredApps = new HashMap<String,RegisteredApp>();
			}
		}
		return super.onStartCommand(intent, flags, startId);
	}

	/**
	 * base stuff
	 */
	public void onTransportDisconnected(TransportType type){
		cachedModuleVersion = -1; //Reset our cached version
		//We've notified our clients, less clean up the mess now.
		synchronized(SESSION_LOCK){
			if (sessionMap != null) {
				this.sessionMap.clear();
			}
			if (sessionHashIdMap != null) {
				this.sessionHashIdMap.clear();
			}
		}
		synchronized(REGISTERED_APPS_LOCK){
			if(registeredApps==null){
				return;
			}
			registeredApps.clear();
		}
	}

	public void putRegisteredApp(String id, RegisteredApp app) {
		synchronized(REGISTERED_APPS_LOCK){
			if (registeredApps != null) {
				RegisteredApp old = registeredApps.put(app.getAppId(), app);
				if(old!=null){
					Log.w(TAG, "Replacing already existing app with this app id");
					removeAllSessionsForApp(old, true);
					old.close();
				}
			}
		}
	}

	public RegisteredApp removeRegisteredApp(String id) {
		RegisteredApp unregisteredApp = null;
		synchronized(REGISTERED_APPS_LOCK){
			if (registeredApps != null) {
				unregisteredApp = registeredApps.remove(id);
			}
		}
		return unregisteredApp;
	}

	public RegisteredApp getRegisteredApp(String id) {
		return registeredApps.get(id);
	}
	/* **************************************************************************************************************************************
	****************************************************************************************************************************************
	***********************************************  Broadcast Receivers START  **************************************************************
	****************************************************************************************************************************************
	****************************************************************************************************************************************/

	/** create our receiver from the router service */
	BroadcastReceiver mainServiceReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			//Let's grab where to reply to this intent at. We will keep it temp right now because we may have to deny registration
			String action =intent.getStringExtra(TransportConstants.SEND_PACKET_TO_APP_LOCATION_EXTRA_NAME);
			sendBroadcast(prepareRegistrationIntent(action));
		}
	};

	private Intent prepareRegistrationIntent(String action){
		Intent registrationIntent = new Intent();
		registrationIntent.setAction(action);
		registrationIntent.putExtra(TransportConstants.BIND_LOCATION_PACKAGE_NAME_EXTRA, this.getPackageName());
		registrationIntent.putExtra(TransportConstants.BIND_LOCATION_CLASS_NAME_EXTRA, this.getClass().getName());
		return registrationIntent;
	}

	/**
	 * Method for finding the next, highest priority write task from all connected apps.
	 * @return
	 */
	protected PacketWriteTask getNextTask(){
		final long currentTime = System.currentTimeMillis();
		RegisteredApp priorityApp = null;
		long currentPriority = -Long.MAX_VALUE, peekWeight;
		if (registeredApps != null) {
			synchronized (REGISTERED_APPS_LOCK) {
				PacketWriteTask peekTask = null;
				for (RegisteredApp app : registeredApps.values()) {
					peekTask = app.peekNextTask();
					if (peekTask != null) {
						peekWeight = peekTask.getWeight(currentTime);
						//Log.v(TAG, "App " + app.appId +" has a task with weight "+ peekWeight);
						if (peekWeight > currentPriority) {
							if (app.queuePaused) {
								app.notIt();//Reset the timer
								continue;
							}
							if (priorityApp != null) {
								priorityApp.notIt();
							}
							currentPriority = peekWeight;
							priorityApp = app;
						}
					}
				}
				if (priorityApp != null) {
					return priorityApp.getNextTask();
				}
			}
		}
		return null;
	}


	void initPingIntent(Class myClass){
		pingIntent = new Intent();
		pingIntent.setAction(START_SERVICE_ACTION);
		pingIntent.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_EXTRA, true);
		pingIntent.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_APP_PACKAGE, getBaseContext().getPackageName());
		pingIntent.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_CMP_NAME, new ComponentName(this, myClass));
		pingIntent.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_PING, true);
	}

	void startClientPings(){
		synchronized(this){
			if(!isTransportConnected){ //If we aren't connected, bail
				return;
			}
			if(isPingingClients){
				Log.w(TAG, "Already pinging clients. Resting count");
				synchronized(PING_COUNT_LOCK){
					pingCount = 0;
				}
				return;
			}
			if(clientPingExecutor == null){
				clientPingExecutor = Executors.newSingleThreadScheduledExecutor();
			}
			isPingingClients = true;
			synchronized(PING_COUNT_LOCK){
				pingCount = 0;
			}
			clientPingExecutor.scheduleAtFixedRate(new Runnable(){

				@Override
				public void run() {
					if(getPingCount()>=10){
						DebugTool.logInfo("Hit ping limit");
						stopClientPings();
						return;
					}
					if(pingIntent == null){
						initPingIntent(this.getClass());
					}
					getBaseContext().sendBroadcast(pingIntent);
					synchronized(PING_COUNT_LOCK){
						pingCount++;
					}

				}
			}, CLIENT_PING_DELAY, CLIENT_PING_DELAY, TimeUnit.MILLISECONDS); //Give a little delay for first call
		}
	}

	int getPingCount(){
		synchronized(PING_COUNT_LOCK){
			return pingCount;
		}
	}

	void stopClientPings(){
		if(clientPingExecutor!=null && !clientPingExecutor.isShutdown()){
			clientPingExecutor.shutdownNow();
			clientPingExecutor = null;
			isPingingClients = false;
		}
		pingIntent = null;
	}

	void notifyClients(Message message){
		if(message==null){
			Log.w(TAG, "Can't notify clients, message was null");
			return;
		}
		DebugTool.logInfo("Notifying "+ registeredApps.size()+ " clients");
		int result;
		synchronized(REGISTERED_APPS_LOCK){
			Collection<RegisteredApp> apps = registeredApps.values();
			Iterator<RegisteredApp> it = apps.iterator();
			while(it.hasNext()){
				RegisteredApp app = it.next();
				result = app.sendMessage(message);
				if(result == RegisteredApp.SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT){
					app.close();
					it.remove();
				}
			}
		}
	}

	private void pingClients(){
		Message message = Message.obtain();
		DebugTool.logInfo("Pinging "+ registeredApps.size()+ " clients");
		int result;
		synchronized(REGISTERED_APPS_LOCK){
			Collection<RegisteredApp> apps = registeredApps.values();
			Iterator<RegisteredApp> it = apps.iterator();
			while(it.hasNext()){
				RegisteredApp app = it.next();
				result = app.sendMessage(message);
				if(result == RegisteredApp.SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT){
					app.close();
					Vector<Long> sessions = app.getSessionIds();
					for(Long session:sessions){
						if(session !=null && session != -1){
							attemptToCleanUpModule(session.intValue(), cachedModuleVersion);
						}
					}
					it.remove();
				}
			}
		}
	}

	/**
	 * Removes all sessions from the sessions map for this given app id
	 * @param app
	 */
	void removeAllSessionsForApp(RegisteredApp app, boolean cleanModule){
		Vector<Long> sessions = app.getSessionIds();
		int size = sessions.size(), sessionId;
		for(int i=0; i<size;i++){
			//Log.d(TAG, "Investigating session " +sessions.get(i).intValue());
			//Log.d(TAG, "App id is: " + sessionMap.get(sessions.get(i).intValue()));
			sessionId = sessions.get(i).intValue();
			removeSessionFromMap(sessionId);
			if(cleanModule){
				attemptToCleanUpModule(sessionId, cachedModuleVersion);
			}
		}
	}

	boolean removeAppFromMap(RegisteredApp app){
		synchronized(REGISTERED_APPS_LOCK){
			RegisteredApp old = registeredApps.remove(app);
			if(old!=null){
				old.close();
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes session from map if the key is found.
	 * @param sessionId
	 * @return if the key was found
	 */
	boolean removeSessionFromMap(int sessionId){
		synchronized(SESSION_LOCK){
			if(sessionMap!=null){
				if(sessionMap.indexOfKey(sessionId)>=0){
					sessionMap.remove(sessionId);
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * This method is an all else fails situation. If the head unit is out of synch with the apps on the phone
	 * this method will clear out an unwanted or out of date session.
	 * @param session
	 * @param version
	 */
	private void attemptToCleanUpModule(int session, int version){
		Log.i(TAG, "Attempting to stop session " + session);
		byte[] uai = createForceUnregisterApp((byte)session, (byte)version);
		mWriter.manuallyWriteBytes(uai,0,uai.length);
		int hashId = 0;
		synchronized(this.SESSION_LOCK){
			if(this.sessionHashIdMap.indexOfKey(session)>=0){
				hashId = this.sessionHashIdMap.get(session);
				this.sessionHashIdMap.remove(session);
			}
		}
		byte[] stopService = (SdlPacketFactory.createEndSession(SessionType.RPC, (byte)session, 0, (byte)version, BitConverter.intToByteArray(hashId))).constructPacket();
		mWriter.manuallyWriteBytes(stopService,0,stopService.length);
	}

	private boolean sendPacketMessageToClient(RegisteredApp app, Message message, byte version){
		int result = app.sendMessage(message);
		if(result == RegisteredApp.SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT){
			DebugTool.logInfo("Dead object, removing app and sessions");
			//Get all their sessions and send out unregister info
			//Use the version in this packet as a best guess
			app.close();
			Vector<Long> sessions = app.getSessionIds();
			byte[]  unregister,stopService;
			int size = sessions.size(), sessionId;
			for(int i=0; i<size;i++){
				sessionId = sessions.get(i).intValue();
				unregister = createForceUnregisterApp((byte)sessionId,version);
				mWriter.manuallyWriteBytes(unregister,0,unregister.length);
				int hashId = 0;
				synchronized(this.SESSION_LOCK){
					if(this.sessionHashIdMap.indexOfKey(sessionId)>=0){
						hashId = this.sessionHashIdMap.get(sessionId);
					}
				}
				stopService = (SdlPacketFactory.createEndSession(SessionType.RPC, (byte)sessionId, 0, version, BitConverter.intToByteArray(hashId))).constructPacket();

				mWriter.manuallyWriteBytes(stopService,0,stopService.length);
				synchronized(SESSION_LOCK){
					this.sessionMap.remove(sessionId);
					this.sessionHashIdMap.remove(sessionId);
				}
			}
			synchronized(REGISTERED_APPS_LOCK){
				registeredApps.remove(app.appId);
			}
			return false;//We did our best to correct errors
		}
		return true;//We should have sent our packet, so we can return true now
	}

	/**
	 * This will send the received packet to the registered service. It will default to the single registered "foreground" app.
	 * This can be overridden to provide more specific functionality.
	 * @param packet the packet that is received
	 * @return whether or not the sending was successful
	 */
	public boolean sendPacketToRegisteredApp(SdlPacket packet) {
		if(registeredApps!=null && (registeredApps.size()>0)){
			int session = packet.getSessionId();
			boolean shouldAssertNewSession = packet.getFrameType() == FrameType.Control && (packet.getFrameInfo() == SdlPacket.FRAME_INFO_START_SERVICE_ACK || packet.getFrameInfo() == SdlPacket.FRAME_INFO_START_SERVICE_NAK);
			String appid = getAppIDForSession(session, shouldAssertNewSession); //Find where this packet should go
			if(appid!=null && appid.length()>0){
				RegisteredApp app = null;
				synchronized(REGISTERED_APPS_LOCK){
					app = registeredApps.get(appid);
				}
				if(app==null){
					Log.e(TAG, "No app found for app id " + appid + " Removing session maping and sending unregisterAI to head unit.");
					//We have no app to match the app id tied to this session
					removeSessionFromMap(session);
					byte[] uai = createForceUnregisterApp((byte)session, (byte)packet.getVersion());
					mWriter.manuallyWriteBytes(uai,0,uai.length);
					int hashId = 0;
					synchronized(this.SESSION_LOCK){
						if(this.sessionHashIdMap.indexOfKey(session)>=0){
							hashId = this.sessionHashIdMap.get(session);
							this.sessionHashIdMap.remove(session);
						}
					}
					byte[] stopService = (SdlPacketFactory.createEndSession(SessionType.RPC, (byte)session, 0, (byte)packet.getVersion(), BitConverter.intToByteArray(hashId))).constructPacket();
					mWriter.manuallyWriteBytes(stopService,0,stopService.length);
					return false;
				}
				byte version = (byte)packet.getVersion();

				if(shouldAssertNewSession && version>1 && packet.getFrameInfo() == SdlPacket.FRAME_INFO_START_SERVICE_ACK){ //we know this was a start session response
					if (packet.getPayload() != null && packet.getDataSize() == 4){ //hashid will be 4 bytes in length
						synchronized(SESSION_LOCK){
							this.sessionHashIdMap.put(session, (BitConverter.intFromByteArray(packet.getPayload(), 0)));
						}
					}
				}

				int packetSize = (int) (packet.getDataSize() + SdlPacket.HEADER_SIZE);
				//Log.i(TAG, "Checking packet size: " + packetSize);
				Message message = Message.obtain();
				Bundle bundle = new Bundle();

				if(packetSize < ByteArrayMessageSpliter.MAX_BINDER_SIZE){ //This is a small enough packet just send on through
					//Log.w(TAG, " Packet size is just right " + packetSize  + " is smaller than " + ByteArrayMessageSpliter.MAX_BINDER_SIZE + " = " + (packetSize<ByteArrayMessageSpliter.MAX_BINDER_SIZE));
					message.what = TransportConstants.ROUTER_RECEIVED_PACKET;
					bundle.putParcelable(FORMED_PACKET_EXTRA_NAME, packet);
					bundle.putInt(TransportConstants.BYTES_TO_SEND_FLAGS, TransportConstants.BYTES_TO_SEND_FLAG_NONE);
					message.setData(bundle);
					return sendPacketMessageToClient(app,message, version);
				}else{
					//Log.w(TAG, "Packet too big for IPC buffer. Breaking apart and then sending to client.");
					//We need to churn through the packet payload and send it in chunks
					byte[] bytes = packet.getPayload();
					SdlPacket copyPacket = new SdlPacket(packet.getVersion(),packet.isEncrypted(),
							(int)packet.getFrameType().getValue(),
							packet.getServiceType(),packet.getFrameInfo(), session,
							(int)packet.getDataSize(),packet.getMessageId(),null);
					message.what = TransportConstants.ROUTER_RECEIVED_PACKET;
					bundle.putParcelable(FORMED_PACKET_EXTRA_NAME, copyPacket);
					bundle.putInt(TransportConstants.BYTES_TO_SEND_FLAGS, TransportConstants.BYTES_TO_SEND_FLAG_SDL_PACKET_INCLUDED);
					message.setData(bundle);
					//Log.d(TAG, "First packet before sending: " + message.getData().toString());
					if(!sendPacketMessageToClient(app, message, version)){
						Log.w(TAG, "Error sending first message of split packet to client " + app.appId);
						return false;
					}
					//Log.w(TAG, "Message too big for single IPC transaction. Breaking apart. Size - " +  packet.getDataSize());
					ByteArrayMessageSpliter splitter = new ByteArrayMessageSpliter(appid, TransportConstants.ROUTER_RECEIVED_PACKET,bytes,0);
					while(splitter.isActive()){
						if(!sendPacketMessageToClient(app,splitter.nextMessage(),version)){
							Log.w(TAG, "Error sending first message of split packet to client " + app.appId);
							splitter.close();
							return false;
						}
					}
					//Log.i(TAG, "Large packet finished being sent");
				}

			}else{	//If we can't find a session for this packet we just drop the packet
				Log.e(TAG, "App Id was NULL for session!");
				if(removeSessionFromMap(session)){ //If we found the session id still tied to an app in our map we need to remove it and send the proper shutdown sequence.
					Log.i(TAG, "Removed session from map.  Sending unregister request to module.");
					attemptToCleanUpModule(session, packet.getVersion());
				}else{ //There was no mapping so let's try to resolve this

					if(packet.getFrameType() == FrameType.Single && packet.getServiceType() == SdlPacket.SERVICE_TYPE_RPC){
						BinaryFrameHeader binFrameHeader = BinaryFrameHeader.parseBinaryHeader(packet.getPayload());
						if(binFrameHeader!=null && FunctionID.UNREGISTER_APP_INTERFACE.getId() == binFrameHeader.getFunctionID()){
							DebugTool.logInfo("Received an unregister app interface with no where to send it, dropping the packet.");
						}else{
							attemptToCleanUpModule(session, packet.getVersion());
						}
					}else if((packet.getFrameType() == FrameType.Control
							&& (packet.getFrameInfo() == SdlPacket.FRAME_INFO_END_SERVICE_ACK || packet.getFrameInfo() == SdlPacket.FRAME_INFO_END_SERVICE_NAK))){
						//We want to ignore this
						DebugTool.logInfo("Received a stop service ack/nak with no where to send it, dropping the packet.");
					}else{
						attemptToCleanUpModule(session, packet.getVersion());
					}
				}
			}
		}
		return false;
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	boolean removeAllSessionsWithAppId(String appId){
		synchronized(SESSION_LOCK){
			if(sessionMap!=null){
				SparseArray<String> iter = sessionMap.clone();
				int size = iter.size();
				for(int i = 0; i<size; i++){
					//Log.d(TAG, "Investigating session " +iter.keyAt(i));
					//Log.d(TAG, "App id is: " + iter.valueAt(i));
					if(((String)iter.valueAt(i)).compareTo(appId) == 0){
						sessionHashIdMap.remove(iter.keyAt(i));
						sessionMap.removeAt(i);
					}
				}
			}
		}
		return false;
	}

	private String getAppIDForSession(int sessionId, boolean shouldAssertNewSession){
		synchronized(SESSION_LOCK){
			//Log.d(TAG, "Looking for session: " + sessionId);
			if(sessionMap == null){
				Log.w(TAG, "Session map was null during look up. Creating one on the fly");
				sessionMap = new SparseArray<String>(); //THIS SHOULD NEVER BE NULL! WHY IS THIS HAPPENING?!?!?!
			}
			String appId = sessionMap.get(sessionId);// SdlRouterService.this.sessionMap.get(sessionId);
			if(appId==null && shouldAssertNewSession){
				int pos;
				synchronized(REGISTERED_APPS_LOCK){
					for (RegisteredApp app : registeredApps.values()) {
						pos = app.containsSessionId(-1);
						if(pos != -1){
							app.setSessionId(pos,sessionId);
							appId = app.getAppId();
							sessionMap.put(sessionId, appId);
							break;
						}
					}
				}
			}
			//Log.d(TAG, sessionId + " session returning App Id: " + appId);
			return appId;
		}
	}

	void enterForeground(int resId) {
		if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.HONEYCOMB){
			Log.w(TAG, "Unable to start service as foreground due to OS SDK version being lower than 11");
			isForeground = false;
			return;
		}


		Bitmap icon;
		int resourcesIncluded = getResources().getIdentifier("ic_sdl", "drawable", getPackageName());

		if ( resourcesIncluded != 0 ) {  //No additional pylons required
			icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_sdl);
		}
		else {
			icon = BitmapFactory.decodeResource(getResources(), resId);
		}
		// Bitmap icon = BitmapFactory.decodeByteArray(SdlLogo.SDL_LOGO_STRING, 0, SdlLogo.SDL_LOGO_STRING.length);

		Notification.Builder builder = new Notification.Builder(this);
		if(0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)){ //If we are in debug mode, include what app has the router service open
			ComponentName name = new ComponentName(this, this.getClass());
			builder.setContentTitle("SDL: " + name.getPackageName());
		}else{
			builder.setContentTitle("SmartDeviceLink");
		}
		builder.setTicker("SmartDeviceLink Connected");
		builder.setContentText("Connected to " + this.getConnectedDeviceName());

		//We should use icon from library resources if available
		int trayId = getResources().getIdentifier("sdl_tray_icon", "drawable", getPackageName());

		if ( resourcesIncluded != 0 ) {  //No additional pylons required
			builder.setSmallIcon(trayId);
		}
		else {
			builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth);
		}
		builder.setLargeIcon(icon);
		builder.setOngoing(true);

		Notification notification;
		if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN){
			notification = builder.getNotification();

		}else{
			notification = builder.build();
		}
		if(notification == null){
			Log.e(TAG, "Notification was null");
		}
		startForeground(FOREGROUND_SERVICE_ID, notification);
		isForeground = true;

	}

	void exitForeground(){
		if(isForeground){
			this.stopForeground(true);
		}
	}

	public Message createHardwareConnectedMessage(final TransportType type){
		Message message = Message.obtain();
		message.what = TransportConstants.HARDWARE_CONNECTION_EVENT;
		Bundle bundle = new Bundle();
		if (type.equals(TransportType.MULTIPLEX_AOA)) {
			// For backward compatibility reason, we can't add new value to HARDWARE_CONNECTED message.
			bundle.putString(TransportConstants.HARDWARE_CONNECTED_AOA, type.name());
		} else {
			bundle.putString(TransportConstants.HARDWARE_CONNECTED, type.name());
		}
		if(MultiplexBluetoothTransport.currentlyConnectedDevice!=null){
			bundle.putString(CONNECTED_DEVICE_STRING_EXTRA_NAME, MultiplexBluetoothTransport.currentlyConnectedDevice);
		}
		message.setData(bundle);
		return message;

	}


	public String getConnectedDeviceName(){
		return connectedDeviceName;
	}

	/**
	 * This class helps keep track of all the different sessions established with the head unit
	 * and to which app they belong to.
	 * @author Joey Grover
	 *
	 */
	class RegisteredApp {
		protected static final int SEND_MESSAGE_SUCCESS 							= 0x00;
		protected static final int SEND_MESSAGE_ERROR_MESSAGE_NULL 					= 0x01;
		protected static final int SEND_MESSAGE_ERROR_MESSENGER_NULL 				= 0x02;
		protected static final int SEND_MESSAGE_ERROR_MESSENGER_GENERIC_EXCEPTION 	= 0x03;
		protected static final int SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT 		= 0x04;

		protected static final int PAUSE_TIME_FOR_QUEUE 							= 1500;

		String appId;
		Messenger messenger;
		Vector<Long> sessionIds;
		ByteAraryMessageAssembler buffer;
		int prioirtyForBuffingMessage;
		IBinder.DeathRecipient deathNote = null;
		//Packey queue vars
		PacketWriteTaskBlockingQueue queue;
		Handler queueWaitHandler= null;
		Runnable queueWaitRunnable = null;
		boolean queuePaused = false;

		/**
		 * This is a simple class to hold onto a reference of a registered app.
		 * @param appId
		 * @param messenger
		 */
		public RegisteredApp(String appId, Messenger messenger){
			this.appId = appId;
			this.messenger = messenger;
			this.sessionIds = new Vector<Long>();
			this.queue = new PacketWriteTaskBlockingQueue();
			queueWaitHandler = new Handler();
			setDeathNote();
		}

		/**
		 * Closes this app properly.
		 */
		public void close(){
			clearDeathNote();
			clearBuffer();
			if(this.queue!=null){
				this.queue.clear();
				queue = null;
			}
			if(queueWaitHandler!=null){
				if(queueWaitRunnable!=null){
					queueWaitHandler.removeCallbacks(queueWaitRunnable);
				}
				queueWaitHandler = null;
			}
		}

		public String getAppId() {
			return appId;
		}

		/*public long getAppId() {
			return appId;
		}*/
		/**
		 * This is a convenience variable and may not be used or useful in different protocols
		 * @return
		 */
		public Vector<Long> getSessionIds() {
			return sessionIds;
		}

		/**
		 * Returns the position of the desired object if it is contained in the vector. If not it will return -1.
		 * @param id
		 * @return
		 */
		public int containsSessionId(long id){
			return sessionIds.indexOf(id);
		}
		/**
		 * This will remove a session from the session id list
		 * @param sessionId
		 * @return
		 */
		public boolean removeSession(Long sessionId){
			int location = sessionIds.indexOf(sessionId);
			if(location>=0){
				Long removedSessionId = sessionIds.remove(location);
				if(removedSessionId!=null){
					return true;
				}else{
					return false;
				}
			}else{
				return false;
			}
		}
		/**
		 * @param sessionId
		 */
		public void setSessionId(int position,long sessionId) throws ArrayIndexOutOfBoundsException {
			this.sessionIds.set(position, (long)sessionId);
		}

		public void clearSessionIds(){
			this.sessionIds.clear();
		}

		public boolean handleIncommingClientMessage(final Bundle receivedBundle){
			//Log.d(TAG, "handleIncommingClientMessage");
			int flags = receivedBundle.getInt(TransportConstants.BYTES_TO_SEND_FLAGS, TransportConstants.BYTES_TO_SEND_FLAG_NONE);
			//Log.d(TAG, "Flags received: " + flags);
			if(flags!= TransportConstants.BYTES_TO_SEND_FLAG_NONE){
				byte[] packet = receivedBundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
				if(flags == TransportConstants.BYTES_TO_SEND_FLAG_LARGE_PACKET_START){
					this.prioirtyForBuffingMessage = receivedBundle.getInt(TransportConstants.PACKET_PRIORITY_COEFFICIENT,0);
				}
				handleMessage(flags, packet);
			}else{
				//Add the write task on the stack
				if(queue!=null){
					queue.add(new PacketWriteTask(receivedBundle));
					if(packetWriteTaskMaster!=null){
						packetWriteTaskMaster.alert();
					} else {
						DebugTool.logInfo("packetWriteTaskMaster is null");
					}
				}
			}
			return true;
		}

		public int sendMessage(Message message){
			if(this.messenger == null){return SEND_MESSAGE_ERROR_MESSENGER_NULL;}
			if(message == null){return SEND_MESSAGE_ERROR_MESSAGE_NULL;}
			try {
				this.messenger.send(message);
				return SEND_MESSAGE_SUCCESS;
			} catch (RemoteException e) {
				e.printStackTrace();
				if(e instanceof DeadObjectException){
					return SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT;
				}else{
					return SEND_MESSAGE_ERROR_MESSENGER_GENERIC_EXCEPTION;
				}
			}
		}

		public void handleMessage(int flags, byte[] packet){
			//Log.d(TAG, String.format("handleMessage: flag=%d", flags));
			if(flags == TransportConstants.BYTES_TO_SEND_FLAG_LARGE_PACKET_START){
				clearBuffer();
				buffer = new ByteAraryMessageAssembler();
				buffer.init();
			}
			if(buffer != null){
				if (!buffer.handleMessage(flags, packet)) { //If this returns false
					Log.e(TAG, "Error handling bytes");
				}
				if (buffer.isFinished()) { //We are finished building the buffer so we should write the bytes out
					byte[] bytes = buffer.getBytes();
					if (queue != null) {
						queue.add(new PacketWriteTask(bytes, 0, bytes.length, this.prioirtyForBuffingMessage));
						if (packetWriteTaskMaster != null) {
							packetWriteTaskMaster.alert();
						}
					}
					buffer.close();
				}
			}
		}

		protected PacketWriteTask peekNextTask(){
			if(queue !=null){
				return queue.peek();
			}
			return null;
		}

		protected PacketWriteTask getNextTask(){
			if(queue !=null){
				return queue.poll();
			}
			return null;
		}

		/**
		 * This will inform the local app object that it was not picked to have the highest priority. This will allow the user to continue to perform interactions
		 * with the module and not be bogged down by large packet requests.
		 */
		protected void notIt(){
			if(queue!=null && queue.peek().priorityCoefficient>0){ //If this has any sort of priority coefficient we want to make it wait.
				//Flag to wait
				if(queueWaitHandler == null){
					Log.e(TAG, "Unable to pause queue, handler was null");
				}
				if(queueWaitRunnable == null){
					queueWaitRunnable = new Runnable(){

						@Override
						public void run() {
							pauseQueue(false);
							if(packetWriteTaskMaster!=null){
								packetWriteTaskMaster.alert();
							}

						}

					};
				}
				if(queuePaused){
					queueWaitHandler.removeCallbacks(queueWaitRunnable);
				}
				pauseQueue(queueWaitHandler.postDelayed(queueWaitRunnable, PAUSE_TIME_FOR_QUEUE));
			}
		}
		private void pauseQueue(boolean paused){
			this.queuePaused = paused;
		}
		protected void clearBuffer(){
			if(buffer!=null){
				buffer.close();
				buffer = null;
			}
		}

		protected boolean setDeathNote(){
			if(messenger!=null){
				if(deathNote == null){
					deathNote = new IBinder.DeathRecipient(){
						final Object deathLock = new Object();
						@Override
						public void binderDied() {
							synchronized(deathLock){
								Log.w(TAG, "Binder died for app " + RegisteredApp.this.appId);
								if(messenger!=null && messenger.getBinder()!=null){
									messenger.getBinder().unlinkToDeath(this, 0);
								}
								removeAllSessionsForApp(RegisteredApp.this,true);
								removeAppFromMap(RegisteredApp.this);
								startClientPings();
							}
						}
					};
				}
				try {
					messenger.getBinder().linkToDeath(deathNote, 0);
					return true;
				} catch (RemoteException e) {
					e.printStackTrace();
					return false;
				}
			}
			return false;
		}

		protected boolean clearDeathNote(){
			if(messenger!=null && messenger.getBinder()!=null && deathNote!=null){
				return messenger.getBinder().unlinkToDeath(deathNote, 0);
			}
			return false;
		}
	}

	/**
	 * A runnable task for writing out packets.
	 * @author Joey Grover
	 *
	 */
	class PacketWriteTask implements Runnable {
		private static final long DELAY_CONSTANT = 500; //250ms
		private static final long SIZE_CONSTANT = 1000; //1kb
		private static final long PRIORITY_COEF_CONSTATNT = 500;
		private static final int DELAY_COEF = 1;
		private static final int SIZE_COEF = 1;

		private byte[] bytesToWrite = null;
		public int offset, size, priorityCoefficient;
		private final long timestamp;
		final Bundle receivedBundle;
		private ITransportWriter transportWriter;

		public PacketWriteTask(byte[] bytes, int offset, int size, int priorityCoefficient) {
			timestamp = System.currentTimeMillis();
			bytesToWrite = bytes;
			this.offset = offset;
			this.size = size;
			this.priorityCoefficient = priorityCoefficient;
			receivedBundle = null;
		}

		public PacketWriteTask(Bundle bundle){
			this.receivedBundle = bundle;
			timestamp = System.currentTimeMillis();
			bytesToWrite = bundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
			offset = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, 0); //If nothing, start at the begining of the array
			size = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, bytesToWrite.length);  //In case there isn't anything just send the whole packet.
			this.priorityCoefficient = bundle.getInt(TransportConstants.PACKET_PRIORITY_COEFFICIENT,0); //Log.d(TAG, "packet priority coef: "+ this.priorityCoefficient);
		}

		@Override
		public void run() {
			if(receivedBundle!=null){
				//Log.d("PacketWriteTask", "writeBytesToTransport");
				mWriter.writeBytesToTransport(receivedBundle);
			}else if(bytesToWrite !=null){
				//Log.d("PacketWriteTask", String.format("manuallyWriteBytes %d bytes", size));
				mWriter.manuallyWriteBytes(bytesToWrite, offset, size);
			}
		}

		private long getWeight(long currentTime){ //Time waiting - size - prioirty_coef
			return ((((currentTime-timestamp) + DELAY_CONSTANT) * DELAY_COEF ) - ((size -SIZE_CONSTANT) * SIZE_COEF) - (priorityCoefficient * PRIORITY_COEF_CONSTATNT));
		}
	}

	/**
	 * Extends thread to consume PacketWriteTasks in a priority queue fashion. It will attempt to look
	 * at all apps serial queue of tasks and compare them
	 * @author Joey Grover
	 *
	 */
	class PacketWriteTaskMaster extends Thread {
		protected final Object QUEUE_LOCK = new Object();
		private boolean isHalted = false, isWaiting = false;

		public PacketWriteTaskMaster(){
			this.setName("PacketWriteTaskMaster");
		}

		@Override
		public void run() {
			while(!isHalted){
				try{
					PacketWriteTask task = null;
					synchronized(QUEUE_LOCK){
						task = getNextTask();
						if(task != null){
							task.run();
						}else{
							isWaiting = true;
							QUEUE_LOCK.wait();
							isWaiting = false;
						}
					}
				}catch(InterruptedException e){
					break;
				}
			}
		}

		void alert(){
			//Log.d(TAG, "alert isWaiting=" + isWaiting);
			if(isWaiting){
				synchronized(QUEUE_LOCK){
					QUEUE_LOCK.notify();
				}
			}
		}

		void close(){
			this.isHalted = true;
		}
	}

	/**
	 * Custom queue to prioritize packet write tasks based on their priority coefficient.<br> The queue is a doubly linked list.<br><br>
	 * When a tasks is added to the queue, it will be evaluated using it's priority coefficient. If the coefficient is greater than 0, it will simply
	 * be placed at the end of the queue. If the coefficient is equal to 0, the queue will begin to iterate at the head and work it's way back. Once it is found that the current
	 * tasks has a priority coefficient greater than 0, it will be placed right before that task. The idea is to keep a semi-serial queue but creates a priority that allows urgent
	 * tasks such as UI related to skip near the front. However, it is assumed those tasks of higher priority should also be handled in a serial fashion.
	 *
	 * @author Joey Grover
	 *
	 */
	class PacketWriteTaskBlockingQueue{
		final class Node<E> {
			E item;
			PacketWriteTaskBlockingQueue.Node<E> prev;
			PacketWriteTaskBlockingQueue.Node<E> next;
			Node(E item, PacketWriteTaskBlockingQueue.Node<E> previous, PacketWriteTaskBlockingQueue.Node<E> next) {
				this.item = item;
				this.prev = previous;
				this.next = next;
			}
		}

		private PacketWriteTaskBlockingQueue.Node<PacketWriteTask> head;
		private PacketWriteTaskBlockingQueue.Node<PacketWriteTask> tail;

		/**
		 * This will take the given task and insert it at the tail of the queue
		 * @param task the task to be inserted at the tail of the queue
		 */
		private void insertAtTail(PacketWriteTask task){
			if (task == null){
				throw new NullPointerException();
			}
			PacketWriteTaskBlockingQueue.Node<PacketWriteTask> oldTail = tail;
			PacketWriteTaskBlockingQueue.Node<PacketWriteTask> newTail = new PacketWriteTaskBlockingQueue.Node<PacketWriteTask>(task, oldTail, null);
			tail = newTail;
			if (head == null){
				head = newTail;
			}else{
				oldTail.next = newTail;
			}

		}

		/**
		 * This will take the given task and insert it at the head of the queue
		 * @param task the task to be inserted at the head of the queue
		 */
		private void insertAtHead(PacketWriteTask task){
			if (task == null){
				throw new NullPointerException();
			}
			PacketWriteTaskBlockingQueue.Node<PacketWriteTask> oldHead = head;
			PacketWriteTaskBlockingQueue.Node<PacketWriteTask> newHead = new PacketWriteTaskBlockingQueue.Node<PacketWriteTask>(task, null, oldHead);
			head = newHead;
			if (tail == null){
				tail = newHead;
			}else{
				if(oldHead!=null){
					oldHead.prev = newHead;
				}
			}
		}

		/**
		 * Insert the task in the queue where it belongs
		 * @param task
		 */
		public void add(PacketWriteTask task){
			synchronized(this){
				if (task == null){
					throw new NullPointerException();
				}

				//If we currently don't have anything in our queue
				if(head == null || tail == null){
					PacketWriteTaskBlockingQueue.Node<PacketWriteTask> taskNode = new PacketWriteTaskBlockingQueue.Node<PacketWriteTask>(task, head, tail);
					head = taskNode;
					tail = taskNode;
					return;
				}else if(task.priorityCoefficient>0){ //If the  task is already a not high priority task, we just need to insert it at the tail
					insertAtTail(task);
					return;
				}else if(head.item.priorityCoefficient>0){ //If the head task is already a not high priority task, we just need to insert at head
					insertAtHead(task);
					return;
				}else{
					if(tail!=null && tail.item.priorityCoefficient==0){ //Saves us from going through the entire list if all of these tasks are priority coef == 0
						insertAtTail(task);
						return;
					}
					PacketWriteTaskBlockingQueue.Node<PacketWriteTask> currentPlace = head;
					while(true){
						if(currentPlace.item.priorityCoefficient==0){
							if(currentPlace.next==null){
								//We've reached the end of the list
								insertAtTail(task);
								return;
							}else{
								currentPlace = currentPlace.next;
								continue;
							}
						}else{
							//We've found where this task should be inserted
							PacketWriteTaskBlockingQueue.Node<PacketWriteTask> previous = currentPlace.prev;
							PacketWriteTaskBlockingQueue.Node<PacketWriteTask> taskNode = new PacketWriteTaskBlockingQueue.Node<SdlRouterService.PacketWriteTask>(task, previous, currentPlace);
							previous.next = taskNode;
							currentPlace.prev = taskNode;
							return;

						}
					}
				}
			}
		}

		/**
		 * Peek at the current head of the queue
		 * @return the task at the head of the queue but does not remove it from the queue
		 */
		public PacketWriteTask peek(){
			synchronized(this){
				if(head == null){
					return null;
				}else{
					return head.item;
				}
			}
		}

		/**
		 * Remove the head of the queue
		 * @return the old head of the queue
		 */
		public PacketWriteTask poll(){
			synchronized(this){
				if(head == null){
					return null;
				}else{
					PacketWriteTaskBlockingQueue.Node<PacketWriteTask> retValNode = head;
					PacketWriteTaskBlockingQueue.Node<PacketWriteTask> newHead = head.next;
					if(newHead == null){
						tail = null;
					}
					head = newHead;

					return retValNode.item;
				}
			}
		}

		/**
		 * Currently only clears the head and the tail of the queue.
		 */
		public void clear(){
			//Should probably go through the linked list and clear elements, but gc should clear them out automatically.
			head = null;
			tail = null;
		}
	}

	/**
	 * If an app crashes the only way we can handle it being on the head unit is to send an unregister app interface rpc.
	 * This method should only be called when the router service recognizes the client is no longer valid
	 * @param sessionId
	 * @param version
	 * @return
	 */
	private byte[] createForceUnregisterApp(byte sessionId,byte version){
		UnregisterAppInterface request = new UnregisterAppInterface();
		request.setCorrelationID(UNREGISTER_APP_INTERFACE_CORRELATION_ID);
		byte[] msgBytes = JsonRPCMarshaller.marshall(request, version);
		ProtocolMessage pm = new ProtocolMessage();
		pm.setData(msgBytes);
		pm.setSessionID(sessionId);
		pm.setMessageType(MessageType.RPC);
		pm.setSessionType(SessionType.RPC);
		pm.setFunctionID(FunctionID.getFunctionId(request.getFunctionName()));
		pm.setCorrID(request.getCorrelationID());
		if (request.getBulkData() != null)
			pm.setBulkData(request.getBulkData());
		byte[] data = null;
		if(version > 1) { //If greater than v1 we need to include a binary frame header in the data before all the JSON starts
			data = new byte[12 + pm.getJsonSize()];
			BinaryFrameHeader binFrameHeader = new BinaryFrameHeader();
			binFrameHeader = SdlPacketFactory.createBinaryFrameHeader(pm.getRPCType(), pm.getFunctionID(), pm.getCorrID(), pm.getJsonSize());
			System.arraycopy(binFrameHeader.assembleHeaderBytes(), 0, data, 0, 12);
			System.arraycopy(pm.getData(), 0, data, 12, pm.getJsonSize());
		}else {
			data = pm.getData();
		}

		SdlPacket packet = new SdlPacket(version,false, SdlPacket.FRAME_TYPE_SINGLE, SdlPacket.SERVICE_TYPE_RPC,0,sessionId,data.length,data.length+100,data);
		return packet.constructPacket();
	}

}

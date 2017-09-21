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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.protocol.enums.FrameType;
import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.util.DebugTool;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.smartdevicelink.transport.TransportConstants.HARDWARE_DISCONNECTED;


public class SdlAoaRouterService extends SdlRouterBase implements ITransportWriter {

	private static final String TAG = "Sdl AOA Router Service";


	private boolean isTransportConnected = false;


	private static boolean connectAsClient = false;
	private boolean startSequenceComplete = false;

	private static MultiplexAOATransport mMuxAoaTransport = null;
	private ExecutorService packetExecuter = null;
	private static boolean closing = false;
	private BroadcastReceiver registerAnInstanceOfSerialServer;


	/* **************************************************************************************************************************************
	***********************************************  Broadcast Receivers stuff  **************************************************************
	****************************************************************************************************************************************/
	private void onAppRegistered(RegisteredApp app){
		DebugTool.logInfo("onAppRegistered");
		if(startSequenceComplete &&
				!connectAsClient && (mMuxAoaTransport ==null
				|| mMuxAoaTransport.getMuxState() == MultiplexAOATransport.MUX_STATE_NONE)){
			//Log.d(TAG, "mux transport not initliazed while registering app");
			//Maybe we should try to do a connect here instead
			if(mMuxAoaTransport ==null){
				DebugTool.logInfo("mux transport being restarted");
				mMuxAoaTransport = MultiplexAOATransport.getInstance(this, mHandlerAOA);
				mMuxAoaTransport.start();

			} else {
				DebugTool.logInfo("MultiplexAOATransport already started");
			}
			//mMuxAoaTransport.start();

		} else {
			DebugTool.logInfo("MultiplexAOATransport already started");
		}
	}


	// @TODO: also need to check if mListenForDisconnect (BroadcastReceiver) is required.

	/* **************************************************************************************************************************************
	*********************************************** Handlers for bound clients **************************************************************
	****************************************************************************************************************************************/
	/**
	 * RouterHandler
	 * (internal)
	 */
	/**
	 * Target we publish for clients to send messages to RouterHandler.
	 */
	final Messenger routerMessenger = new Messenger(new RouterHandler(this));

	/**
	 * Handler of incoming messages from clients.
	 */
	static class RouterHandler extends Handler {
		final WeakReference<SdlAoaRouterService> provider;

		public RouterHandler(SdlAoaRouterService provider){
			this.provider = new WeakReference<SdlAoaRouterService>(provider);
		}

		@Override
		public void handleMessage(Message msg) {
			//DebugTool.logInfo(String.format("handleMessage: msg.what=%d", msg.what));
			if(this.provider.get() == null){
				return;
			}
			final Bundle receivedBundle = msg.getData();
			Bundle returnBundle;
			final SdlAoaRouterService service = this.provider.get();

			switch (msg.what) {
				case TransportConstants.ROUTER_REGISTER_CLIENT: //msg.arg1 is appId
					//pingClients();
					DebugTool.logInfo("ROUTER_REGISTER_CLIENT");
					Message message = Message.obtain();
					message.what = TransportConstants.ROUTER_REGISTER_CLIENT_RESPONSE;
					message.arg1 = TransportConstants.REGISTRATION_RESPONSE_SUCESS;
					String appId = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
					if(appId == null){
						appId = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
					}
					if(appId == null || appId.length()<=0 || msg.replyTo == null){
						Log.w(TAG, "Unable to register app as no id or messenger was included");
						if(msg.replyTo!=null){
							message.arg1 = TransportConstants.REGISTRATION_RESPONSE_DENIED_APP_ID_NOT_INCLUDED;
							try {
								msg.replyTo.send(message);
							} catch (RemoteException e) {
								e.printStackTrace();
							}
						}
						break;
					}
					if(service.legacyModeEnabled){
						DebugTool.logWarning("Unable to register app as legacy mode is enabled");
						if(msg.replyTo!=null){
							message.arg1 = TransportConstants.REGISTRATION_RESPONSE_DENIED_LEGACY_MODE_ENABLED;
							try {
								msg.replyTo.send(message);
							} catch (RemoteException e) {
								e.printStackTrace();
							}
						}
						break;
					}

					RegisteredApp app = service.new RegisteredApp(appId,msg.replyTo);
					service.putRegisteredApp(appId, app);
					service.onAppRegistered(app);

					returnBundle = new Bundle();
					//Add params if connected
					//if(service.isTransportConnected){
					returnBundle.putString(TransportConstants.HARDWARE_CONNECTED_AOA, TransportType.MULTIPLEX_AOA.name());
						//if(MultiplexBluetoothTransport.currentlyConnectedDevice!=null){
						//	returnBundle.putString(CONNECTED_DEVICE_STRING_EXTRA_NAME, MultiplexBluetoothTransport.currentlyConnectedDevice);
						//}
					//}
					//Add the version of this router service
					returnBundle.putInt(TransportConstants.ROUTER_SERVICE_VERSION, ROUTER_SERVICE_VERSION_NUMBER);

					message.setData(returnBundle);

					int result = app.sendMessage(message);
					/*--
					if(result == RegisteredApp.SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT){
						synchronized(service.REGISTERED_APPS_LOCK){
							registeredApps.remove(appId);
						}
					}
					--*/
					break;
				case TransportConstants.ROUTER_UNREGISTER_CLIENT:
					String appIdToUnregister = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
					if(appIdToUnregister == null){
						appIdToUnregister = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
					}
					DebugTool.logInfo("Unregistering client: " + appIdToUnregister);
					Message response = Message.obtain();
					response.what = TransportConstants.ROUTER_UNREGISTER_CLIENT_RESPONSE;
					RegisteredApp unregisteredApp = service.removeRegisteredApp(appIdToUnregister);
					if(unregisteredApp == null){
						response.arg1 = TransportConstants.UNREGISTRATION_RESPONSE_FAILED_APP_ID_NOT_FOUND;
						service.removeAllSessionsWithAppId(appIdToUnregister);
					}else{
						response.arg1 = TransportConstants.UNREGISTRATION_RESPONSE_SUCESS;
						service.removeAllSessionsForApp(unregisteredApp,false);
					}
					//Log.i(TAG, "Unregistering client response: " + response.arg1 );
					try {
						msg.replyTo.send(response); //We do this because we aren't guaranteed to find the correct registeredApp to send the message through
					} catch (RemoteException e) {
						e.printStackTrace();

					}catch(NullPointerException e2){
						Log.e(TAG, "No reply address included, can't send a reply");
					}

					break;
				case TransportConstants.ROUTER_SEND_PACKET:
					//DebugTool.logInfo("ROUTER_SEND_PACKET: Received packet to send");
					if(receivedBundle!=null){
						Runnable packetRun = new Runnable(){
							@Override
							public void run() {
								if(receivedBundle!=null){
									String buffAppId = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
									if(buffAppId == null){
										buffAppId = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
									}
									RegisteredApp buffApp = null;
									if(buffAppId!=null){
										synchronized(service.REGISTERED_APPS_LOCK){
											buffApp = service.getRegisteredApp(buffAppId);
										}
									}

									if(buffApp !=null){
										//Log.d(TAG, "buffApp=" + buffApp.toString());
										buffApp.handleIncommingClientMessage(receivedBundle);
									}else{
										service.writeBytesToTransport(receivedBundle);
									}
								}
							}
						};
						if(service.packetExecuter!=null){
							service.packetExecuter.execute(packetRun);
						}
					}
					break;
				case TransportConstants.ROUTER_REQUEST_NEW_SESSION:
					DebugTool.logInfo("ROUTER_REQUEST_NEW_SESSION");
					// TEST TEST
					//provider.get().onTransportConnected(TransportType.MULTIPLEX_AOA);

					String appIdRequesting = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
					if(appIdRequesting == null){
						appIdRequesting = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
					}
					Message extraSessionResponse = Message.obtain();
					extraSessionResponse.what = TransportConstants.ROUTER_REQUEST_NEW_SESSION_RESPONSE;
					if(appIdRequesting!=null && appIdRequesting.length()>0){
						synchronized(service.REGISTERED_APPS_LOCK){
							if(registeredApps!=null){
								RegisteredApp appRequesting = service.getRegisteredApp(appIdRequesting);
								if(appRequesting!=null){
									appRequesting.getSessionIds().add((long)-1); //Adding an extra session
									extraSessionResponse.arg1 = TransportConstants.ROUTER_REQUEST_NEW_SESSION_RESPONSE_SUCESS;
								}else{
									extraSessionResponse.arg1 = TransportConstants.ROUTER_REQUEST_NEW_SESSION_RESPONSE_FAILED_APP_NOT_FOUND;
								}
							}
						}
					}else{
						extraSessionResponse.arg1 = TransportConstants.ROUTER_REQUEST_NEW_SESSION_RESPONSE_FAILED_APP_ID_NOT_INCL;
					}
					try {
						msg.replyTo.send(extraSessionResponse); //We do this because we aren't guaranteed to find the correct registeredApp to send the message through
					} catch (RemoteException e) {
						e.printStackTrace();
					}catch(NullPointerException e2){
						Log.e(TAG, "No reply address included, can't send a reply");
					}
					break;
				case  TransportConstants.ROUTER_REMOVE_SESSION:
					String appIdWithSession = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
					if(appIdWithSession == null){
						appIdWithSession = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
					}
					long sessionId = receivedBundle.getLong(TransportConstants.SESSION_ID_EXTRA, -1);
					service.removeSessionFromMap((int)sessionId);
					Message removeSessionResponse = Message.obtain();
					removeSessionResponse.what = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE;
					if(appIdWithSession != null && appIdWithSession.length()>0){
						if(sessionId>=0){
							synchronized(service.REGISTERED_APPS_LOCK){
								if(registeredApps!=null){
									RegisteredApp appRequesting = service.getRegisteredApp(appIdWithSession);
									if(appRequesting!=null){
										if(appRequesting.removeSession(sessionId)){
											removeSessionResponse.arg1 = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE_SUCESS;
										}else{
											removeSessionResponse.arg1 = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE_FAILED_SESSION_NOT_FOUND;
										}
									}else{
										removeSessionResponse.arg1 = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE_FAILED_APP_NOT_FOUND;
									}
								}
							}
						}else{
							removeSessionResponse.arg1 = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE_FAILED_SESSION_ID_NOT_INCL;
						}
					}else{
						removeSessionResponse.arg1 = TransportConstants.ROUTER_REMOVE_SESSION_RESPONSE_FAILED_APP_ID_NOT_INCL;
					}
					try {
						msg.replyTo.send(removeSessionResponse); //We do this because we aren't guaranteed to find the correct registeredApp to send the message through
					} catch (RemoteException e) {
						e.printStackTrace();
					}catch(NullPointerException e2){
						Log.e(TAG, "No reply address included, can't send a reply");
					}
					break;
				case TransportConstants.ROUTER_REQUEST_USB_ATTACHED:
					if (msg.arg1 == MultiplexAOATransport.MUX_STATE_CONNECTED) {
						provider.get().onTransportConnected(TransportType.MULTIPLEX_AOA);
					}
					break;
				default:
					super.handleMessage(msg);
			}
		}
	}

	// @TODO: Need to check how to communicate with AltTransportHandler in SdlRouterService.

	final Messenger routerStatusMessenger = new Messenger(new RouterStatusHandler(this));
	static class RouterStatusHandler extends Handler {
		final WeakReference<SdlAoaRouterService> provider;

		public RouterStatusHandler(SdlAoaRouterService provider){
			this.provider = new WeakReference<SdlAoaRouterService>(provider);
		}

		@Override
		public void handleMessage(Message msg) {
			if(this.provider.get() == null){
				return;
			}
			SdlAoaRouterService service = this.provider.get();
			switch(msg.what){
				case TransportConstants.ROUTER_STATUS_CONNECTED_STATE_REQUEST:
					int flags = msg.arg1;
					if(msg.replyTo!=null){
						Message message = Message.obtain();
						message.what = TransportConstants.ROUTER_STATUS_CONNECTED_STATE_RESPONSE;
						message.arg1 = (service.isTransportConnected == true) ? 1 : 0;
						try {
							msg.replyTo.send(message);
						} catch (RemoteException e) {
							e.printStackTrace();
						}
					}
					if(service.isTransportConnected && ((TransportConstants.ROUTER_STATUS_FLAG_TRIGGER_PING  & flags) == TransportConstants.ROUTER_STATUS_FLAG_TRIGGER_PING)){
						if(service.pingIntent == null){
							service.initPingIntent(this.getClass());
						}
						DebugTool.logInfo("sendBroadcast(pingIntent)");
						service.getBaseContext().sendBroadcast(service.pingIntent);
					}
					break;
				default:
					Log.w(TAG, "Unsopported request: " + msg.what);
					break;
			}
		}
	};

	/* **************************************************************************************************************************************
	***********************************************  Life Cycle **************************************************************
	****************************************************************************************************************************************/
	@Override
	public IBinder onBind(Intent intent) {
		//Check intent to send back the correct binder (client binding vs alt transport)
		if(intent!=null){
			if(closing){
				DebugTool.logWarning("Denying bind request due to service shutting down.");
				return null;
			}
			String requestType = intent.getAction();//intent.getIntExtra(TransportConstants.ROUTER_BIND_REQUEST_TYPE_EXTRA, TransportConstants.BIND_REQUEST_TYPE_CLIENT);
			DebugTool.logInfo("onBind: " + requestType);
			// @TODO: need taking this into account
			if(false) {//TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT.equals(requestType)){
				//if(0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)){ //Only allow alt transport in debug mode
				//	return this.altTransportMessenger.getBinder();
				//}
			}else if(TransportConstants.BIND_REQUEST_TYPE_CLIENT.equals(requestType)){
				return this.routerMessenger.getBinder();
			}else if(TransportConstants.BIND_REQUEST_TYPE_STATUS.equals(requestType)){
				return this.routerStatusMessenger.getBinder();
			}else{
				DebugTool.logWarning("Uknown bind request type");
			}
		}
		return null;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		DebugTool.logInfo("Unbind being called.");
		return super.onUnbind(intent);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		DebugTool.enableDebugTool();

		initPassed = true;
		closing = false;

		startVersionCheck();
		DebugTool.logInfo("SDL AOA Router Service has been created");

		packetExecuter =  Executors.newSingleThreadExecutor();
		super.setTransportWriter(this);

		if(packetWriteTaskMaster!=null){
			packetWriteTaskMaster.close();
			packetWriteTaskMaster = null;
		}
		packetWriteTaskMaster = new PacketWriteTaskMaster();
		packetWriteTaskMaster.start();
		DebugTool.logInfo("packetWriteTaskMaster created onCreate");

		notifyStartedService(getApplicationContext());
	}

	HashMap<String,ResolveInfo> sdlMultiList ;
	public void startVersionCheck(){
		Intent intent = new Intent(START_SERVICE_ACTION);
		List<ResolveInfo> infos = getPackageManager().queryBroadcastReceivers(intent, 0);
		sdlMultiList = new HashMap<String,ResolveInfo>();
		for(ResolveInfo info: infos){
			if(getPackageName().equals(info.activityInfo.applicationInfo.packageName)){
				//DebugTool.logInfo("Ignoring my own package");
				continue;
			}
			sdlMultiList.put(info.activityInfo.packageName, info);
		}
		registerAnInstanceOfSerialServer = new BroadcastReceiver() {
			final Object COMPARE_LOCK = new Object();
			@Override
			public void onReceive(Context context, Intent intent)
			{
				/*-- I don't think AOA Router Service needs version check.
				LocalRouterService tempService = intent.getParcelableExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_EXTRA);
				synchronized(COMPARE_LOCK){
					//Let's make sure we are on the same version.
					if(tempService!=null && tempService.name!=null){
						sdlMultiList.remove(tempService.name.getPackageName());
						if((localCompareTo == null || localCompareTo.isNewer(tempService)) && AndroidTools.isServiceExported(context, tempService.name)){
							LocalRouterService self = getLocalRouterService();
							if(!self.isEqual(tempService)){ //We want to ignore self
								Log.i(TAG, "Newer service received than previously stored service - " + tempService.launchIntent.getAction());
								localCompareTo = tempService;
							}else{
								Log.i(TAG, "Ignoring self local router service");
							}
						}
						if(sdlMultiList.isEmpty()){
							DebugTool.logInfo("All router services have been accounted more. We can start the version check now");
						//-- @TODO: Need to check if versionCheckTimeoutHandler is needed.
						if(versionCheckTimeOutHandler!=null){
							versionCheckTimeOutHandler.removeCallbacks(versionCheckRunable);
							versionCheckTimeOutHandler.post(versionCheckRunable);
						}--//
						}
					}
				}
				--*/
						/*if(intent!=null && intent.getBooleanExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_DID_START_OWN, false)){
							Log.w(TAG, "Another serivce has been started, let's resend our version info to make sure they know about us too");
						}*/

			}
			@SuppressWarnings("unused")
			private void notifyStartedService(Context context){
				Intent restart = new Intent(REGISTER_NEWER_SERVER_INSTANCE_ACTION);
				//restart.putExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_EXTRA, getLocalRouterService());
				context.sendBroadcast(restart);
			}
		};

		registerReceiver(registerAnInstanceOfSerialServer, new IntentFilter(REGISTER_NEWER_SERVER_INSTANCE_ACTION));
		// @TODO: newest version check if needed.
		//newestServiceCheck(currentContext);
		startUpSequence();
	}

	private void notifyStartedService(Context context){
		DebugTool.logInfo("notifyStartedService gets called; about sendingBroadcast for " + REGISTER_NEWER_SERVER_INSTANCE_ACTION);
		Intent restart = new Intent(REGISTER_NEWER_SERVER_INSTANCE_ACTION);
		//restart.putExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_EXTRA, getLocalRouterService());
		context.sendBroadcast(restart);
	}

	public void startUpSequence(){
		// @TODO: disconnect listener if needed.
		/*--
		IntentFilter disconnectFilter = new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED");
		disconnectFilter.addAction("android.bluetooth.device.action.CLASS_CHANGED");
		disconnectFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
		disconnectFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED");
		registerReceiver(mListenForDisconnect,disconnectFilter );
		---*/

		IntentFilter filter = new IntentFilter();
		filter.addAction(REGISTER_WITH_ROUTER_ACTION);
		registerReceiver(mainServiceReceiver,filter);

		// @TODO: if support AltTransport
		/*--
		if(altTransportTimerHandler!=null){
			//There's an alt transport waiting for this service to be started
			Intent intent =  new Intent(TransportConstants.ALT_TRANSPORT_RECEIVER);
			sendBroadcast(intent);
		} --*/

		startSequenceComplete= true;
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(!initPassed) {
			return super.onStartCommand(intent, flags, startId);
		}
		if (!shouldServiceRemainOpen(this)) {
			DebugTool.logInfo("shouldServiceRemainOpen false");
			shutdownService();
		} else {
			DebugTool.logInfo("shouldServiceRemainOpen true");
			initMuxTransport();
			// @REVIEW: assuming USB is connected here.
			this.onTransportConnected(TransportType.MULTIPLEX_AOA);
		}
		return super.onStartCommand(intent, flags, startId);
	}

	private synchronized void initMuxTransport() {
		// for AOA, we should start Transport before actually app being registered.
		DebugTool.logInfo("initMuxTransport");
		if (mMuxAoaTransport == null) {
			mMuxAoaTransport = MultiplexAOATransport.getInstance(getApplicationContext(), mHandlerAOA);
		}
		mMuxAoaTransport.start();
	}

	public static boolean shouldServiceRemainOpen(Context context) {
		UsbManager usbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
		UsbAccessory[] accessories = usbManager.getAccessoryList();
		if (accessories == null) {
			return false;
		}
		for (UsbAccessory accessory: accessories) {
			if (accessory.getManufacturer().equalsIgnoreCase("SDL") && accessory.getModel().equalsIgnoreCase("Core")) {
				Log.d(TAG, accessory.toString() + " is allowed in shouldServiceRemainOpen");
			} else {
				Log.d(TAG, accessory.toString() + " does not look like SDL AOA params, but we allow it so far.");
			}
		}
		return true;
	}

	// @TODO: processCheck if needed.
	// @TODO: initCheck if needed
	// @TODO: permission check if needed.
	// @TODO: startVersionCheck if needed.
	// @TODO: startUpSequence
	@Override
	public void onDestroy() {
		stopClientPings();

		unregisterAllReceivers();

		if (registerAnInstanceOfSerialServer != null) {
			unregisterReceiver(registerAnInstanceOfSerialServer);
			registerAnInstanceOfSerialServer = null;
		}
		startSequenceComplete=false;
		if(packetExecuter!=null){
			packetExecuter.shutdownNow();
			packetExecuter = null;
		}
		//exitForeground();
		if(packetWriteTaskMaster!=null){
			packetWriteTaskMaster.close();
			packetWriteTaskMaster = null;
			DebugTool.logInfo("destroy packetWriteTaskMaster");
		}
		if (mMuxAoaTransport != null) {
			mMuxAoaTransport.stop();
			mMuxAoaTransport = null;
		}
		super.onDestroy();
	}

	private void unregisterAllReceivers() {
		if (startSequenceComplete) {
			unregisterReceiver(mainServiceReceiver);
		}
	}


	public void onTransportConnected(final TransportType type){
		// This will run in foreground service. So make sure we are in foreground beforehand.
		//if (!isForegroundService()) {
		//	DebugTool.logInfo("we are not foreground service");
			//return;
		//}

		DebugTool.logInfo("onTransportConnected: type=" + type.toString());
		isTransportConnected = true;
		enterForeground(android.R.drawable.stat_sys_data_bluetooth); // @TODO change drawable for AOA.

		// packetWriterTaskMaster instance is created onCreate.
		connectedTransportType = type;

		// @TODO: need to check if this is the right place.
		requestAccessory();

		Intent startService = new Intent();
		startService.setAction(START_SERVICE_ACTION);
		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_EXTRA, true);
		startService.putExtra(TransportConstants.FORCE_TRANSPORT_CONNECTED, true);
		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_APP_PACKAGE, getBaseContext().getPackageName());
		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_CMP_NAME, new ComponentName(this, this.getClass()));
		sendBroadcast(startService);
		//HARDWARE_CONNECTED
		if(!(registeredApps== null || registeredApps.isEmpty())){
			//If we have clients
			notifyClients(createHardwareConnectedMessage(type));
		}
	}

	@Override
	public void onTransportDisconnected(TransportType type) {
		super.onTransportDisconnected(type);
		DebugTool.logInfo("onTransportDisconnected");
		isTransportConnected = false;
		connectedTransportType = null;

		stopClientPings();

		exitForeground();//Leave our foreground state as we don't have a connection anymore

		if(registeredApps== null || registeredApps.isEmpty()){
			Intent unregisterIntent = new Intent();
			if (type.equals(TransportType.MULTIPLEX_AOA)) {
				unregisterIntent.putExtra(TransportConstants.HARDWARE_DISCONNECTED_AOA, type.name());
			} else {
				// should not happen..
				unregisterIntent.putExtra(TransportConstants.HARDWARE_DISCONNECTED, type.name());
			}
			unregisterIntent.putExtra(TransportConstants.ENABLE_LEGACY_MODE_EXTRA, legacyModeEnabled);
			unregisterIntent.setAction(TransportConstants.START_ROUTER_SERVICE_ACTION);
			sendBroadcast(unregisterIntent);
			//return;
		}else{
			Message message = Message.obtain();
			message.what = TransportConstants.HARDWARE_CONNECTION_EVENT;
			Bundle bundle = new Bundle();
			if (type.equals(TransportType.MULTIPLEX_AOA)) {
				bundle.putString(TransportConstants.HARDWARE_DISCONNECTED_AOA, type.name());
			} else {
				bundle.putString(TransportConstants.HARDWARE_DISCONNECTED, type.name());
			}
			bundle.putBoolean(TransportConstants.ENABLE_LEGACY_MODE_EXTRA, legacyModeEnabled);
			message.setData(bundle);
			notifyClients(message);
		}
		// also need to shutdown
		shutdownService();
	}

	public void onPacketRead(SdlPacket packet){
		try {
			//Log.i(TAG, "******** Read packet with header: " +(packet).toString());
			if(packet.getVersion() == 1){
				if( packet.getFrameType() == FrameType.Control && packet.getFrameInfo() == SdlPacket.FRAME_INFO_START_SERVICE_ACK){
					//We received a v1 packet from the head unit, this means we can't use the router service.
					//Enable legacy mode
					enableLegacyMode(true);
					return;
				}
			}else if(cachedModuleVersion == -1){
				cachedModuleVersion = packet.getVersion();
			}
			//Send the received packet to the registered app
			sendPacketToRegisteredApp(packet);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private final Handler mHandlerAOA = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MESSAGE_STATE_CHANGE:
					switch (msg.arg1) {
						case MultiplexAOATransport.MUX_STATE_CONNECTED:
							onTransportConnected(TransportType.MULTIPLEX_AOA);
							break;
						case MultiplexAOATransport.MUX_STATE_CONNECTING:
							// Currently attempting to connect - update UI?
							break;
						case MultiplexAOATransport.MUX_STATE_LISTEN:
							break;
						case MultiplexAOATransport.MUX_STATE_NONE:
							// We've just lost the connection
							if(!connectAsClient ){
								if(!legacyModeEnabled && !closing){
									//initAOAService(); @TODO: do we need to implement this?
								}
								onTransportDisconnected(TransportType.MULTIPLEX_AOA);
							}
							break;
						case MultiplexBluetoothTransport.STATE_ERROR:
							/*--
							if(mMuxAoaTransport!=null){
								Log.d(TAG, "Bluetooth serial server error received, setting state to none, and clearing local copy");
								mMuxAoaTransport.setMuxState(MultiplexBluetoothTransport.STATE_NONE);
								mMuxAoaTransport = null;
							} --*/
							break;
					}
					break;

				case MESSAGE_READ:
					onPacketRead((SdlPacket) msg.obj);
					break;
			}
		}
	};


	//
	/**
	 *
	 * ITransportWriter interface impl.
	 */

	public boolean writeBytesToTransport(Bundle bundle){
		if(bundle == null){
			return false;
		}
		if(mMuxAoaTransport !=null && mMuxAoaTransport.getMuxState()== MultiplexAOATransport.MUX_STATE_CONNECTED){
			byte[] packet = bundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
			int offset = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, 0); //If nothing, start at the begining of the array
			int count = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, packet.length);  //In case there isn't anything just send the whole packet.
			if(packet!=null){
				mMuxAoaTransport.write(packet,offset,count);
				return true;
			}
			return false;
			/*-- @TODO: sendThroughAltTransport
		}else if(sendThroughAltTransport(bundle)){
			return true;
			---*/
		}
		else{
			Log.e(TAG, "Can't send data, no transport connected; mMuxAoaTransport=" + ((mMuxAoaTransport == null) ? "null" : "non-null"));
			return false;
		}
	}

	public boolean manuallyWriteBytes(byte[] bytes, int offset, int count){
		if(mMuxAoaTransport !=null && mMuxAoaTransport.getMuxState()== MultiplexAOATransport.MUX_STATE_CONNECTED){
			if(bytes!=null){
				mMuxAoaTransport.write(bytes,offset,count);
				return true;
			}
			return false;
			/*-- @TODO: make sure if sendThroughAltTransport is unused.
		}else if(sendThroughAltTransport(bytes,offset,count)){
			return true;
			---*/
		}else{
			return false;
		}
	}


	// @TODO: make sure if sendThroughAltTransport is unused.

	// @TODO: make sure if sendThroughAltTransport is unused.

	// @TODO: make sure if getLocalRouterService(Intent launchIntent, ComponentName name) is unused.

	// @TODO: make sure if getLocalRouterService() is unused.

	// @TODO: make sure if newestServiceCheck is unused.

	private synchronized void shutdownService() {
		if(getBaseContext()!=null){
			DebugTool.logInfo("stopSelf because of baseContext");
			onDestroy();
			stopSelf();
			//*-- comment this block out if need to suppress kill process for debugging purpose
			Handler handler = new Handler(Looper.getMainLooper());
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					try{
						android.os.Process.killProcess(android.os.Process.myPid());
					}catch(Exception e){}
				}
			}, 200); //--*/

		}else{
			DebugTool.logInfo("onDestroy because of baseContext");
			onDestroy();
		}
	}

	/**
	 * accessory
	 *
	 */
	private void requestAccessory() {
		UsbManager manager = (UsbManager)getSystemService(Context.USB_SERVICE);
		UsbAccessory[] accessories = manager.getAccessoryList();
		if (accessories != null) {
			for(UsbAccessory accessory : accessories) {
				DebugTool.logInfo("requestAccessory: " + accessory.toString());
				if (isAccessorySupported(accessory)) {
					if (mMuxAoaTransport != null) {
						mMuxAoaTransport.connectToAccessory(accessory);
						return;
					}
				}
			}
		}

	}
	private final static String ACCESSORY_MANUFACTURER = "SDL";
	private final static String ACCESSORY_MODEL = "Core";
	private final static String ACCESSORY_VERSION = "1.0";

	private static boolean isAccessorySupported(UsbAccessory accessory) {
		boolean manufacturerMatches =
				ACCESSORY_MANUFACTURER.equals(accessory.getManufacturer());
		boolean modelMatches = ACCESSORY_MODEL.equals(accessory.getModel());
		boolean versionMatches =
				ACCESSORY_VERSION.equals(accessory.getVersion());
		return manufacturerMatches && modelMatches && versionMatches;
	}

	/* ***********************************************************************************************************************************************************************
	 * *****************************************************************  CUSTOM ADDITIONS  ************************************************************************************
	 *************************************************************************************************************************************************************************/

	// @TODO: make sure if getLocalBluetoothServiceCompare is unused.

	// @TODO: make sure if getLocalRouterService is unused.


	/*--
	private boolean isForegroundService() {
		String thisPackage = getApplicationContext().getPackageName();
		ActivityManager manager = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);
		List<ActivityManager.RunningServiceInfo> serviceList = manager.getRunningServices(1000);
		for (ActivityManager.RunningServiceInfo info: serviceList) {
			//Log.d(TAG, "runningPackage: " + info.service.getPackageName());
			if (info.service.getPackageName().equalsIgnoreCase(thisPackage)) {
				//Log.d(TAG, "foundPackage: " + info.service.getPackageName());
				return info.foreground;
			}
		}
		return false;
	} ---*/

	/* ****************************************************************************************************************************************
	// ***********************************************************   LEGACY MODE   ****************************************************************
	//*****************************************************************************************************************************************/
	private boolean legacyModeEnabled = false;

	private void enableLegacyMode(boolean enable){
		DebugTool.logInfo("Enable legacy mode: " + enable);
		legacyModeEnabled = enable; //We put this at the end to avoid a race condition between the bluetooth d/c and notify of legacy mode enabled

		if(legacyModeEnabled){
			// @TODO: shutdown service.

		}//else{}

	}

	// @TODO: LocalRouterService
}

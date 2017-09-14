package com.smartdevicelink.transport;

import static com.smartdevicelink.proxy.constants.Names.info;
import static com.smartdevicelink.transport.TransportConstants.CONNECTED_DEVICE_STRING_EXTRA_NAME;
import static com.smartdevicelink.transport.TransportConstants.FORMED_PACKET_EXTRA_NAME;
import static com.smartdevicelink.transport.TransportConstants.HARDWARE_DISCONNECTED;
import static com.smartdevicelink.transport.TransportConstants.SEND_PACKET_TO_APP_LOCATION_EXTRA_NAME;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
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
import com.smartdevicelink.util.AndroidTools;
import com.smartdevicelink.util.BitConverter;
import com.smartdevicelink.util.DebugTool;

/**
 * <b>This class should not be modified by anyone outside of the approved contributors of the SmartDeviceLink project.</b>
 * This service is a central point of communication between hardware and the registered clients. It will multiplex a single transport
 * to provide a connection for a theoretical infinite amount of SDL sessions. 
 * @author Joey Grover
 *
 */
public class SdlRouterService extends SdlRouterBase implements ITransportWriter {
	
	private static final String TAG = "Sdl Router Service";
	/**
	 * <b> NOTE: DO NOT MODIFY THIS UNLESS YOU KNOW WHAT YOU'RE DOING.</b>
	 */

	private static final String ROUTER_SERVICE_PROCESS = "com.smartdevicelink.router";

	private MultiplexBluetoothTransport mSerialService = null;

	private static boolean connectAsClient = false;
	private static boolean closing = false;
	private static Context currentContext = null;
    
    private Handler versionCheckTimeOutHandler, altTransportTimerHandler;
    private Runnable versionCheckRunable, altTransportTimerRunnable;
    private LocalRouterService localCompareTo = null;
    private final static int VERSION_TIMEOUT_RUNNABLE = 1500;
    private final static int ALT_TRANSPORT_TIMEOUT_RUNNABLE = 30000; 
	
    private boolean wrongProcess = false;

	private static Messenger altTransportService = null;
	
	private boolean startSequenceComplete = false;
	
	private ExecutorService packetExecuter = null; 

	private static LocalRouterService selfRouterService;

	private int cachedModuleVersion = -1;
	

	/* **************************************************************************************************************************************
	***********************************************  Broadcast Receivers stuff  **************************************************************
	****************************************************************************************************************************************/

	private void onAppRegistered(RegisteredApp app){
		//Log.enableDebug(receivedIntent.getBooleanExtra(LOG_BASIC_DEBUG_BOOLEAN_EXTRA, false));
		//Log.enableBluetoothTraceLogging(receivedIntent.getBooleanExtra(LOG_TRACE_BT_DEBUG_BOOLEAN_EXTRA, false));
		//Ok this is where we should do some authenticating...maybe.
		//Should we ask for all relevant data in this packet?
		if(BluetoothAdapter.getDefaultAdapter()!=null && BluetoothAdapter.getDefaultAdapter().isEnabled()){

			if(startSequenceComplete &&
					!connectAsClient && (mSerialService ==null
					|| mSerialService.getState() == MultiplexBluetoothTransport.STATE_NONE)){
				//Log.e(TAG, "Serial service not initliazed while registering app");
				//Maybe we should try to do a connect here instead
				DebugTool.logInfo("Serial service being restarted");
				if(mSerialService ==null){
					DebugTool.logError("Local copy of BT Server is null");
					mSerialService = MultiplexBluetoothTransport.getBluetoothSerialServerInstance();
					if(mSerialService==null){
						DebugTool.logError("Local copy of BT Server is still null and so is global");
						mSerialService = MultiplexBluetoothTransport.getBluetoothSerialServerInstance(mHandlerBT);

					}
				}
				mSerialService.start();

			}
		}

		Log.i(TAG, app.appId + " has just been registered with SDL Router Service");
	}
	
	
	 /**
	  * this is to make sure the AceeptThread is still running
	  */
		BroadcastReceiver registerAnInstanceOfSerialServer = new BroadcastReceiver() {
			final Object COMPARE_LOCK = new Object();
					@Override
					public void onReceive(Context context, Intent intent)
					{
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
									Log.d(TAG, "All router services have been accounted more. We can start the version check now");
									if(versionCheckTimeOutHandler!=null){
										versionCheckTimeOutHandler.removeCallbacks(versionCheckRunable);
										versionCheckTimeOutHandler.post(versionCheckRunable);
									}
								}
							}
						}
						/*if(intent!=null && intent.getBooleanExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_DID_START_OWN, false)){
							Log.w(TAG, "Another serivce has been started, let's resend our version info to make sure they know about us too");
						}*/

					}
					@SuppressWarnings("unused")
					private void notifyStartedService(Context context){
						Intent restart = new Intent(SdlRouterService.REGISTER_NEWER_SERVER_INSTANCE_ACTION);
				    	restart.putExtra(SdlBroadcastReceiver.LOCAL_ROUTER_SERVICE_EXTRA, getLocalRouterService());
				    	context.sendBroadcast(restart);
					}
			};
			
			
	
	/**
	 * If the user disconnects the bluetooth device we will want to stop SDL and our current
	 * connection through RFCOMM
	 */
	BroadcastReceiver mListenForDisconnect = new BroadcastReceiver() 
			{
				@Override
				public void onReceive(Context context, Intent intent) 
				{
					String action = intent.getAction();
			    	if(action!=null){Log.d(TAG, "Disconnect received. Action: " + intent.getAction());}
			    	else{Log.d(TAG, "Disconnect received.");}
					if(intent.getAction()!=null && intent.getAction().equalsIgnoreCase("android.bluetooth.adapter.action.STATE_CHANGED") 
							&&(	(BluetoothAdapter.getDefaultAdapter().getState() == BluetoothAdapter.STATE_TURNING_ON) 
							|| (BluetoothAdapter.getDefaultAdapter().getState() == BluetoothAdapter.STATE_ON))){
						return;
					}

					connectAsClient=false;
					
					if(action!=null && intent.getAction().equalsIgnoreCase("android.bluetooth.adapter.action.STATE_CHANGED") 
							&&(	(BluetoothAdapter.getDefaultAdapter().getState() == BluetoothAdapter.STATE_TURNING_OFF) 
							|| (BluetoothAdapter.getDefaultAdapter().getState() == BluetoothAdapter.STATE_OFF))){
							Log.d(TAG, "Bluetooth is shutting off, SDL Router Service is closing.");
							//Since BT is shutting off...there's no reason for us to be on now. 
							//Let's take a break...I'm sleepy
							shouldServiceRemainOpen(intent);
						}
					else{//So we just got d/c'ed from the bluetooth...alright...Let the client know
						if(legacyModeEnabled){
							Log.d(TAG, "Legacy mode enabled and bluetooth d/c'ed, restarting router service bluetooth.");
							enableLegacyMode(false);
							onTransportDisconnected(TransportType.BLUETOOTH);
							initBluetoothSerialService();
						}
					}
			}
		};
		
/* **************************************************************************************************************************************
***********************************************  Broadcast Receivers End  **************************************************************
****************************************************************************************************************************************/

		/* **************************************************************************************************************************************
		*********************************************** Handlers for bound clients **************************************************************
		****************************************************************************************************************************************/


	    /**
	     * Target we publish for clients to send messages to RouterHandler.
	     */
	    final Messenger routerMessenger = new Messenger(new RouterHandler(this));
	    
		 /**
	     * Handler of incoming messages from clients.
	     */
	    static class RouterHandler extends Handler {
	    	final WeakReference<SdlRouterService> provider;

	    	public RouterHandler(SdlRouterService provider){
	    		this.provider = new WeakReference<SdlRouterService>(provider);
	    	}

	        @Override
	        public void handleMessage(Message msg) {
	        	if(this.provider.get() == null){
	        		return;
	        	}
	        	final Bundle receivedBundle = msg.getData();
	        	Bundle returnBundle;
	        	final SdlRouterService service = this.provider.get();
	        	
	            switch (msg.what) {
	            case TransportConstants.ROUTER_REQUEST_BT_CLIENT_CONNECT:              	
	            	if(receivedBundle.getBoolean(TransportConstants.CONNECT_AS_CLIENT_BOOLEAN_EXTRA, false)
	        				&& !connectAsClient){		//We check this flag to make sure we don't try to connect over and over again. On D/C we should set to false
	        				//Log.d(TAG,"Attempting to connect as bt client");
	        				BluetoothDevice device = receivedBundle.getParcelable(BluetoothDevice.EXTRA_DEVICE);
	        				connectAsClient = true;
	        				if(device==null || !service.bluetoothConnect(device)){
	        					Log.e(TAG, "Unable to connect to bluetooth device");
	        					connectAsClient = false;
	        				}
	        			}
	            	//**************** We don't break here so we can let the app register as well
	                case TransportConstants.ROUTER_REGISTER_CLIENT: //msg.arg1 is appId
	                	//pingClients();
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
	                		Log.w(TAG, "Unable to register app as legacy mode is enabled");
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
	                	synchronized(service.REGISTERED_APPS_LOCK){
	                		RegisteredApp old = registeredApps.put(app.getAppId(), app); 
	                		if(old!=null){
	                			Log.w(TAG, "Replacing already existing app with this app id");
	                			service.removeAllSessionsForApp(old, true);
	                			old.close();
	                		}
	                	}
	                	service.onAppRegistered(app);

	            		returnBundle = new Bundle();
	            		//Add params if connected
	            		if(service.isTransportConnected){
	            			returnBundle.putString(TransportConstants.HARDWARE_CONNECTED, service.connectedTransportType.name());
	                		if(MultiplexBluetoothTransport.currentlyConnectedDevice!=null){
	                			returnBundle.putString(TransportConstants.CONNECTED_DEVICE_STRING_EXTRA_NAME, MultiplexBluetoothTransport.currentlyConnectedDevice);
	                		}
	            		}
	            		//Add the version of this router service
	            		returnBundle.putInt(TransportConstants.ROUTER_SERVICE_VERSION, SdlRouterService.ROUTER_SERVICE_VERSION_NUMBER);
	            		
	            		message.setData(returnBundle);
	            		
	            		int result = app.sendMessage(message);
	            		if(result == RegisteredApp.SEND_MESSAGE_ERROR_MESSENGER_DEAD_OBJECT){
	            			synchronized(service.REGISTERED_APPS_LOCK){
	            				registeredApps.remove(appId);
	            			}
	            		}
	                    break;
	                case TransportConstants.ROUTER_UNREGISTER_CLIENT:
	                	String appIdToUnregister = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
	                	if(appIdToUnregister == null){
	                		appIdToUnregister = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
	                	}
	                	Log.i(TAG, "Unregistering client: " + appIdToUnregister);
	                	RegisteredApp unregisteredApp = null;
	                	synchronized(service.REGISTERED_APPS_LOCK){
	                		unregisteredApp = registeredApps.remove(appIdToUnregister);
	                	}
	                	Message response = Message.obtain();
	                	response.what = TransportConstants.ROUTER_UNREGISTER_CLIENT_RESPONSE;
	                	if(unregisteredApp == null){
	                		response.arg1 = TransportConstants.UNREGISTRATION_RESPONSE_FAILED_APP_ID_NOT_FOUND;
	                		service.removeAllSessionsWithAppId(appIdToUnregister);
	                	}else{
	                		response.arg1 = TransportConstants.UNREGISTRATION_RESPONSE_SUCESS;
	                		service.removeAllSessionsForApp(unregisteredApp,false);
	                	}
	                	Log.i(TAG, "Unregistering client response: " + response.arg1 );
	                	try {
	                		msg.replyTo.send(response); //We do this because we aren't guaranteed to find the correct registeredApp to send the message through
	                	} catch (RemoteException e) {
	                		e.printStackTrace();
	                		
	                	}catch(NullPointerException e2){
	                		Log.e(TAG, "No reply address included, can't send a reply");
	                	}
	                	
	                    break;
	                case TransportConstants.ROUTER_SEND_PACKET:
	                	Log.d(TAG, "Received packet to send");
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
	                							buffApp = registeredApps.get(buffAppId);
	                						}
	                					}
	                					
	                					if(buffApp !=null){
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
	                	String appIdRequesting = receivedBundle.getString(TransportConstants.APP_ID_EXTRA_STRING);
	                	if(appIdRequesting == null){
	                		appIdRequesting = "" + receivedBundle.getLong(TransportConstants.APP_ID_EXTRA, -1);
	                	}
	                	Message extraSessionResponse = Message.obtain();
	                	extraSessionResponse.what = TransportConstants.ROUTER_REQUEST_NEW_SESSION_RESPONSE;
	                	if(appIdRequesting!=null && appIdRequesting.length()>0){
							synchronized(service.REGISTERED_APPS_LOCK){
								if(registeredApps!=null){
									RegisteredApp appRequesting = registeredApps.get(appIdRequesting);
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
	                					RegisteredApp appRequesting = registeredApps.get(appIdWithSession);
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
	                default:
	                    super.handleMessage(msg);
	            }
	        }
	    }

	    
	    /**
	     * Target we publish for alternative transport (USB) clients to send messages to RouterHandler.
	     */
	    final Messenger altTransportMessenger = new Messenger(new AltTransportHandler(this));
	    
		 /**
	     * Handler of incoming messages from an alternative transport (USB).
	     */
	    static class AltTransportHandler extends Handler {
	    	ClassLoader loader; 
	    	final WeakReference<SdlRouterService> provider;

	    	public AltTransportHandler(SdlRouterService provider){
	    		this.provider = new WeakReference<SdlRouterService>(provider);
	    		loader = getClass().getClassLoader();
	    	}

	        @Override
	        public void handleMessage(Message msg) {
	        	if(this.provider.get() == null){
	        		return;
	        	}
	        	SdlRouterService service = this.provider.get();
	        	Bundle receivedBundle = msg.getData();
	        	switch(msg.what){
	        	case TransportConstants.HARDWARE_CONNECTION_EVENT:
        			if(receivedBundle.containsKey(TransportConstants.HARDWARE_DISCONNECTED)){
        				//We should shut down, so call 
        				if(altTransportService != null 
        						&& altTransportService.equals(msg.replyTo)){
        					//The same transport that was connected to the router service is now telling us it's disconnected. Let's inform clients and clear our saved messenger
        					altTransportService = null;
        					service.onTransportDisconnected(TransportType.valueOf(receivedBundle.getString(TransportConstants.HARDWARE_DISCONNECTED)));
        					service.shouldServiceRemainOpen(null); //this will close the service if bluetooth is not available
        				}
        			}else if(receivedBundle.containsKey(TransportConstants.HARDWARE_CONNECTED)){
    					Message retMsg =  Message.obtain();
    					retMsg.what = TransportConstants.ROUTER_REGISTER_ALT_TRANSPORT_RESPONSE;
        				if(altTransportService == null){ //Ok no other transport is connected, this is good
        					Log.d(TAG, "Alt transport connected.");
        					if(msg.replyTo == null){
        						break;
        					}
        					altTransportService = msg.replyTo;
        					//Clear out the timer to make sure the service knows we're good to go
        					if(service.altTransportTimerHandler!=null && service.altTransportTimerRunnable!=null){
        						service.altTransportTimerHandler.removeCallbacks(service.altTransportTimerRunnable);
        					}
        					service.altTransportTimerHandler = null;
        					service.altTransportTimerRunnable = null;
        					
        					//Let the alt transport know they are good to go
        					retMsg.arg1 = TransportConstants.ROUTER_REGISTER_ALT_TRANSPORT_RESPONSE_SUCESS;
        					service.onTransportConnected(TransportType.valueOf(receivedBundle.getString(TransportConstants.HARDWARE_CONNECTED)));
        				}else{ //There seems to be some other transport connected
        					//Error
        					retMsg.arg1 = TransportConstants.ROUTER_REGISTER_ALT_TRANSPORT_ALREADY_CONNECTED;
        				}
        				if(msg.replyTo!=null){
        					try {msg.replyTo.send(retMsg);} catch (RemoteException e) {e.printStackTrace();}
        				}
        			}
            		break;
	        	case TransportConstants.ROUTER_RECEIVED_PACKET:
	        		if(receivedBundle!=null){
	        			receivedBundle.setClassLoader(loader);//We do this because loading a custom parceable object isn't possible without it
					if(receivedBundle.containsKey(TransportConstants.FORMED_PACKET_EXTRA_NAME)){
						SdlPacket packet = receivedBundle.getParcelable(TransportConstants.FORMED_PACKET_EXTRA_NAME);
						if(packet!=null){
							service.onPacketRead(packet);
						}else{
							Log.w(TAG, "Received null packet from alt transport service");
						}
					}else{
						Log.w(TAG, "Flase positive packet reception");
					}
	            		}else{
	            			Log.e(TAG, "Bundle was null while sending packet to router service from alt transport");
	            		}
            			break; 
	        	default:
	        		super.handleMessage(msg);
	        	}
	        	
	        }
	    };
	    
	    /**
	     * Target we publish for alternative transport (USB) clients to send messages to RouterHandler.
	     */
	    final Messenger routerStatusMessenger = new Messenger(new RouterStatusHandler(this));
	    
		 /**
	     * Handler of incoming messages from an alternative transport (USB).
	     */
	    static class RouterStatusHandler extends Handler {
	    	 final WeakReference<SdlRouterService> provider;

	    	 public RouterStatusHandler(SdlRouterService provider){
				 this.provider = new WeakReference<SdlRouterService>(provider);
	    	 }

	        @Override
	        public void handleMessage(Message msg) {
	        	if(this.provider.get() == null){
	        		return;
	        	}
	        	SdlRouterService service = this.provider.get();
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
				Log.w(TAG, "Denying bind request due to service shutting down.");
				return null;
			}
			String requestType = intent.getAction();//intent.getIntExtra(TransportConstants.ROUTER_BIND_REQUEST_TYPE_EXTRA, TransportConstants.BIND_REQUEST_TYPE_CLIENT);
			if(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT.equals(requestType)){
				if(0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)){ //Only allow alt transport in debug mode
					return this.altTransportMessenger.getBinder();
				}
			}else if(TransportConstants.BIND_REQUEST_TYPE_CLIENT.equals(requestType)){
				return this.routerMessenger.getBinder();
			}else if(TransportConstants.BIND_REQUEST_TYPE_STATUS.equals(requestType)){
				return this.routerStatusMessenger.getBinder();
			}else{
				Log.w(TAG, "Uknown bind request type");
			}
			
		}
		return null;
	}

	
	
	@Override
	public boolean onUnbind(Intent intent) {
		Log.d(TAG, "Unbind being called.");
		return super.onUnbind(intent);
	}


	/**
	 * We want to make sure we are in the right process here. If there is somesort of developer error 
	 * we want to just close out right away.
	 * @return
	 */
	private boolean processCheck(){
		int myPid = android.os.Process.myPid();
		ActivityManager am = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);
		if(am == null || am.getRunningAppProcesses() == null)
			return false; // No RunningAppProcesses, let's close out
		for (RunningAppProcessInfo processInfo : am.getRunningAppProcesses())
		{
			if (processInfo != null && processInfo.pid == myPid)
			{
				return ROUTER_SERVICE_PROCESS.equals(processInfo.processName);
			}
		}
		return false;

	}
	
	private boolean permissionCheck(String permissionToCheck){
		if(permissionToCheck == null){
			throw new IllegalArgumentException("permission is null");
		}
		return PackageManager.PERMISSION_GRANTED == getBaseContext().checkPermission(permissionToCheck, android.os.Process.myPid(), android.os.Process.myUid());
	}

	/**
	 * Runs several checks to ensure this router service has the correct conditions to run properly 
	 * @return true if this service is set up correctly
	 */
	private boolean initCheck(){
		if(!processCheck()){
			Log.e(TAG, "Not using correct process. Shutting down");
			wrongProcess = true;
			return false;
		}
		if(!permissionCheck(Manifest.permission.BLUETOOTH)){
			Log.e(TAG, "Bluetooth Permission is not granted. Shutting down");
			return false;
		}
		if(!AndroidTools.isServiceExported(this, new ComponentName(this, this.getClass()))){ //We want to check to see if our service is actually exported
			Log.e(TAG, "Service isn't exported. Shutting down");
			return false;
		}
		return true;
	}


	@Override
	public void onCreate() {
		super.onCreate();

		if(!initCheck()){ // Run checks on process and permissions
			stopSelf();
			return;
		}
		initPassed = true;

		synchronized(REGISTERED_APPS_LOCK){
			registeredApps = new HashMap<String,RegisteredApp>();
		}
		closing = false;
		currentContext = getBaseContext();
		
		startVersionCheck();
		Log.i(TAG, "SDL Router Service has been created");
		
		packetExecuter =  Executors.newSingleThreadExecutor();
		super.setTransportWriter(this);
	}

	HashMap<String,ResolveInfo> sdlMultiList ;
	public void startVersionCheck(){
		Intent intent = new Intent(TransportConstants.START_ROUTER_SERVICE_ACTION);
		List<ResolveInfo> infos = getPackageManager().queryBroadcastReceivers(intent, 0);
		sdlMultiList = new HashMap<String,ResolveInfo>();
		for(ResolveInfo info: infos){
			//Log.d(TAG, "Sdl enabled app: " + info.activityInfo.packageName);
			if(getPackageName().equals(info.activityInfo.applicationInfo.packageName)){
				//Log.d(TAG, "Ignoring my own package");
				continue;
			}
			sdlMultiList.put(info.activityInfo.packageName, info);
		}
		registerReceiver(registerAnInstanceOfSerialServer, new IntentFilter(REGISTER_NEWER_SERVER_INSTANCE_ACTION));
		newestServiceCheck(currentContext);
	}
	
	public void startUpSequence(){
		IntentFilter disconnectFilter = new IntentFilter("android.bluetooth.adapter.action.STATE_CHANGED");
		disconnectFilter.addAction("android.bluetooth.device.action.CLASS_CHANGED");
		disconnectFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
		disconnectFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED");
		registerReceiver(mListenForDisconnect,disconnectFilter );
		
		IntentFilter filter = new IntentFilter();
		filter.addAction(REGISTER_WITH_ROUTER_ACTION);
		registerReceiver(mainServiceReceiver,filter);
		
		if(!connectAsClient){
			if(bluetoothAvailable()){
				initBluetoothSerialService();
			}
		}
		
		if(altTransportTimerHandler!=null){
			//There's an alt transport waiting for this service to be started
			Intent intent =  new Intent(TransportConstants.ALT_TRANSPORT_RECEIVER);
			sendBroadcast(intent);
		}
		
		startSequenceComplete= true;
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(!initPassed) {
			return super.onStartCommand(intent, flags, startId);
		}
		if(registeredApps == null){
			synchronized(REGISTERED_APPS_LOCK){
				registeredApps = new HashMap<String,RegisteredApp>();
			}
		}
		if(intent != null ){
			if(intent.hasExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA)){
				//Make sure we are listening on RFCOMM
				if(startSequenceComplete){ //We only check if we are sure we are already through the start up process
					Log.i(TAG, "Received ping, making sure we are listening to bluetooth rfcomm");
					initBluetoothSerialService();
				}
			}
		}
		shouldServiceRemainOpen(intent);
		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onDestroy(){
		super.onDestroy();

		stopClientPings();
		if(versionCheckTimeOutHandler!=null){
			versionCheckTimeOutHandler.removeCallbacks(versionCheckRunable);
			versionCheckTimeOutHandler = null;
		}
		if(altTransportTimerHandler!=null){
			altTransportTimerHandler.removeCallbacks(versionCheckRunable);
			altTransportTimerHandler = null;
			versionCheckRunable = null;
		}
		Log.w(TAG, "Sdl Router Service Destroyed");
	    closing = true;
		currentContext = null;
		//No need for this Broadcast Receiver anymore
		unregisterAllReceivers();
		closeBluetoothSerialServer();
		if(registeredApps!=null){
			synchronized(REGISTERED_APPS_LOCK){
				registeredApps.clear();
				registeredApps = null;
			}
		}
		//SESSION_LOCK = null;
		
		startSequenceComplete=false;
		if(packetExecuter!=null){
			packetExecuter.shutdownNow();
			packetExecuter = null;
		}
		
		exitForeground();
		
		if(packetWriteTaskMaster!=null){
			packetWriteTaskMaster.close();
			packetWriteTaskMaster = null;
		}
		
		super.onDestroy();
		System.gc(); //Lower end phones need this hint
		if(!wrongProcess){
			try{
				android.os.Process.killProcess(android.os.Process.myPid());
			}catch(Exception e){}
		}
	}
	
	private void unregisterAllReceivers(){
		try{
			unregisterReceiver(registerAnInstanceOfSerialServer);		///This should be first. It will always be registered, these others may not be and cause an exception.
			unregisterReceiver(mListenForDisconnect);
			unregisterReceiver(mainServiceReceiver);
		}catch(Exception e){}
	}
	
	private void notifyAltTransportOfClose(int reason){
		if(altTransportService!=null){
			Message msg = Message.obtain();
			msg.what = TransportConstants.ROUTER_SHUTTING_DOWN_NOTIFICATION;
			msg.arg1 = reason;
			try {
				altTransportService.send(msg);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}

	/* **************************************************************************************************************************************
	***********************************************  Helper Methods **************************************************************
	****************************************************************************************************************************************/
	
	/**
	 * Checks to make sure bluetooth adapter is available and on
	 * @return
	 */
	private boolean bluetoothAvailable(){
		try {
			boolean retVal = (!(BluetoothAdapter.getDefaultAdapter() == null) && BluetoothAdapter.getDefaultAdapter().isEnabled());
			//Log.d(TAG, "Bluetooth Available? - " + retVal);
			return retVal;
		}catch(NullPointerException e){ // only for BluetoothAdapter.getDefaultAdapter().isEnabled() call
			return false;
		}
	}

	/**
	 * 
	 * 1. If the app has SDL shut off, 												shut down
	 * 2. if The app has an Alt Transport address or was started by one, 			stay open
	 * 3. If Bluetooth is off/NA	 												shut down
	 * 4. Anything else					
	 */
	public boolean shouldServiceRemainOpen(Intent intent){
		//Log.d(TAG, "Determining if this service should remain open");
		
		if(altTransportService!=null || altTransportTimerHandler !=null){
			//We have been started by an alt transport, we must remain open. "My life for Auir...."
			Log.d(TAG, "Alt Transport connected, remaining open");
			return true;
			
		}else if(intent!=null && TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT.equals(intent.getAction())){
			Log.i(TAG, "Received start intent with alt transprt request.");
			startAltTransportTimer();
			return true;
		}else if(!bluetoothAvailable()){//If bluetooth isn't on...there's nothing to see here
			//Bluetooth is off, we should shut down
			Log.d(TAG, "Bluetooth not available, shutting down service");
			closeSelf();
			return false;
		}else{
			Log.d(TAG, "Service to remain open");
			return true;
		}
	}
	/**
	 * This method is needed so that apps that choose not to implement this as a service as defined by Android, but rather
	 * just a simple class we have to know how to shut down.
	 */
	public void closeSelf(){
		closing = true;
		if(getBaseContext()!=null){
			stopSelf();
		}else{
			onDestroy();
		}
	}
	private synchronized void initBluetoothSerialService(){
		if(legacyModeEnabled){
			Log.d(TAG, "Not starting own bluetooth during legacy mode");
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
	
	public void onTransportConnected(final TransportType type){
		isTransportConnected = true;
		enterForeground(android.R.drawable.stat_sys_data_bluetooth);
		if(packetWriteTaskMaster!=null){
			packetWriteTaskMaster.close();
			packetWriteTaskMaster = null;
		}
		packetWriteTaskMaster = new PacketWriteTaskMaster();
		packetWriteTaskMaster.start();
		
		connectedTransportType = type;
		
		Intent startService = new Intent();  
		startService.setAction(TransportConstants.START_ROUTER_SERVICE_ACTION);
		//Perform our query prior to adding any extras or flags
		List<ResolveInfo> sdlApps = getPackageManager().queryBroadcastReceivers(startService, 0);

		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_EXTRA, true);
		startService.putExtra(TransportConstants.FORCE_TRANSPORT_CONNECTED, true);
		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_APP_PACKAGE, getBaseContext().getPackageName());
		startService.putExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_CMP_NAME, new ComponentName(this, this.getClass()));
		startService.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

		//Iterate through all apps that we know are listening for this intent with an explicit intent (neccessary for Android O SDK 26)
		if(sdlApps != null && sdlApps.size()>0){
			for(ResolveInfo app: sdlApps){
				startService.setClassName(app.activityInfo.applicationInfo.packageName, app.activityInfo.name);
				sendBroadcast(startService);
			}
		}

		//HARDWARE_CONNECTED
    	if(!(registeredApps== null || registeredApps.isEmpty())){
    		//If we have clients
			notifyClients(createHardwareConnectedMessage(type));
    	}
	}
	
	public void onTransportDisconnected(TransportType type){
		super.onTransportDisconnected(type);
		if(altTransportService!=null){  //If we still have an alt transport open, then we don't need to tell the clients to close
			return;
		}
		Log.e(TAG, "Notifying client service of hardware disconnect.");
		connectedTransportType = null;
		isTransportConnected = false;
		stopClientPings();
		
		exitForeground();//Leave our foreground state as we don't have a connection anymore
		
		if(packetWriteTaskMaster!=null){
			packetWriteTaskMaster.close();
			packetWriteTaskMaster = null;
		}


		if (!type.equals(TransportType.MULTIPLEX)) {
			// ignore this event.
			return;
		}
		if(registeredApps== null || registeredApps.isEmpty()){
			Intent unregisterIntent = new Intent();
			unregisterIntent.putExtra(TransportConstants.HARDWARE_DISCONNECTED, type.name());
			unregisterIntent.putExtra(TransportConstants.ENABLE_LEGACY_MODE_EXTRA, legacyModeEnabled);
			unregisterIntent.setAction(TransportConstants.START_ROUTER_SERVICE_ACTION);
			sendBroadcast(unregisterIntent);
			//return;
		}else{
			Message message = Message.obtain();
			message.what = TransportConstants.HARDWARE_CONNECTION_EVENT;
			Bundle bundle = new Bundle();
			bundle.putString(TransportConstants.HARDWARE_DISCONNECTED, type.name());
			bundle.putBoolean(TransportConstants.ENABLE_LEGACY_MODE_EXTRA, legacyModeEnabled);
			message.setData(bundle);
			notifyClients(message);
		}
		synchronized(REGISTERED_APPS_LOCK){
			if(registeredApps==null){
				return;
			}
			registeredApps.clear();
		}
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
	
	 private final Handler mHandlerBT = new Handler() {
	        @Override
	        public void handleMessage(Message msg) {
	            switch (msg.what) {
	            	case MESSAGE_DEVICE_NAME:
	            		connectedDeviceName = msg.getData().getString(MultiplexBluetoothTransport.DEVICE_NAME);
	            		break;
	            	case MESSAGE_STATE_CHANGE:
	            		switch (msg.arg1) {
	            		case MultiplexBluetoothTransport.STATE_CONNECTED:
	            			onTransportConnected(TransportType.BLUETOOTH);
	            			break;
	            		case MultiplexBluetoothTransport.STATE_CONNECTING:
	            			// Currently attempting to connect - update UI?
	            			break;
	            		case MultiplexBluetoothTransport.STATE_LISTEN:
	            			break;
	            		case MultiplexBluetoothTransport.STATE_NONE:
	            			// We've just lost the connection
	            			if(!connectAsClient ){
	            				if(!legacyModeEnabled && !closing){
	            					initBluetoothSerialService();
	            				}
	            				onTransportDisconnected(TransportType.BLUETOOTH);
	            			}
	            			break;
	            		case MultiplexBluetoothTransport.STATE_ERROR:
	            			if(mSerialService!=null){
	            				Log.d(TAG, "Bluetooth serial server error received, setting state to none, and clearing local copy");
	            				mSerialService.setStateManually(MultiplexBluetoothTransport.STATE_NONE);
	            				mSerialService = null;
	            			}
	            			break;
	            		}
	                break;
	                
	            	case MESSAGE_READ:
	                	onPacketRead((SdlPacket) msg.obj);
	        			break;
	            }
	        }
	    };

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
			}else if(sendThroughAltTransport(bundle)){
				return true;
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
			if(mSerialService !=null && mSerialService.getState()==MultiplexBluetoothTransport.STATE_CONNECTED){
				if(bytes!=null){
					mSerialService.write(bytes,offset,count);
					return true;
				}
				return false;
			}else if(sendThroughAltTransport(bytes,offset,count)){
				return true;
			}else{
				return false;
			}
		}
		
		
		/**
		 * This Method will send the packets through the alt transport that is connected
		 * @param bundle The byte array of data to be wrote out
		 * @return If it was possible to send the packet off.
		 * <p><b>NOTE: This is not guaranteed. It is a best attempt at sending the packet, it may fail.</b>
		 */
		private boolean sendThroughAltTransport(Bundle bundle){
			if(altTransportService!=null){
				Message msg = Message.obtain();
				msg.what = TransportConstants.ROUTER_SEND_PACKET;
				msg.setData(bundle);
				try {
					altTransportService.send(msg);
				} catch (RemoteException e) {
					Log.e(TAG, "Unable to send through alt transport!");
					e.printStackTrace();
				}
				return true;
			}else{
				Log.w(TAG, "Unable to send packet through alt transport, it was null");
			}
			return false;		
		}
		
		 /** This Method will send the packets through the alt transport that is connected
		 * @param bytes The byte array of data to be wrote out
		 * @return If it was possible to send the packet off.
		 * <p><b>NOTE: This is not guaranteed. It is a best attempt at sending the packet, it may fail.</b>
		 */
		private boolean sendThroughAltTransport(byte[] bytes, int offset, int count){
			if(altTransportService!=null){
				Message msg = Message.obtain();
				msg.what = TransportConstants.ROUTER_SEND_PACKET;
				Bundle bundle = new Bundle();
				bundle.putByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME,bytes); 
				bundle.putInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, offset); 
				bundle.putInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, count);  
				msg.setData(bundle);
				try {
					altTransportService.send(msg);
				} catch (RemoteException e) {
					Log.e(TAG, "Unable to send through alt transport!");
					e.printStackTrace();
				}
				return true;
			}else{
				Log.w(TAG, "Unable to send packet through alt transport, it was null");
			}
			return false;		
		}

		private synchronized void closeBluetoothSerialServer(){
			if(mSerialService != null){
				mSerialService.stop();
				mSerialService = null;
			}
		}
		
		 /**
	     * bluetoothQuerryAndConnect()
	     * This function looks through the phones currently paired bluetooth devices
	     * If one of the devices' names contain "sync", or livio it will attempt to connect the RFCOMM
	     * And start SDL
	     * @return a boolean if a connection was attempted
	     */
		public synchronized boolean bluetoothQuerryAndConnect(){
			if( BluetoothAdapter.getDefaultAdapter().isEnabled()){
				Set<BluetoothDevice> pairedBT= BluetoothAdapter.getDefaultAdapter().getBondedDevices();
	        	Log.d(TAG, "Querry Bluetooth paired devices");
	        	if (pairedBT.size() > 0) {
	            	for (BluetoothDevice device : pairedBT) {
	            		if(device.getName().toLowerCase(Locale.US).contains("sync") 
	            				|| device.getName().toLowerCase(Locale.US).contains("livio")){
	            			bluetoothConnect(device);
	            				  return true;
	                	}
	            	}
	       		}
			}
			else{
				Log.e(TAG, "There was an issue with connecting as client");
			}
			return false;
			}
		
		private synchronized boolean bluetoothConnect(BluetoothDevice device){
    		Log.d(TAG,"Connecting to device: " + device.getName().toString());
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

			Log.d(TAG, "Bluetooth SPP Connect Attempt Completed");
			return false;
		}

		
		//**************************************************************************************************************************************
		//********************************************************* PREFERENCES ****************************************************************
		//**************************************************************************************************************************************
		/**
		 * This method will set the last known bluetooth connection method that worked with this phone.
		 * This helps speed up the process of connecting
		 * @param level The level of bluetooth connecting method that last worked
		 * @param prefLocation Where the preference should be stored
		 */
		public final static void setBluetoothPrefs (int level, String prefLocation) {
			if(currentContext==null){
				return;
			}
			SharedPreferences mBluetoothPrefs = currentContext.getSharedPreferences(prefLocation, Context.MODE_PRIVATE);
	    	// Write the new prefs
	    	SharedPreferences.Editor prefAdd = mBluetoothPrefs.edit();
	    	prefAdd.putInt("level", level);
	    	prefAdd.commit();
		}
		
		public final static int getBluetoothPrefs(String prefLocation)
		{		
			if(currentContext==null){
				return 0;
			}
			SharedPreferences mBluetoothPrefs = currentContext.getSharedPreferences(prefLocation, Context.MODE_PRIVATE);
			return mBluetoothPrefs.getInt("level", 0);
		}
	
	/* ***********************************************************************************************************************************************************************
	 * *****************************************************************  CUSTOM ADDITIONS  ************************************************************************************
	 *************************************************************************************************************************************************************************/

	private LocalRouterService getLocalBluetoothServiceComapre(){
		return this.localCompareTo;
	}
	
	protected static LocalRouterService getLocalRouterService(Intent launchIntent, ComponentName name){
		if(SdlRouterService.selfRouterService == null){
			if(launchIntent == null){
				Log.w(TAG, "Supplied intent was null, local router service will not contain intent");
				//Log.e(TAG, "Unable to create local router service instance. Supplied intent was null");
				//return null;
			}
			if(name == null){
				Log.e(TAG, "Unable to create local router service object because component name was null");
				return null;
			}
			selfRouterService = new LocalRouterService(launchIntent,ROUTER_SERVICE_VERSION_NUMBER, System.currentTimeMillis(), name);
		}
		if(launchIntent!=null){
			//Assume we want to set this in our service
			//Log.d(TAG, "Setting new intent on our local router service object");
			selfRouterService.launchIntent = launchIntent;
		}
		return selfRouterService;
	}
	
	private  LocalRouterService getLocalRouterService(){
		//return getLocalRouterService(new Intent(getBaseContext(),SdlRouterService.class));
		return getLocalRouterService(null, new ComponentName(this, this.getClass()));
	}
	/**
	 * This method is used to check for the newest version of this class to make sure the latest and greatest is up and running.
	 * @param context
	 */
	private void newestServiceCheck(final Context context){
		getLocalRouterService(); //Make sure our timestamp is set
		versionCheckTimeOutHandler = new Handler(); 
		versionCheckRunable = new Runnable() {           
            public void run() {
            	Log.i(TAG, "Starting up Version Checking ");
            	
            	LocalRouterService newestServiceReceived = getLocalBluetoothServiceComapre();
            	LocalRouterService self = getLocalRouterService(); //We can send in null here, because it should have already been created
            	//Log.v(TAG, "Self service info " + self);
            	//Log.v(TAG, "Newest compare to service info " + newestServiceReceived);
            	if(newestServiceReceived!=null && self.isNewer(newestServiceReceived)){
            		if(SdlRouterService.this.mSerialService!=null && SdlRouterService.this.mSerialService.isConnected()){ //We are currently connected. Wait for next connection
            			return;
            		}
            		Log.d(TAG, "There is a newer version "+newestServiceReceived.version+" of the Router Service, starting it up");
					if(newestServiceReceived.launchIntent == null){
						if(newestServiceReceived.name!=null){
							newestServiceReceived.launchIntent = new Intent().setComponent(newestServiceReceived.name);
						}else{
							Log.w(TAG, "Service didn't include launch intent or component name");
							startUpSequence();
							return;
						}
					}
                	closing = true;
					closeBluetoothSerialServer();
					context.startService(newestServiceReceived.launchIntent);
					notifyAltTransportOfClose(TransportConstants.ROUTER_SHUTTING_DOWN_REASON_NEWER_SERVICE);
					if(getBaseContext()!=null){
						stopSelf();
					}else{
						onDestroy();
					}
            	}
            	else{			//Let's start up like normal
            		Log.d(TAG, "No newer services than " + ROUTER_SERVICE_VERSION_NUMBER +" found. Starting up bluetooth transport");
                	startUpSequence();
            	}
            }
        };
        versionCheckTimeOutHandler.postDelayed(versionCheckRunable, VERSION_TIMEOUT_RUNNABLE); 
	}
	
	/**
	 * This method is used to check for the newest version of this class to make sure the latest and greatest is up and running.
	 * @param
	 */
	private void startAltTransportTimer(){
		altTransportTimerHandler = new Handler(); 
		altTransportTimerRunnable = new Runnable() {           
            public void run() {
            	altTransportTimerHandler = null;
            	altTransportTimerRunnable = null;
            	shouldServiceRemainOpen(null);
            }
        };
        altTransportTimerHandler.postDelayed(altTransportTimerRunnable, ALT_TRANSPORT_TIMEOUT_RUNNABLE); 
	}

	/* ****************************************************************************************************************************************
	// ***********************************************************   LEGACY   ****************************************************************
	//*****************************************************************************************************************************************/
	private boolean legacyModeEnabled = false;
	
	private void enableLegacyMode(boolean enable){
		Log.d(TAG, "Enable legacy mode: " + enable);
		legacyModeEnabled = enable; //We put this at the end to avoid a race condition between the bluetooth d/c and notify of legacy mode enabled

		if(legacyModeEnabled){
			//So we need to let the clients know they need to host their own bluetooth sessions because the currently connected head unit only supports a very old version of SDL/Applink
			//Start by closing our own bluetooth connection. The following calls will handle actually notifying the clients of legacy mode
			closeBluetoothSerialServer();			
			//Now wait until we get a d/c, then the apps should shut their bluetooth down and go back to normal
			
		}//else{}

	}
	
	/* ****************************************************************************************************************************************
	// ***********************************************************   UTILITY   ****************************************************************
	//*****************************************************************************************************************************************/
	
	@SuppressWarnings("unused")
	private void debugPacket(byte[] bytes){
		//DEBUG
		
		if(bytes[0] != 0x00){
			Log.d(TAG, "Writing packet with header: " + BitConverter.bytesToHex(bytes, 12)); //just want the header
		}else{
			
			//Log.d(TAG, "Writing packet with binary header: " + BitConverter.bytesToHex(bytes, 12)); //just want the header
			//int length = bytes.length-12;
			if(bytes.length<=8){
				Log.w(TAG, "No payload to debug or too small");
				return;
			}
			//Check first byte if 0, make to json
			char[] buffer = new char[bytes.length];
			for(int i = 12;i<bytes.length;i++){
				 buffer[i-12] = (char)(bytes[i] & 0xFF);
			}
			try {

				JSONObject object = new JSONObject(new String(buffer));
				Log.d(TAG, "JSON: " + object.toString());
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
	}
	

	/* ****************************************************************************************************************************************
	// **********************************************************   TINY CLASSES   ************************************************************
	//*****************************************************************************************************************************************/

	/**
	 *This class enables us to compare two router services
	 * from different apps and determine which is the newest
	 * and therefore which one should be the one spun up.
	 * @author Joey Grover
	 *
	 */
	static class LocalRouterService implements Parcelable{
		Intent launchIntent = null;
		int version = 0;
		long timestamp;
		ComponentName name;
		
		private LocalRouterService(Intent intent, int version, long timeStamp,ComponentName name ){
			this.launchIntent = intent;
			this.version = version;
			this.timestamp = timeStamp;
			this.name = name;
		}
		/**
		 * Check if input is newer than this version
		 * @param service
		 * @return
		 */
		public boolean isNewer(LocalRouterService service){
			if(service.version>this.version){
				return true;
			}else if(service.version == this.version){ //If we have the same version, we will use a timestamp
				return service.timestamp<this.timestamp;
			}
			return false;
		}
		
		
		public boolean isEqual(LocalRouterService service) {
			if(service != null && service.name!= null 
					&& this.name !=null){
				return (this.name.equals(service.name));
			}
			return false;
		}
		@Override
		public String toString() {
			StringBuilder build = new StringBuilder();
			build.append("Intent action: ");
			if(launchIntent!=null){
				build.append(launchIntent.getComponent().getClassName());
			}else if(name!=null){
				build.append(name.getClassName());
			}
			
			build.append(" Version: ");
			build.append(version);
			build.append(" Timestamp: ");
			build.append(timestamp);
			
			return build.toString();
		}
		public LocalRouterService(Parcel p) {
			this.version = p.readInt();
			this.timestamp = p.readLong();
			try {
				this.launchIntent = p.readParcelable(Intent.class.getClassLoader());
				this.name = p.readParcelable(ComponentName.class.getClassLoader());
			}catch (Exception e){
				// catch DexException
			}
		}
		
		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(version);
			dest.writeLong(timestamp);
			dest.writeParcelable(launchIntent, 0);
			dest.writeParcelable(name, 0);

		}
		
		public static final Parcelable.Creator<LocalRouterService> CREATOR = new Parcelable.Creator<LocalRouterService>() {
	        public LocalRouterService createFromParcel(Parcel in) {
	     	   return new LocalRouterService(in); 
	        }

			@Override
			public LocalRouterService[] newArray(int size) {
				return new LocalRouterService[size];
			}

	    };
		
	}

	/**
	 * RegisteredApp, PacketWriteTask, PacketWriteTaskMaster, PacketWriteTaskBlockingQueue
	 * are moved to SdlRouterBase.
	 */
}

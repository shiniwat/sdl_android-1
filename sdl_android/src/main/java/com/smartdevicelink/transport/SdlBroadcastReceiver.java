package com.smartdevicelink.transport;

import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.util.AndroidTools;
import com.smartdevicelink.transport.RouterServiceValidator.TrustedListCallback;
import com.smartdevicelink.util.DebugTool;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import com.smartdevicelink.util.ServiceFinder;

import java.util.Collections;
import java.util.Comparator;

public abstract class SdlBroadcastReceiver extends BroadcastReceiver{
	
	private static final String TAG = "Sdl Broadcast Receiver";

	private static final String BOOT_COMPLETE = "android.intent.action.BOOT_COMPLETED";
	private static final String ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED";
	private static final String STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED" ;
	
	public static final String SDL_ROUTER_SERVICE_CLASS_NAME 				= "sdlrouterservice";
	public static final String SDL_AOA_ROUTER_SERVICE_CLASS_NAME 			= "sdlaoarouterservice";

	public static final String LOCAL_ROUTER_SERVICE_EXTRA					= "router_service";
	public static final String LOCAL_ROUTER_SERVICE_DID_START_OWN			= "did_start";
	
	public static final String TRANSPORT_GLOBAL_PREFS 						= "SdlTransportPrefs"; 
	public static final String IS_TRANSPORT_CONNECTED						= "isTransportConnected";

	public static Vector<ComponentName> runningRouterServicePackage = null;

	@SuppressWarnings("rawtypes")
	private static Class localRouterClass;
	private static Class localAoaRouterClass;

    private static final Object QUEUED_SERVICE_LOCK = new Object();
    private static ComponentName queuedService = null;
	
	public int getRouterServiceVersion(){
		return SdlRouterService.ROUTER_SERVICE_VERSION_NUMBER;	
	}
	
	@Override
	public void onReceive(Context context, Intent intent) {
		//Log.i(TAG, "Sdl Receiver Activated");
		String action = intent.getAction();
		
		if(action.equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)
				|| action.equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)){
			//The package manager has sent out a new broadcast. 
			RouterServiceValidator.invalidateList(context);
			return;
		}
		
        if(!(action.equalsIgnoreCase(BOOT_COMPLETE)
        		|| action.equalsIgnoreCase(ACL_CONNECTED)
        		|| action.equalsIgnoreCase(STATE_CHANGED)
        		|| action.equalsIgnoreCase(USBTransport.ACTION_USB_ACCESSORY_ATTACHED)
        		|| action.equalsIgnoreCase(TransportConstants.START_ROUTER_SERVICE_ACTION)
				|| action.equalsIgnoreCase(SdlRouterBase.REGISTER_NEWER_SERVER_INSTANCE_ACTION))){
        	//We don't want anything else here if the child class called super and has different intent filters
        	//Log.i(TAG, "Unwanted intent from child class");
        	return;
        }
        
        if(action.equalsIgnoreCase(USBTransport.ACTION_USB_ACCESSORY_ATTACHED)){
        	Log.d(TAG, "Usb connected");
			//intent.setAction(null);
			onSdlEnabled(context, intent);
			return;
        }
        
		boolean didStart = false;
		if (localRouterClass == null){
			localRouterClass = defineLocalSdlRouterClass();
		}
		if (localAoaRouterClass == null) {
			localAoaRouterClass = defineLocalSdlAoaRouterClass();
		}

		//This will only be true if we are being told to reopen our SDL service because SDL is enabled
		if(action.equalsIgnoreCase(TransportConstants.START_ROUTER_SERVICE_ACTION)){
			final int typeValue = intent.getIntExtra(TransportConstants.ROUTER_TRANSPORT_TYPE, TransportType.MULTIPLEX.ordinal());
			if(intent.hasExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_EXTRA)){
				DebugTool.logInfo("got START_ROUTER_SERVICE_ACTION + START_ROUTER_SERVICE_SDL_ENABLED_EXTRA");
				if(intent.getBooleanExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_EXTRA, false)){
					String packageName = intent.getStringExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_APP_PACKAGE);
					final ComponentName componentName = intent.getParcelableExtra(TransportConstants.START_ROUTER_SERVICE_SDL_ENABLED_CMP_NAME);
					if(componentName!=null){
						// HACK
						if (componentName.toString().contains("SdlAoaRouterService")) {
							return; // HACK
						}
						DebugTool.logInfo("componentName=" + componentName.toString());
						final Intent finalIntent = intent;
						final Context finalContext = context;
						RouterServiceValidator.createTrustedListRequest(context, false, new TrustedListCallback(){
							@Override
							public void onListObtained(boolean successful) {
								//Log.v(TAG, "SDL enabled by router service from " + packageName + " compnent package " + componentName.getPackageName()  + " - " + componentName.getClassName());
								//List obtained. Let's start our service
								queuedService = componentName;
								finalIntent.setAction("com.sdl.noaction"); //Replace what's there so we do go into some unintended loop
								//Validate the router service so the service knows if this is a trusted router service
								RouterServiceValidator vlad = new RouterServiceValidator(finalContext,componentName);
								finalIntent.putExtra(TransportConstants.ROUTER_SERVICE_VALIDATED, vlad.validate(TransportType.values()[typeValue]));
								DebugTool.logInfo("about onSDLEnabled");
								onSdlEnabled(finalContext, finalIntent);
							}

						});
					}

				}else{
					//This was previously not hooked up, so let's leave it commented out
					//onSdlDisabled(context);
				}
				return;
			}else if(intent.getBooleanExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, false)){
				//We were told to wake up our router services
				boolean altServiceWake = intent.getBooleanExtra(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT, false);
				didStart = wakeUpRouterService(context, false,altServiceWake, TransportType.values()[typeValue]);
			}
		}
		
	    if (intent.getAction().contains("android.bluetooth.adapter.action.STATE_CHANGED")){
	    	int state = intent.getIntExtra("android.bluetooth.adapter.extra.STATE",-1);
	    		if (state == BluetoothAdapter.STATE_OFF || 
	    			state == BluetoothAdapter.STATE_TURNING_OFF){
	    			//onProtocolDisabled(context);
	    			//Let's let the service that is running manage what to do for this
	    			//If we were to do it here, for every instance of this BR it would send
	    			//an intent to stop service, where it's only one that is needed.
	    			return;
	    		}
	    }
	    Log.d(TAG, "Check for local router");
	    if(localRouterClass!=null){ //If there is a supplied router service lets run some logic regarding starting one
	    	
	    	if(!didStart){Log.d(TAG, "attempting to wake up router service");
				didStart = wakeUpRouterService(context, true,false, TransportType.MULTIPLEX);
	    	}

	    	//So even though we started our own version, on some older phones we find that two services are started up so we want to make sure we send our version that we are working with
	    	//We will send it an intent with the version number of the local instance and an intent to start this instance
	    	
	    	Intent serviceIntent =  new Intent(context, localRouterClass);
	    	SdlRouterService.LocalRouterService self = SdlRouterService.getLocalRouterService(serviceIntent, serviceIntent.getComponent());
	    	Intent restart = new Intent(SdlRouterService.REGISTER_NEWER_SERVER_INSTANCE_ACTION);
	    	restart.putExtra(LOCAL_ROUTER_SERVICE_EXTRA, self);
	    	restart.putExtra(LOCAL_ROUTER_SERVICE_DID_START_OWN, didStart);
	    	context.sendBroadcast(restart);
	    }
	}

	/**
	 * @review: isForegroundApp requires usage stats permission, which needs review.
	 * The reason why we need foreground check is when app gets USB_ATTACHED broadcast, it's natural that foreground app launches router service.
	 * For now, we suppress this check to reduce the permission requirement.
	 * @param context
	 * @return
	 */
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	static boolean isForegroundApp(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			if (!hasUsageStatsPermission(context)) {
				DebugTool.logWarning("hasUsageStatsPermission false");
				return true; // cannot check.
			}
			UsageStatsManager manager = (UsageStatsManager)context.getSystemService("usagestats");
			long time = System.currentTimeMillis();
			List<UsageStats> appList = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 1000, time);
			if (appList != null && appList.size() > 0) {
				SortedMap<Long, UsageStats> myMap = new TreeMap<Long, UsageStats>();
				for (UsageStats stats: appList) {
					myMap.put(stats.getLastTimeUsed(), stats);
				}
				DebugTool.logInfo(String.format("myMap count=%d", myMap.size()));
				if (myMap != null && !myMap.isEmpty()) {
					String current = myMap.get(myMap.lastKey()).getPackageName();
					DebugTool.logInfo("isForegroundApp top=" + current);
					return current.equalsIgnoreCase(context.getApplicationContext().getPackageName());
				}
			} else {
				DebugTool.logInfo("appList is empty");
			}
		}
		DebugTool.logInfo("isForegroundApp top unknown");
		return true;
	}

	static boolean hasUsageStatsPermission(Context context) {
		AppOpsManager appOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
		int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), context.getPackageName());
		return mode == AppOpsManager.MODE_ALLOWED;
	}

	public static void checkHasUsageStatsPermission(Context context) {
		if (!hasUsageStatsPermission(context)) {
			context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
		}
	}

	@TargetApi(Build.VERSION_CODES.O)
	private boolean wakeUpRouterService(final Context context, boolean ping, final boolean altTransportWake, final TransportType transportType){
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			if(!isRouterServiceRunning(context, ping, transportType)){
				DebugTool.logInfo("wakeUpRouterService but router service is not running. transportType=" + transportType.toString());
				//If there isn't a service running we should try to start one
				//The under class should have implemented this....

				//So let's start up our service since no copy is running
				if (transportType == TransportType.MULTIPLEX && localRouterClass != null) {
					DebugTool.logInfo("Router service is not running: BT Router is" + localRouterClass.toString());
					Intent serviceIntent = new Intent(context, localRouterClass);
					if(altTransportWake){
						serviceIntent.setAction(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT);
					}
					try {
						context.startService(serviceIntent);
					}catch (SecurityException e){
						Log.e(TAG, "Security exception, process is bad");
						return false; // Let's exit, we can't start the service
					}
				}

				if (transportType == TransportType.MULTIPLEX_AOA && localAoaRouterClass != null) {
					DebugTool.logInfo("Router service is not running: AoaRouter is " + localAoaRouterClass.toString());
					//if (isForegroundApp(context)) {
						Intent aoaRouterIntent = new Intent(context, localAoaRouterClass);
						ComponentName name = context.startService(aoaRouterIntent);
						DebugTool.logInfo("startService " + name);
					//} else {
					//	DebugTool.logInfo("do not start service because we are not foreground");
					//}
				}
				return true;
			}else {
				DebugTool.logInfo("wakeUpRouterService but router service runs already");
				if (altTransportWake && runningRouterServicePackage != null && runningRouterServicePackage.size() > 0) {
					Intent serviceIntent = new Intent();
					serviceIntent.setAction(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT);
					context.startService(serviceIntent);
					for (ComponentName compName : runningRouterServicePackage) {
						serviceIntent.setComponent(compName);
						context.startService(serviceIntent);
					}
					return true;
				}
				return false;
			}
		} else { //We are android Oreo or newer
			ServiceFinder finder = new ServiceFinder(context, context.getPackageName(), new ServiceFinder.ServiceFinderCallback() {
				@Override
				public void onComplete(Vector<ComponentName> routerServices) {
					runningRouterServicePackage = new Vector<ComponentName>();
					runningRouterServicePackage.addAll(routerServices);
					if (runningRouterServicePackage.isEmpty()) {
						//If there isn't a service running we should try to start one
						//We will try to sort the SDL enabled apps and find the one that's been installed the longest
						Intent serviceIntent;
						final PackageManager packageManager = context.getPackageManager();
						Vector<ResolveInfo> apps = new Vector(AndroidTools.getSdlEnabledApps(context, "").values()); //we want our package
						if (apps != null && !apps.isEmpty()) {
							Collections.sort(apps, new Comparator<ResolveInfo>() {
								@Override
								public int compare(ResolveInfo resolveInfo, ResolveInfo t1) {
									try {
										PackageInfo thisPack = packageManager.getPackageInfo(resolveInfo.activityInfo.packageName, 0);
										PackageInfo itPack = packageManager.getPackageInfo(t1.activityInfo.packageName, 0);
										if (thisPack.lastUpdateTime < itPack.lastUpdateTime) {
											return -1;
										} else if (thisPack.lastUpdateTime > itPack.lastUpdateTime) {
											return 1;
										}

									} catch (PackageManager.NameNotFoundException e) {
										e.printStackTrace();
									}
									return 0;
								}
							});
							String packageName = apps.get(0).activityInfo.packageName;
							serviceIntent = new Intent();
							if (transportType.equals(TransportType.MULTIPLEX)) {
								serviceIntent.setComponent(new ComponentName(packageName, packageName + ".SdlRouterService"));
							} else if (transportType.equals(TransportType.MULTIPLEX_AOA)) {
								serviceIntent.setComponent(new ComponentName(packageName, packageName + ".SdlAoaRouterService"));
							}
						} else{
							Log.d(TAG, "No router service running, starting ours");
							//So let's start up our service since no copy is running
							serviceIntent = new Intent(context, (transportType.equals(TransportType.MULTIPLEX_AOA)) ? localAoaRouterClass : localRouterClass);

						}
						if (altTransportWake) {
							serviceIntent.setAction(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT);
						}
						try {
							serviceIntent.putExtra(TransportConstants.FOREGROUND_EXTRA, true);
							context.startForegroundService(serviceIntent);

						} catch (SecurityException e) {
							Log.e(TAG, "Security exception, process is bad");
						}
					} else {
						if (altTransportWake && runningRouterServicePackage != null && runningRouterServicePackage.size() > 0) {
							wakeRouterServiceAltTransport(context);
							return;
						}
						return;
					}
				}
			});
			return true;
		}
	}

	private void wakeRouterServiceAltTransport(Context context){
		Intent serviceIntent = new Intent();
		serviceIntent.setAction(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT);
		for (ComponentName compName : runningRouterServicePackage) {
			serviceIntent.setComponent(compName);
			context.startService(serviceIntent);
		}
	}

	/**
	 * Determines if an instance of the Router Service is currently running on the device.<p>
	 * <b>Note:</b> This method no longer works on Android Oreo or newer
	 * @param context A context to access Android system services through.
	 * @param pingService Set this to true if you want to make sure the service is up and listening to bluetooth
	 * @return True if a SDL Router Service is currently running, false otherwise.
	 */
	private static boolean isRouterServiceRunning(Context context, boolean pingService, TransportType transportType){
		if(context == null){
			Log.e(TAG, "Can't look for router service, context supplied was null");
			return false;
		}
		if(runningRouterServicePackage ==null){
			runningRouterServicePackage = new Vector<ComponentName>();
		}else{
			runningRouterServicePackage.clear();
		}
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		manager.getRunningAppProcesses();
		List<RunningServiceInfo> runningServices = null;
		try {
			runningServices = manager.getRunningServices(Integer.MAX_VALUE);
		} catch (NullPointerException e) {
			Log.e(TAG, "Can't get list of running services");
			return false;
		}
		for (RunningServiceInfo service : runningServices) {
			//We will check to see if it contains this name, should be pretty specific
			//Log.d(TAG, "Found Service: "+ service.service.getClassName());
			//Log.d(TAG, "package=" + service.service.getPackageName() + " process=" + service.process);
			if ((service.service.getClassName()).toLowerCase(Locale.US).contains(SDL_ROUTER_SERVICE_CLASS_NAME) && AndroidTools.isServiceExported(context, service.service) && transportType == TransportType.MULTIPLEX) {

				runningRouterServicePackage.add(service.service);	//Store which instance is running
				DebugTool.logInfo("runningRouterService (router): " + service.service.getPackageName());
				if(pingService){
					pingRouterService(context, service.service.getPackageName(), service.service.getClassName());
				}
			} else if (service.service.getClassName().toLowerCase().contains(SDL_AOA_ROUTER_SERVICE_CLASS_NAME) && transportType == TransportType.MULTIPLEX_AOA) {
				DebugTool.logInfo("runningRouterService (aoarouter): " + service.service.getPackageName());
				runningRouterServicePackage.add(service.service);	//Store which instance is running
				if(pingService){
					pingRouterService(context, service.service.getPackageName(), service.service.getClassName());
				}
			}
		}

		return runningRouterServicePackage.size() > 0;
	}

	/**
	 * Attempts to ping a running router service
	 * @param context A context to access Android system services through.
	 * @param packageName Package name for service to ping
	 * @param className Class name for service to ping
	 */
	protected static void pingRouterService(Context context, String packageName, String className){
		if(context == null || packageName == null || className == null){
			return;
		}
		try{
			Intent intent = new Intent();
			intent.setClassName(packageName, className);
			intent.putExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, true);
			context.startService(intent);
		}catch(SecurityException e){
			Log.e(TAG, "Security exception, process is bad");
			// This service could not be started
		}
	}

	/**
	 * This call will reach out to all SDL related router services to check if they're connected. If a the router service is connected, it will react by pinging all clients. This receiver will then
	 * receive that ping and if the router service is trusted, the onSdlEnabled method will be called. 
	 * @param context
	 */
	public static void queryForConnectedService(final Context context, final TransportType transportType){
		//Leverage existing call. Include ping bit
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
			ServiceFinder finder = new ServiceFinder(context, context.getPackageName(), new ServiceFinder.ServiceFinderCallback() {
				@Override
				public void onComplete(Vector<ComponentName> routerServices) {
					runningRouterServicePackage = new Vector<ComponentName>();
					runningRouterServicePackage.addAll(routerServices);
					requestTransportStatus(context, null, true, false, transportType);
				}
			});

		}else{
			requestTransportStatus(context, null, true, true, transportType);
		}
	}
	/**
	 * If a Router Service is running, this method determines if that service is connected to a device over some form of transport.
	 * @param context A context to access Android system services through. If null is passed, this will always return false
	 * @param callback Use this callback to find out if the router service is connected or not. 
	 */
	public static void requestTransportStatus(Context context, final SdlRouterStatusProvider.ConnectedStatusCallback callback, final TransportType transportType){
		requestTransportStatus(context,callback,false, true, transportType);
	}

	@Deprecated
	private static void requestTransportStatus(Context context, final SdlRouterStatusProvider.ConnectedStatusCallback callback, final boolean triggerRouterServicePing, final boolean lookForServices, final TransportType transportType){
		if(context == null){
			if(callback!=null){
				callback.onConnectionStatusUpdate(false, null,context);
			}
			return;
		}
		if(transportType.equals(TransportType.MULTIPLEX) && isRouterServiceRunning(context,false,transportType) && !runningRouterServicePackage.isEmpty()){	//So there is a service up, let's see if it's connected
			final ConcurrentLinkedQueue<ComponentName> list = new ConcurrentLinkedQueue<ComponentName>(runningRouterServicePackage);
			final SdlRouterStatusProvider.ConnectedStatusCallback sdlBrCallback = new SdlRouterStatusProvider.ConnectedStatusCallback() {

				@Override
				public void onConnectionStatusUpdate(boolean connected, ComponentName service,Context context) {
					if(!connected && !list.isEmpty()){
						SdlRouterStatusProvider provider = new SdlRouterStatusProvider(context,list.poll(), this);
						if(triggerRouterServicePing){provider.setFlags(TransportConstants.ROUTER_STATUS_FLAG_TRIGGER_PING);	}
						provider.checkIsConnected();
					}else{
						if(service!=null){
							DebugTool.logInfo(service.getPackageName() + " is connected = " + connected);
						}else{
							DebugTool.logInfo("No service is connected/running");
						}
						if(callback!=null){
							callback.onConnectionStatusUpdate(connected, service,context);
						}
						list.clear();
					}

				}
			};
			final SdlRouterStatusProvider provider = new SdlRouterStatusProvider(context,list.poll(),sdlBrCallback);
			if(triggerRouterServicePing){
				provider.setFlags(TransportConstants.ROUTER_STATUS_FLAG_TRIGGER_PING);
			}
			//Lets ensure we have a current list of trusted router services
			RouterServiceValidator.createTrustedListRequest(context, false, new TrustedListCallback(){
				@Override
				public void onListObtained(boolean successful) {
					//This will kick off our check of router services
					provider.checkIsConnected();
				}
			});

		}else{
			DebugTool.logInfo("Router service isn't running, returning false. for transportType=" + transportType.toString());
			if(transportType == TransportType.MULTIPLEX && BluetoothAdapter.getDefaultAdapter()!=null && BluetoothAdapter.getDefaultAdapter().isEnabled()){
				Intent serviceIntent = new Intent();
				serviceIntent.setAction(TransportConstants.START_ROUTER_SERVICE_ACTION);
				serviceIntent.putExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, true);
				serviceIntent.putExtra(TransportConstants.ROUTER_TRANSPORT_TYPE, TransportType.MULTIPLEX.ordinal());
				context.sendBroadcast(serviceIntent);
				DebugTool.logInfo("sent Broadcast: START_ROUTER_SERVICE_ACTION for BT");
			} else if (transportType == TransportType.MULTIPLEX_AOA && SdlAoaRouterService.shouldServiceRemainOpen(context)){
				Intent routerIntent = new Intent();
				routerIntent.setAction(TransportConstants.START_ROUTER_SERVICE_ACTION);
				routerIntent.putExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, true);
				routerIntent.putExtra(TransportConstants.ROUTER_TRANSPORT_TYPE, TransportType.MULTIPLEX_AOA.ordinal());
				context.sendBroadcast(routerIntent);
				DebugTool.logInfo("sent Broadcast: START_ROUTER_SERVICE_ACTION for AOA");
			} else {
				DebugTool.logInfo("Cannot send broadcast for creating service.");
			}
			if(callback!=null){
				callback.onConnectionStatusUpdate(false, null,context);
			}
		}
	}
	

	
	public static ComponentName consumeQueuedRouterService(){
		synchronized(QUEUED_SERVICE_LOCK){
			ComponentName retVal = queuedService;
			queuedService = null;
			return retVal;
		}
	}
	
	/**
	 * We need to define this for local copy of the Sdl Router Service class.
	 * It will be the main point of connection for Sdl enabled apps
	 * @return Return the local copy of SdlRouterService.class
	 * {@inheritDoc}
	 */
	public abstract Class<? extends SdlRouterService> defineLocalSdlRouterClass();

	public abstract Class<? extends SdlRouterBase> defineLocalSdlAoaRouterClass();
	
	/**
	 * 
	 * The developer will need to define exactly what should happen when Sdl is enabled.
	 * This method will only get called when a Sdl  session is initiated.
	 * <p> The most useful code here would be to start the activity or service that handles most of the Livio 
	 * Connect code.
	 * @param context this is the context that was passed to this receiver when it was called.
	 * @param intent this is the intent that alerted this broadcast. Make sure to pass all extra it came with to your service/activity
	 * {@inheritDoc}
	 */
	public abstract void onSdlEnabled(Context context, Intent intent);
	
	//public abstract void onSdlDisabled(Context context); //Removing for now until we're able to abstract from developer


}

package com.smartdevicelink.test.proxy;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MotionEvent;

import com.smartdevicelink.AndroidTestCase2;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.exception.SdlExceptionCause;
import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.SdlProxyBase;
import com.smartdevicelink.proxy.SdlProxyBuilder;
import com.smartdevicelink.proxy.SdlProxyConfigurationResources;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.Show;
import com.smartdevicelink.proxy.rpc.ShowResponse;
import com.smartdevicelink.proxy.rpc.Speak;
import com.smartdevicelink.proxy.rpc.SpeakResponse;
import com.smartdevicelink.proxy.rpc.enums.Result;
import com.smartdevicelink.proxy.rpc.listeners.OnMultipleRequestListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.streaming.video.SdlRemoteDisplay;
import com.smartdevicelink.streaming.video.VideoStreamingParameters;
import com.smartdevicelink.test.streaming.video.SdlRemoteDisplayTest;
import com.smartdevicelink.proxy.interfaces.ISdl;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.interfaces.OnSystemCapabilityListener;
import com.smartdevicelink.proxy.rpc.SdlMsgVersion;
import com.smartdevicelink.streaming.audio.AudioStreamingCodec;
import com.smartdevicelink.streaming.audio.AudioStreamingParams;
import com.smartdevicelink.proxy.interfaces.IVideoStreamListener;
import com.smartdevicelink.proxy.interfaces.IAudioStreamListener;
import com.smartdevicelink.test.Test;
import com.smartdevicelink.proxy.rpc.TouchEvent;
import com.smartdevicelink.proxy.rpc.enums.TouchType;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.proxy.interfaces.ISdlServiceListener;

import junit.framework.Assert;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Constructor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;


public class SdlProxyBaseTests extends AndroidTestCase2 {
    public static final String TAG = "SdlProxyBaseTests";

    int onUpdateListenerCounter, onFinishedListenerCounter, onResponseListenerCounter, onErrorListenerCounter, remainingRequestsExpected;

    @Override
    protected void setUp() throws Exception{
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        //Nothing here for now
    }

    /**
     * Test SdlProxyBase for handling null SdlProxyConfigurationResources
     */
    public void testNullSdlProxyConfigurationResources() {
        SdlProxyALM proxy = null;
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(mock(IProxyListenerALM.class), "appId", "appName", true, getContext());
        SdlProxyConfigurationResources config = new SdlProxyConfigurationResources("path", (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE));
        //Construct with a non-null SdlProxyConfigurationResources
        builder.setSdlProxyConfigurationResources(config);
        try {
            proxy = builder.build();
        } catch (Exception e) {
            Log.v(TAG, "Exception in testNullSdlProxyConfigurationResources, testing non null SdlProxyConfigurationResources");
            if (!(e instanceof SdlException) || !((SdlException) e).getSdlExceptionCause().equals(SdlExceptionCause.BLUETOOTH_ADAPTER_NULL)) {
                e.printStackTrace();
                Assert.fail("Exception in testNullSdlProxyConfigurationResources - \n" + e.toString());
            }
        }

        if (proxy != null) {
            try {
                proxy.dispose();
                proxy = null;
            }catch(SdlException e){
                e.printStackTrace();
            }
        }

        //Construct with a null SdlProxyConfigurationResources
        builder.setSdlProxyConfigurationResources(null);
        try {
            proxy = builder.build();
        } catch (Exception e) {
            Log.v(TAG, "Exception in testNullSdlProxyConfigurationResources, testing null SdlProxyConfigurationResources");
            if (!(e instanceof SdlException) || !((SdlException) e).getSdlExceptionCause().equals(SdlExceptionCause.BLUETOOTH_ADAPTER_NULL)) {
                e.printStackTrace();
                Assert.fail("Exception in testNullSdlProxyConfigurationResources, testing null SdlProxyConfigurationResources");
            }
        }
        if (proxy != null) {
            try {
                proxy.dispose();
                proxy = null;
            }catch(SdlException e){
                e.printStackTrace();
            }
        }

        //Construct with a non-null SdlProxyConfigurationResources and a null TelephonyManager
        config.setTelephonyManager(null);
        builder.setSdlProxyConfigurationResources(config);
        try {
            proxy = builder.build();
        } catch (Exception e) {
            Log.v(TAG, "Exception in testNullSdlProxyConfigurationResources, testing null TelephonyManager");
            if (!(e instanceof SdlException) || !((SdlException) e).getSdlExceptionCause().equals(SdlExceptionCause.BLUETOOTH_ADAPTER_NULL)) {
                Assert.fail("Exception in testNullSdlProxyConfigurationResources, testing null TelephonyManager");
            }
        }
        if (proxy != null) {
            try {
                proxy.dispose();
                proxy = null;
            }catch(SdlException e){
                e.printStackTrace();
            }
        }
    }

    public void testRemoteDisplayStreaming(){
        SdlProxyALM proxy = null;
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(mock(IProxyListenerALM.class), "appId", "appName", true, getContext());
        try{
            proxy = builder.build();
            //	public void startRemoteDisplayStream(Context context, final Class<? extends SdlRemoteDisplay> remoteDisplay, final VideoStreamingParameters parameters, final boolean encrypted){
            Method m = SdlProxyALM.class.getDeclaredMethod("startRemoteDisplayStream", Context.class, SdlRemoteDisplay.class, VideoStreamingParameters.class, boolean.class);
            assertNotNull(m);
            m.setAccessible(true);
            m.invoke(proxy,getContext(), SdlRemoteDisplayTest.MockRemoteDisplay.class, (VideoStreamingParameters)null, false);
            assert true;

        }catch (Exception e){
            assert false;
        }
    }

    /*---
    public void testMultipleRPCSendSynchronous() {

		List<RPCRequest> rpcs = new ArrayList<>();

		// rpc 1
		Show show = new Show();
		show.setMainField1("hey y'all");
		show.setMainField2("");
		show.setMainField3("");
		show.setMainField4("");
		rpcs.add(show);

		// rpc 2
		Show show2 = new Show();
		show2.setMainField1("");
		show2.setMainField2("It is Wednesday My Dudes");
		show2.setMainField3("");
		show2.setMainField4("");
		rpcs.add(show2);

		OnMultipleRequestListener mrl = new OnMultipleRequestListener() {
			@Override
			public void onUpdate(int remainingRequests) {

			}

			@Override
			public void onFinished() {

			}

			@Override
			public void onError(int correlationId, Result resultCode, String info) {
				assert false;
			}

			@Override
			public void onResponse(int correlationId, RPCResponse response) {

			}
		};
		try{
			// public void sendRequests(List<RPCRequest> rpcs, final OnMultipleRequestListener listener) throws SdlException {
			Method m = SdlProxyBase.class.getDeclaredMethod("sendRequests", SdlProxyBase.class);
			assertNotNull(m);
			m.setAccessible(true);
			m.invoke(rpcs,mrl);
			assert true;

		}catch (Exception e){
			assert false;
		}
	}

	public void testMultipleRPCSendAsynchronous() {

		List<RPCRequest> rpcs = new ArrayList<>();

		// rpc 1
		Show show = new Show();
		show.setMainField1("hey y'all");
		show.setMainField2("");
		show.setMainField3("");
		show.setMainField4("");
		rpcs.add(show);

		// rpc 2
		Show show2 = new Show();
		show2.setMainField1("");
		show2.setMainField2("It is Wednesday My Dudes");
		show2.setMainField3("");
		show2.setMainField4("");
		rpcs.add(show2);

		OnMultipleRequestListener mrl = new OnMultipleRequestListener() {
			@Override
			public void onUpdate(int remainingRequests) {

			}

			@Override
			public void onFinished() {

			}

			@Override
			public void onError(int correlationId, Result resultCode, String info) {
				assert false;
			}

			@Override
			public void onResponse(int correlationId, RPCResponse response) {

			}
		};
		try{
			// public void sendSequentialRequests(List<RPCRequest> rpcs, final OnMultipleRequestListener listener) throws SdlException {
			Method m = SdlProxyBase.class.getDeclaredMethod("sendSequentialRequests", SdlProxyBase.class);
			assertNotNull(m);
			m.setAccessible(true);
			m.invoke(rpcs,mrl);
			assert true;

		}catch (Exception e){
			assert false;
		}
	}

    public void testMultiTouchUpDown() {
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(new ProxyListenerTest(), "appId", "appName", true, getContext());
        try{
            ClassLoader loader = this.getContext().getClassLoader();
            Class videoStreamingManagerClass = loader.loadClass("com.smartdevicelink.proxy.SdlProxyBase$VideoStreamingManager");

            Class[] parameterTypes = new Class[]{SdlProxyBase.class, Context.class, ISdl.class};
            Constructor constructor = videoStreamingManagerClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);

            // Get VideoStreamingManager Instance
            Object target = constructor.newInstance((SdlProxyBase)builder.build(), null, _internalInterface);

            // Enable call convertTouchEvent
            Method method = videoStreamingManagerClass.getDeclaredMethod("convertTouchEvent", OnTouchEvent.class);
            method.setAccessible(true);

            // Initialize touch event (Touch ID:100)
            TouchEvent touchEvent = Test.GENERAL_TOUCHEVENT;

            // Initialize touch event (Touch ID:101)
            TouchEvent touchEvent2 = new TouchEvent();
            touchEvent2.setId(touchEvent.getId() + 1);
            touchEvent2.setTimestamps(Test.GENERAL_LONG_LIST);
            touchEvent2.setTouchCoordinates(new ArrayList<TouchCoord>(Arrays.asList(new TouchCoord(Test.GENERAL_TOUCHCOORD.getX() + 1, Test.GENERAL_TOUCHCOORD.getY() + 1))));

            // Touch one pointer (Touch ID:100)
            OnTouchEvent testOnTouchEvent = new OnTouchEvent();
            testOnTouchEvent.setEvent(Collections.singletonList(touchEvent));
            testOnTouchEvent.setType(Test.GENERAL_TOUCHTYPE);
            List<MotionEvent> events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_DOWN, events.get(0).getAction());

            // Touch another pointer (Touch ID:101)
            testOnTouchEvent.setEvent(Collections.singletonList(touchEvent2));
            testOnTouchEvent.setType(Test.GENERAL_TOUCHTYPE);
            events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, events.get(0).getAction());

            // Release one of the pointers (Touch ID:101)
            testOnTouchEvent.setEvent(Collections.singletonList(touchEvent2));
            testOnTouchEvent.setType(TouchType.END);
            events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_POINTER_UP | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, events.get(0).getAction());

            // Release the other pointer (Touch ID:100)
            testOnTouchEvent.setEvent(Collections.singletonList(touchEvent));
            testOnTouchEvent.setType(TouchType.END);
            events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_UP, events.get(0).getAction());

            assert true;

        }catch (Exception e){
            assert false;
        }
    }

    public void testMultiBeginTouch() {
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(new ProxyListenerTest(), "appId", "appName", true, getContext());
        try{
            ClassLoader loader = this.getContext().getClassLoader();
            Class videoStreamingManagerClass = loader.loadClass("com.smartdevicelink.proxy.SdlProxyBase$VideoStreamingManager");

            Class[] parameterTypes = new Class[]{SdlProxyBase.class, Context.class, ISdl.class};
            Constructor constructor = videoStreamingManagerClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);

            // Get VideoStreamingManager Instance
            Object target = constructor.newInstance((SdlProxyBase)builder.build(), null, _internalInterface);

            // Enable call convertTouchEvent
            Method method = videoStreamingManagerClass.getDeclaredMethod("convertTouchEvent", OnTouchEvent.class);
            method.setAccessible(true);

            // Initialize touch event (Touch ID:100)
            TouchEvent touchEvent = Test.GENERAL_TOUCHEVENT;

            // Initialize touch event (Touch ID:101)
            TouchEvent touchEvent2 = new TouchEvent();
            touchEvent2.setId(touchEvent.getId() + 1);
            touchEvent2.setTimestamps(Test.GENERAL_LONG_LIST);
            touchEvent2.setTouchCoordinates(new ArrayList<TouchCoord>(Arrays.asList(new TouchCoord(Test.GENERAL_TOUCHCOORD.getX() + 1, Test.GENERAL_TOUCHCOORD.getY() + 1))));

            // Touch multi pointer (Touch ID:100, 101)
            OnTouchEvent testOnTouchEvent = new OnTouchEvent();
            testOnTouchEvent.setType(Test.GENERAL_TOUCHTYPE);
            testOnTouchEvent.setEvent(Arrays.asList(touchEvent, touchEvent2));
            List<MotionEvent> events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_DOWN, events.get(0).getAction());
            assertEquals(MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, events.get(1).getAction());

            assert true;

        }catch (Exception e){
            assert false;
        }
    }

    public void testMultiTouchOneFingerMove() {
        SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(new ProxyListenerTest(), "appId", "appName", true, getContext());
        try{
            ClassLoader loader = this.getContext().getClassLoader();
            Class videoStreamingManagerClass = loader.loadClass("com.smartdevicelink.proxy.SdlProxyBase$VideoStreamingManager");

            Class[] parameterTypes = new Class[]{SdlProxyBase.class, Context.class, ISdl.class};
            Constructor constructor = videoStreamingManagerClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);

            // Get VideoStreamingManager Instance
            Object target = constructor.newInstance((SdlProxyBase)builder.build(), null, _internalInterface);

            // Enable call convertTouchEvent
            Method method = videoStreamingManagerClass.getDeclaredMethod("convertTouchEvent", OnTouchEvent.class);
            method.setAccessible(true);

            // Initialize touch event (Touch ID:100)
            TouchEvent touchEvent = Test.GENERAL_TOUCHEVENT;

            // Initialize touch event (Touch ID:101)
            TouchEvent touchEvent2 = new TouchEvent();
            touchEvent2.setId(touchEvent.getId() + 1);
            touchEvent2.setTimestamps(Test.GENERAL_LONG_LIST);
            touchEvent2.setTouchCoordinates(new ArrayList<TouchCoord>(Arrays.asList(new TouchCoord(Test.GENERAL_TOUCHCOORD.getX() + 1, Test.GENERAL_TOUCHCOORD.getY() + 1))));

            // Touch multi pointer (Touch ID:100, 101)
            OnTouchEvent testOnTouchEvent = new OnTouchEvent();
            testOnTouchEvent.setType(Test.GENERAL_TOUCHTYPE);
            testOnTouchEvent.setEvent(Arrays.asList(touchEvent, touchEvent2));
            method.invoke(target, testOnTouchEvent);

            // Move pointer (Touch ID:101)
            testOnTouchEvent.setType(TouchType.MOVE);
            testOnTouchEvent.setEvent(Arrays.asList(touchEvent2));
            List<MotionEvent> events = (List<MotionEvent>)method.invoke(target, testOnTouchEvent);
            assertEquals(MotionEvent.ACTION_MOVE, events.get(0).getAction());
            assertEquals(2, events.get(0).getPointerCount());

            assert true;

        }catch (Exception e){
            assert false;
        }
    }

    private ISdl _internalInterface = new ISdl() {
        @Override
        public void start() { }

        @Override
        public void stop() { }

        @Override
        public boolean isConnected() { return false; }

        @Override
        public void addServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener) { }

        @Override
        public void removeServiceListener(SessionType serviceType, ISdlServiceListener sdlServiceListener) { }

        @Override
        public void startVideoService(VideoStreamingParameters parameters, boolean encrypted) { }

        @Override
        public void stopVideoService() { }

        @Override
        public void stopAudioService() { }

        @Override
        public void sendRPCRequest(RPCRequest message){ }

        @Override
        public void sendRequests(List<? extends RPCRequest> rpcs, OnMultipleRequestListener listener) { }

        @Override
        public void addOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener) {}

        @Override
        public boolean removeOnRPCNotificationListener(FunctionID notificationId, OnRPCNotificationListener listener) { return false; }

        @Override
        public void addOnRPCListener(FunctionID responseId, OnRPCListener listener) { }

        @Override
        public boolean removeOnRPCListener(FunctionID responseId, OnRPCListener listener) { return false; }

        @Override
        public Object getCapability(SystemCapabilityType systemCapabilityType){ return null; }

        @Override
        public void getCapability(SystemCapabilityType systemCapabilityType, OnSystemCapabilityListener scListener) { }

        @Override
        public SdlMsgVersion getSdlMsgVersion(){ return null; }

        @Override
        public com.smartdevicelink.util.Version getProtocolVersion() { return null; }

        @Override
        public boolean isCapabilitySupported(SystemCapabilityType systemCapabilityType){ return false; }

        @Override
        public void addOnSystemCapabilityListener(SystemCapabilityType systemCapabilityType, OnSystemCapabilityListener listener) { }

        @Override
        public boolean removeOnSystemCapabilityListener(SystemCapabilityType systemCapabilityType, OnSystemCapabilityListener listener) { return false; }

        @Override
        public boolean isTransportForServiceAvailable(SessionType serviceType) { return false; }

        @Override
        public void startAudioService(boolean isEncrypted, AudioStreamingCodec codec, AudioStreamingParams params) { }

        @Override
        public void startAudioService(boolean encrypted) { }

        @Override
        public IVideoStreamListener startVideoStream(boolean isEncrypted, VideoStreamingParameters parameters){ return null; }

        @Override
        public IAudioStreamListener startAudioStream(boolean isEncrypted, AudioStreamingCodec codec, AudioStreamingParams params) { return null; }
    };

    public class ProxyListenerTest implements IProxyListenerALM {

        @Override
        public void onProxyClosed(String s, Exception e, SdlDisconnectedReason reason) {

        }

        @Override
        public void onOnHMIStatus(OnHMIStatus status) {

        }

        @Override
        public void onListFilesResponse(ListFilesResponse response) {
        }

        @Override
        public void onPutFileResponse(PutFileResponse response) {
        }

        @Override
        public void onOnLockScreenNotification(OnLockScreenStatus notification) {
        }

        @Override
        public void onOnCommand(OnCommand notification){
        }

        //
        // *  Callback method that runs when the add command response is received from SDL.
        //
        @Override
        public void onAddCommandResponse(AddCommandResponse response) {
        }

        @Override
        public void onOnPermissionsChange(OnPermissionsChange notification) {

        }

        @Override
        public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse response) {
        }

        @Override
        public void onOnVehicleData(OnVehicleData notification) {
        }

        //**
        // * Rest of the SDL callbacks from the head unit
        //

        @Override
        public void onAddSubMenuResponse(AddSubMenuResponse response) {
        }

        @Override
        public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse response) {
        }

        @Override
        public void onAlertResponse(AlertResponse response) {
        }

        @Override
        public void onDeleteCommandResponse(DeleteCommandResponse response) {
        }

        @Override
        public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse response) {
        }

        @Override
        public void onDeleteSubMenuResponse(DeleteSubMenuResponse response) {
        }

        @Override
        public void onPerformInteractionResponse(PerformInteractionResponse response) {
        }

        @Override
        public void onResetGlobalPropertiesResponse(
                ResetGlobalPropertiesResponse response) {
        }

        @Override
        public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse response) {
        }

        @Override
        public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse response) {
        }

        @Override
        public void onShowResponse(ShowResponse response) {
        }

        @Override
        public void onSpeakResponse(SpeakResponse response) {
            Log.i(TAG, "SpeakCommand response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());
        }

        @Override
        public void onOnButtonEvent(OnButtonEvent notification) {
        }

        @Override
        public void onOnButtonPress(OnButtonPress notification) {
        }

        @Override
        public void onSubscribeButtonResponse(SubscribeButtonResponse response) {
        }

        @Override
        public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse response) {
        }


        @Override
        public void onOnTBTClientState(OnTBTClientState notification) {
        }

        @Override
        public void onUnsubscribeVehicleDataResponse(
                UnsubscribeVehicleDataResponse response) {

        }

        @Override
        public void onGetVehicleDataResponse(GetVehicleDataResponse response) {

        }

        @Override
        public void onReadDIDResponse(ReadDIDResponse response) {

        }

        @Override
        public void onGetDTCsResponse(GetDTCsResponse response) {

        }


        @Override
        public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse response) {

        }

        @Override
        public void onEndAudioPassThruResponse(EndAudioPassThruResponse response) {

        }

        @Override
        public void onOnAudioPassThru(OnAudioPassThru notification) {

        }

        @Override
        public void onDeleteFileResponse(DeleteFileResponse response) {

        }

        @Override
        public void onSetAppIconResponse(SetAppIconResponse response) {

        }

        @Override
        public void onScrollableMessageResponse(ScrollableMessageResponse response) {

        }

        @Override
        public void onChangeRegistrationResponse(ChangeRegistrationResponse response) {

        }

        @Override
        public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse response) {

        }

        @Override
        public void onOnLanguageChange(OnLanguageChange notification) {

        }

        @Override
        public void onSliderResponse(SliderResponse response) {

        }


        @Override
        public void onOnHashChange(OnHashChange notification) {

        }

        @Override
        public void onOnSystemRequest(OnSystemRequest notification) {
        }

        @Override
        public void onSystemRequestResponse(SystemRequestResponse response) {

        }

        @Override
        public void onOnKeyboardInput(OnKeyboardInput notification) {

        }

        @Override
        public void onOnTouchEvent(OnTouchEvent notification) {

        }

        @Override
        public void onDiagnosticMessageResponse(DiagnosticMessageResponse response) {

        }

        @Override
        public void onOnStreamRPC(OnStreamRPC notification) {

        }

        @Override
        public void onStreamRPCResponse(StreamRPCResponse response) {

        }

        @Override
        public void onDialNumberResponse(DialNumberResponse response) {

        }

        @Override
        public void onSendLocationResponse(SendLocationResponse response) {
            Log.i(TAG, "SendLocation response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());

        }

        @Override
        public void onServiceEnded(OnServiceEnded serviceEnded) {

        }

        @Override
        public void onServiceNACKed(OnServiceNACKed serviceNACKed) {

        }

        @Override
        public void onShowConstantTbtResponse(ShowConstantTbtResponse response) {
            Log.i(TAG, "ShowConstantTbt response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());

        }

        @Override
        public void onAlertManeuverResponse(AlertManeuverResponse response) {
            Log.i(TAG, "AlertManeuver response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());

        }

        @Override
        public void onUpdateTurnListResponse(UpdateTurnListResponse response) {
            Log.i(TAG, "UpdateTurnList response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());

        }

        @Override
        public void onServiceDataACK(int dataSize) {
        }

        @Override
        public void onGetWayPointsResponse(GetWayPointsResponse response) {
            Log.i(TAG, "GetWayPoints response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());
        }

        @Override
        public void onSubscribeWayPointsResponse(SubscribeWayPointsResponse response) {
            Log.i(TAG, "SubscribeWayPoints response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());
        }

        @Override
        public void onUnsubscribeWayPointsResponse(UnsubscribeWayPointsResponse response) {
            Log.i(TAG, "UnsubscribeWayPoints response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());
        }

        @Override
        public void onOnWayPointChange(OnWayPointChange notification) {
            Log.i(TAG, "OnWayPointChange notification from SDL: " + notification);
        }

        @Override
        public void onGetSystemCapabilityResponse(GetSystemCapabilityResponse response) {
            Log.i(TAG, "GetSystemCapability response from SDL: " + response);
        }

        @Override
        public void onGetInteriorVehicleDataResponse(GetInteriorVehicleDataResponse response) {
            Log.i(TAG, "GetInteriorVehicleData response from SDL: " + response);
        }

        @Override
        public void onButtonPressResponse(ButtonPressResponse response) {
            Log.i(TAG, "ButtonPress response from SDL: " + response);
        }

        @Override
        public void onSetInteriorVehicleDataResponse(SetInteriorVehicleDataResponse response) {
            Log.i(TAG, "SetInteriorVehicleData response from SDL: " + response);
        }

        @Override
        public void onOnInteriorVehicleData(OnInteriorVehicleData notification) {

        }

        @Override
        public void onOnDriverDistraction(OnDriverDistraction notification) {
            // Some RPCs (depending on region) cannot be sent when driver distraction is active.
        }

        @Override
        public void onError(String info, Exception e) {
        }

        @Override
        public void onGenericResponse(GenericResponse response) {
            Log.i(TAG, "Generic response from SDL: " + response.getResultCode().name() + " Info: " + response.getInfo());
        }

        @Override
		public void onSendHapticDataResponse(SendHapticDataResponse response) {
			Log.i(TAG, "SendHapticDataResponse response from SDL: " + response);
		}

		@Override
		public void onOnRCStatus(OnRCStatus notification) {
		}
======= */
    public void testSendRPCsAllSucceed(){
        testSendMultipleRPCs(false, 1);
    }

    public void testSendRPCsSomeFail(){
        testSendMultipleRPCs(false, 2);
    }

    public void testSendSequentialRPCsAllSucceed(){
        testSendMultipleRPCs(true, 1);
    }

    public void testSendSequentialRPCsSomeFail(){
        testSendMultipleRPCs(true, 2);
    }

    private void testSendMultipleRPCs(boolean sequentialSend, int caseNumber){
        final List<RPCRequest> rpcsList = new ArrayList<>();
        final List<RPCRequest> rpcsTempList = new ArrayList<>();
        final HashMap<RPCRequest, OnRPCResponseListener> requestsMap = new HashMap<>();
        onUpdateListenerCounter = 0;
        onFinishedListenerCounter = 0;
        onResponseListenerCounter = 0;
        onErrorListenerCounter = 0;


        // We extend the SdlProxyBase to be able to override getIsConnected() &  sendRPCMessagePrivate() methods so they don't cause issues when trying to send RPCs
        // Because otherwise, they will throw exception cause there not actual connection to head unit
        SdlProxyBase proxy = new SdlProxyBase() {

            @Override
            public Boolean getIsConnected() {
                return true;
            }

            @Override
            protected void sendRPCMessagePrivate(RPCMessage message) {
                // Do nothing
            }
        };


        // We need to get list of all OnRPCResponseListeners so we can trigger onResponse/onError for each RPC to fake a response from Core
        final Answer<Void> answer = new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                RPCRequest request = (RPCRequest) invocation.getMock();
                OnRPCResponseListener listener = (OnRPCResponseListener) args[0];
                requestsMap.put(request, listener);
                rpcsTempList.add(request);
                return null;
            }
        };


        // Prepare RPCs to send
        Speak speak = mock(Speak.class);
        doReturn(RPCMessage.KEY_REQUEST).when(speak).getMessageType();
        doAnswer(answer).when(speak).setOnRPCResponseListener(any(OnRPCResponseListener.class));
        rpcsList.add(speak);

        Show show = mock(Show.class);
        doReturn(RPCMessage.KEY_REQUEST).when(show).getMessageType();
        doAnswer(answer).when(show).setOnRPCResponseListener(any(OnRPCResponseListener.class));
        rpcsList.add(show);


        // Send RPCs
        remainingRequestsExpected = rpcsList.size();
        OnMultipleRequestListener onMultipleRequestListener = new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
                onUpdateListenerCounter++;
                assertEquals(remainingRequestsExpected, remainingRequests);
            }

            @Override
            public void onFinished() {
                onFinishedListenerCounter++;
            }

            @Override
            public void onError(int correlationId, Result resultCode, String info) {
                onErrorListenerCounter++;
                remainingRequestsExpected--;
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                onResponseListenerCounter++;
                remainingRequestsExpected--;
            }
        };
        try {
            if (sequentialSend) {
                proxy.sendSequentialRequests(rpcsList, onMultipleRequestListener);
            } else {
                proxy.sendRequests(rpcsList, onMultipleRequestListener);
            }
            assertTrue(true);
        } catch (SdlException e) {
            e.printStackTrace();
            fail();
        }


        // Trigger fake RPC responses
        int onUpdateListenerCounterExpected = 0, onFinishedListenerCounterExpected = 0, onResponseListenerCounterExpected = 0, onErrorListenerCounterExpected = 0;
        switch (caseNumber){
            case 1: // All RPCs succeed
                onUpdateListenerCounterExpected = 2;
                onFinishedListenerCounterExpected = 1;
                onResponseListenerCounterExpected = 2;
                onErrorListenerCounterExpected = 0;

                while (rpcsTempList.size() != 0){
                    RPCRequest request = rpcsTempList.remove(0);
                    if (request instanceof Speak) {
                        requestsMap.get(request).onResponse(request.getCorrelationID(), new SpeakResponse(true, Result.SUCCESS));
                    } else if (request instanceof Show) {
                        requestsMap.get(request).onResponse(request.getCorrelationID(), new ShowResponse(true, Result.SUCCESS));
                    }
                }
                break;
            case 2: // Some RPCs fail
                onUpdateListenerCounterExpected = 2;
                onFinishedListenerCounterExpected = 1;
                onResponseListenerCounterExpected = 1;
                onErrorListenerCounterExpected = 1;

                while (rpcsTempList.size() != 0){
                    RPCRequest request = rpcsTempList.remove(0);
                    if (request instanceof Speak) {
                        requestsMap.get(request).onError(request.getCorrelationID(), Result.DISALLOWED, "ERROR");
                    } else if (request instanceof Show) {
                        requestsMap.get(request).onResponse(request.getCorrelationID(), new ShowResponse(true, Result.SUCCESS));
                    }
                }
                break;
        }


        // Make sure the listener is called correctly
        assertEquals("onUpdate Listener was not called or called more/less frequently than expected", onUpdateListenerCounterExpected, onUpdateListenerCounter);
        assertEquals("onFinished Listener was not called or called more/less frequently than expected", onFinishedListenerCounterExpected, onFinishedListenerCounter);
        assertEquals("onResponse Listener was not called or called more/less frequently than expected", onResponseListenerCounterExpected, onResponseListenerCounter);
        assertEquals("onError Listener was not called or called more/less frequently than expected", onErrorListenerCounterExpected, onErrorListenerCounter);
    }
}

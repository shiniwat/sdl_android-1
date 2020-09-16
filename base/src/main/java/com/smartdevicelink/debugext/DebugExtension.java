package com.smartdevicelink.debugext;

// because this is under base package folder, we cannot use android.util.Log
//import android.util.Log;

public class DebugExtension {
    private static final String TAG = DebugExtension.class.getSimpleName();

    public interface Listener {
        public void onLog(long time, String tag, String msg);
        public void onTotalDataSize(long size);
        public void onConnectRouter(String routerName);
        public void onNotifyVersion(String versionInfo);
    }

    public static Listener sListener;

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    public static Listener getListener() {
        return sListener;
    }


    public static String TAGPREFIX;
    public static int REFRESH_RATE;

    public static void setTagPrefix(String tagPrefix) {
        TAGPREFIX = tagPrefix;
    }

    public static void setRefreshRate(int rate) {
        REFRESH_RATE = rate;
    }

    public static void log(String tag, String msg) {
        String t = "LIB:" + (TAGPREFIX != null ? TAGPREFIX + tag : tag);
        //Log.d(t, msg);
        if (sListener != null) {
            sListener.onLog(System.currentTimeMillis(), t, msg);
        } else {
            //Log.d(t, "listener is null: " + msg);
        }
    }

    public static void totalDataSize(long size) {
        if (sListener != null) {
            sListener.onTotalDataSize(size);
        }
    }

    public static void connectRouter(String name) {
        if (sListener != null) {
            sListener.onConnectRouter(name);
        }
    }
}

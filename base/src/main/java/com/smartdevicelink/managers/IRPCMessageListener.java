package com.smartdevicelink.managers;

import com.smartdevicelink.proxy.RPCMessage;

public interface IRPCMessageListener {
	void onRPCMessage(RPCMessage message);
}

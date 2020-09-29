/*
 * Copyright (c) 2017 - 2019, SmartDeviceLink Consortium, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of the SmartDeviceLink Consortium, Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.smartdevicelink.proxy.rpc;

import androidx.annotation.NonNull;

import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.enums.Result;
import com.smartdevicelink.proxy.rpc.enums.TriggerSource;

import java.util.Hashtable;

/**
 * PerformInteraction Response is sent, when PerformInteraction has been called
 *
 * @since SmartDeviceLink 1.0
 */
public class PerformInteractionResponse extends RPCResponse {
    public static final String KEY_MANUAL_TEXT_ENTRY = "manualTextEntry";
    public static final String KEY_TRIGGER_SOURCE = "triggerSource";
    public static final String KEY_CHOICE_ID = "choiceID";

    /**
     * Constructs a new PerformInteractionResponse object
     */
    public PerformInteractionResponse() {
        super(FunctionID.PERFORM_INTERACTION.toString());
    }

    /**
     * Constructs a new PerformInteractionResponse object indicated by the Hashtable
     * parameter
     * <p></p>
     *
     * @param hash The Hashtable to use
     */
    public PerformInteractionResponse(Hashtable<String, Object> hash) {
        super(hash);
    }

    /**
     * Constructs a new PerformInteractionResponse object
     *
     * @param success    whether the request is successfully processed
     * @param resultCode whether the request is successfully processed
     */
    public PerformInteractionResponse(@NonNull Boolean success, @NonNull Result resultCode) {
        this();
        setSuccess(success);
        setResultCode(resultCode);
    }

    /**
     * Gets the application-scoped identifier that uniquely identifies this choice.
     *
     * @return choiceID Min: 0  Max: 65535
     */
    public Integer getChoiceID() {
        return getInteger(KEY_CHOICE_ID);
    }

    /**
     * Sets the application-scoped identifier that uniquely identifies this choice.
     *
     * @param choiceID Min: 0  Max: 65535
     */
    public PerformInteractionResponse setChoiceID(Integer choiceID) {
        setParameters(KEY_CHOICE_ID, choiceID);
        return this;
    }

    /**
     * <p>Returns a <I>TriggerSource</I> object which will be shown in the HMI</p>
     *
     * @return TriggerSource a TriggerSource object
     */
    public TriggerSource getTriggerSource() {
        return (TriggerSource) getObject(TriggerSource.class, KEY_TRIGGER_SOURCE);
    }

    /**
     * <p>Sets TriggerSource
     * Indicates whether command was selected via VR or via a menu selection (using the OK button).</p>
     *
     * @param triggerSource a TriggerSource object
     */
    public PerformInteractionResponse setTriggerSource(TriggerSource triggerSource) {
        setParameters(KEY_TRIGGER_SOURCE, triggerSource);
        return this;
    }

    public PerformInteractionResponse setManualTextEntry(String manualTextEntry) {
        setParameters(KEY_MANUAL_TEXT_ENTRY, manualTextEntry);
        return this;
    }

    public String getManualTextEntry() {
        return getString(KEY_MANUAL_TEXT_ENTRY);
    }
}

package com.smartdevicelink.test.rpc.datatypes;

import com.smartdevicelink.proxy.rpc.DTC;
import com.smartdevicelink.test.JsonUtils;
import com.smartdevicelink.test.TestValues;

import junit.framework.TestCase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * This is a unit test class for the SmartDeviceLink library project class :
 * {@link com.smartdevicelink.proxy.rpc.DTC}
 */
public class DTCTests extends TestCase {

    private DTC msg;

    @Override
    public void setUp() {
        msg = new DTC();

        msg.setIdentifier(TestValues.GENERAL_STRING);
        msg.setStatusByte(TestValues.GENERAL_STRING);
    }

    /**
     * Tests the expected values of the RPC message.
     */
    public void testRpcValues() {
        // Test Values
        String identifier = msg.getIdentifier();
        String statusByte = msg.getStatusByte();

        // Valid Tests
        assertEquals(TestValues.MATCH, TestValues.GENERAL_STRING, identifier);
        assertEquals(TestValues.MATCH, TestValues.GENERAL_STRING, statusByte);

        // Invalid/Null Tests
        DTC msg = new DTC();
        assertNotNull(TestValues.NOT_NULL, msg);

        assertNull(TestValues.NULL, msg.getIdentifier());
        assertNull(TestValues.NULL, msg.getStatusByte());
    }

    public void testJson() {
        JSONObject reference = new JSONObject();

        try {
            reference.put(DTC.KEY_IDENTIFIER, TestValues.GENERAL_STRING);
            reference.put(DTC.KEY_STATUS_BYTE, TestValues.GENERAL_STRING);

            JSONObject underTest = msg.serializeJSON();
            assertEquals(TestValues.MATCH, reference.length(), underTest.length());

            Iterator<?> iterator = reference.keys();
            while (iterator.hasNext()) {
                String key = (String) iterator.next();
                assertEquals(TestValues.MATCH, JsonUtils.readObjectFromJsonObject(reference, key), JsonUtils.readObjectFromJsonObject(underTest, key));
            }
        } catch (JSONException e) {
            fail(TestValues.JSON_FAIL);
        }
    }
}
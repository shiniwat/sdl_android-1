package com.smartdevicelink.test.rpc.datatypes;

import com.smartdevicelink.proxy.rpc.VehicleDataResult;
import com.smartdevicelink.proxy.rpc.enums.VehicleDataResultCode;
import com.smartdevicelink.proxy.rpc.enums.VehicleDataType;
import com.smartdevicelink.test.JsonUtils;
import com.smartdevicelink.test.Test;

import junit.framework.TestCase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public class VehicleDataResultTest extends TestCase {
	
	private VehicleDataResult msg;

	@Override
	public void setUp() {
		msg = new VehicleDataResult();
		
		msg.setDataType(Test.GENERAL_VEHICLEDATATYPE);
		msg.setResultCode(Test.GENERAL_VEHICLEDATARESULTCODE);
		msg.setOEMCustomVehicleDataType(Test.GENERAL_OEM_CUSTOM_VEHICLE_DATA_NAME);
	}

    /**
	 * Tests the expected values of the RPC message.
	 */
    public void testRpcValues () {
		// Test Values
		VehicleDataResultCode result = msg.getResultCode();
		VehicleDataType type = msg.getDataType();
		String oemCustomDataType = msg.getOEMCustomVehicleDataType();
		
		// Valid Tests
		assertEquals(Test.MATCH, Test.GENERAL_VEHICLEDATARESULTCODE, result);
		assertEquals(Test.MATCH, Test.GENERAL_VEHICLEDATATYPE, type);
		assertEquals(Test.MATCH, Test.GENERAL_OEM_CUSTOM_VEHICLE_DATA_NAME, oemCustomDataType);
		
		// Invalid/Null Tests
		VehicleDataResult msg = new VehicleDataResult();
		assertNotNull(Test.NOT_NULL, msg);

		assertNull(Test.NULL, msg.getDataType());
		assertNull(Test.NULL, msg.getResultCode());
		assertNull(Test.NULL, msg.getOEMCustomVehicleDataType());
	}

	public void testJson() {
		JSONObject reference = new JSONObject();

		try {
			reference.put(VehicleDataResult.KEY_RESULT_CODE, Test.GENERAL_VEHICLEDATARESULTCODE);
			reference.put(VehicleDataResult.KEY_DATA_TYPE, Test.GENERAL_VEHICLEDATATYPE);
			reference.put(VehicleDataResult.KEY_OEM_CUSTOM_DATA_TYPE, Test.GENERAL_OEM_CUSTOM_VEHICLE_DATA_NAME);

			JSONObject underTest = msg.serializeJSON();
			assertEquals(Test.MATCH, reference.length(), underTest.length());

			Iterator<?> iterator = reference.keys();
			while (iterator.hasNext()) {
				String key = (String) iterator.next();
				assertEquals(Test.MATCH,
						JsonUtils.readObjectFromJsonObject(reference, key),
						JsonUtils.readObjectFromJsonObject(underTest, key));
			}
		} catch (JSONException e) {
			fail(Test.JSON_FAIL);
		}
	}
}
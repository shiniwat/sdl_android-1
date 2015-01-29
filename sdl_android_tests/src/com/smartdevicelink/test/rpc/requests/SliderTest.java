package com.smartdevicelink.test.rpc.requests;

import java.util.Arrays;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.rpc.Slider;
import com.smartdevicelink.test.BaseRpcTests;
import com.smartdevicelink.test.utils.JsonUtils;
import com.smartdevicelink.test.utils.Validator;

public class SliderTest extends BaseRpcTests {
	
	private static final Integer NUM_TICKS = 0;
	private static final Integer POSITION = 0;
	private static final Integer TIMEOUT = 0;
	private static final String HEADER = "header";
	private static final List<String> FOOTER = Arrays.asList(new String[]{"param1","param2"});

	@Override
	protected RPCMessage createMessage() {
		Slider msg = new Slider();

		msg.setNumTicks(NUM_TICKS);
		msg.setPosition(POSITION);
		msg.setTimeout(TIMEOUT);
		msg.setSliderHeader(HEADER);
		msg.setSliderFooter(FOOTER);

		return msg;
	}

	@Override
	protected String getMessageType() {
		return RPCMessage.KEY_REQUEST;
	}

	@Override
	protected String getCommandType() {
		return FunctionID.SLIDER;
	}

	@Override
	protected JSONObject getExpectedParameters(int sdlVersion) {
		JSONObject result = new JSONObject();

		try {
			result.put(Slider.KEY_SLIDER_HEADER, HEADER);
			result.put(Slider.KEY_SLIDER_FOOTER, JsonUtils.createJsonArray(FOOTER));
			result.put(Slider.KEY_POSITION, POSITION);
			result.put(Slider.KEY_TIMEOUT, TIMEOUT);
			result.put(Slider.KEY_NUM_TICKS, NUM_TICKS);
			
		} catch (JSONException e) {
			/* do nothing */
		}

		return result;
	}

	public void testHeader () {
		String copy = ( (Slider) msg ).getSliderHeader();
		
		assertEquals("Data didn't match input data.", HEADER, copy);
	}
	
	public void testFooter () {
		List<String> copy = ( (Slider) msg ).getSliderFooter();
		
		assertNotSame("Footer was not defensive copied", FOOTER, copy);
	    assertTrue("Input value didn't match expected value.", Validator.validateStringList(FOOTER, copy));
	}
	
	public void testPosition () {
		Integer copy = ( (Slider) msg ).getPosition();
		
		assertEquals("Data didn't match input data.", POSITION, copy);
	}
	
	public void testTimeout () {
		Integer copy = ( (Slider) msg ).getTimeout();
		
		assertEquals("Data didn't match input data.", TIMEOUT, copy);
	}
	
	public void testNumTicks () {
		Integer copy = ( (Slider) msg ).getNumTicks();
		
		assertEquals("Data didn't match input data.", NUM_TICKS, copy);
	}

	public void testNull() {
		Slider msg = new Slider();
		assertNotNull("Null object creation failed.", msg);

		testNullBase(msg);

		assertNull("Header wasn't set, but getter method returned an object.", msg.getSliderHeader());
		assertNull("Footer wasn't set, but getter method returned an object.", msg.getSliderFooter());
		assertNull("Position wasn't set, but getter method returned an object.", msg.getPosition());
		assertNull("Timeout wasn't set, but getter method returned an object.", msg.getTimeout());
		assertNull("Number of ticks wasn't set, but getter method returned an object.", msg.getNumTicks());
	}
}

package com.smartdevicelink.transport.enums;

/**
 * Defines available types of the transports.
 */
public enum TransportType {
	/**
	 * Experimental multiplexing (only supports bluetooth at the moment)
	 */
	MULTIPLEX,
	/**
	 * Transport type is Bluetooth.
	 */
	BLUETOOTH,
	
	/**
	 * Transport type is TCP.
	 */
	TCP,

	/**
	 * Transport type is USB and multiplex'ed USB AOA.
	 */
	USB,
	MULTIPLEX_AOA,
	// UNKNOWN means underlying transport is NOT connected.
	UNKNOWN,
	// **/;
	;
	
	public static TransportType valueForString(String value) {
		try{
            return valueOf(value);
        }catch(Exception e){
            return null;
        }
	}
}

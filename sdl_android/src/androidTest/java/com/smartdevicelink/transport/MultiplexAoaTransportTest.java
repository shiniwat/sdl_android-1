/*
 * Copyright (c) 2017, Xevo Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.smartdevicelink.transport;

import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;


public class MultiplexAoaTransportTest extends AndroidTestCase {
	public MultiplexAoaTransportTest() {
		super();
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();

	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
	}

	@Override
	public void testAndroidTestCaseSetupProperly() {
		super.testAndroidTestCaseSetupProperly();
		MultiplexAOATransport transport = MultiplexAOATransport.getInstance(getContext(), new Handler(Looper.getMainLooper()));
		assertNotNull(transport);
	}

	@Override
	public void setContext(Context context) {
		super.setContext(context);
	}

	@Override
	public Context getContext() {
		return super.getContext();
	}

	/**
	 * Asserts that launching a given activity is protected by a particular permission by
	 * attempting to start the activity and validating that a {@link SecurityException}
	 * is thrown that mentions the permission in its error message.
	 * <p>
	 * Note that an instrumentation isn't needed because all we are looking for is a security error
	 * and we don't need to wait for the activity to launch and get a handle to the activity.
	 *
	 * @param packageName The package name of the activity to launch.
	 * @param className   The class of the activity to launch.
	 * @param permission  The name of the permission.
	 */
	@Override
	public void assertActivityRequiresPermission(String packageName, String className, String permission) {
		super.assertActivityRequiresPermission(packageName, className, permission);
	}

	/**
	 * Asserts that reading from the content uri requires a particular permission by querying the
	 * uri and ensuring a {@link SecurityException} is thrown mentioning the particular permission.
	 *
	 * @param uri        The uri that requires a permission to query.
	 * @param permission The permission that should be required.
	 */
	@Override
	public void assertReadingContentUriRequiresPermission(Uri uri, String permission) {
		super.assertReadingContentUriRequiresPermission(uri, permission);
	}

	/**
	 * Asserts that writing to the content uri requires a particular permission by inserting into
	 * the uri and ensuring a {@link SecurityException} is thrown mentioning the particular
	 * permission.
	 *
	 * @param uri        The uri that requires a permission to query.
	 * @param permission The permission that should be required.
	 */
	@Override
	public void assertWritingContentUriRequiresPermission(Uri uri, String permission) {
		super.assertWritingContentUriRequiresPermission(uri, permission);
	}

	/**
	 * This function is called by various TestCase implementations, at tearDown() time, in order
	 * to scrub out any class variables.  This protects against memory leaks in the case where a
	 * test case creates a non-static inner class (thus referencing the test case) and gives it to
	 * someone else to hold onto.
	 *
	 * @param testCaseClass The class of the derived TestCase implementation.
	 * @throws IllegalAccessException
	 */
	@Override
	protected void scrubClass(Class<?> testCaseClass) throws IllegalAccessException {
		super.scrubClass(testCaseClass);
	}

	public void testWrite() {
		MultiplexAOATransport transport = MultiplexAOATransport.getInstance(getContext(), new Handler(Looper.getMainLooper()));
		transport.start();
		assertNotNull(transport);

		transport.write(new byte[1], 0, 1);
		transport.stop();
		assertNotNull(transport);
	}

}

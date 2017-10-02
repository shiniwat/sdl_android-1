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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.test.ServiceTestCase;

import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.transport.enums.TransportType;

import junit.framework.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class SdlAoaRouterServiceTest extends ServiceTestCase<SdlAoaRouterService> {

	/*
	 * This class provides final mutable values through indirection
	 */
	static class Holder<H> {
		H value;
	}

	protected Handler serviceHandler;
	protected Looper serviceLooper;
	/**
	 * Constructor
	 *
	 * @param serviceClass The type of the service under test.
	 */
	public SdlAoaRouterServiceTest(Class<SdlAoaRouterService> serviceClass) {
		super(serviceClass);
	}

	public SdlAoaRouterServiceTest() {
		super(SdlAoaRouterService.class);
	}
	/**
	 * @return An instance of the service under test. This instance is created automatically when
	 * a test calls {@link #startService} or {@link #bindService}.
	 */
	@Override
	public SdlAoaRouterService getService() {
		return super.getService();
	}

	/**
	 * Gets the current system context and stores it.
	 * <p>
	 * Extend this method to do your own test initialization. If you do so, you
	 * must call <code>super.setUp()</code> as the first statement in your override. The method is
	 * called before each test method is executed.
	 */
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// Setup service thread
		HandlerThread serviceThread = new HandlerThread("[" + SdlAoaRouterService.class.getSimpleName() + "Thread]");
		serviceThread.start();
		serviceLooper = serviceThread.getLooper();
		serviceHandler = new Handler(serviceLooper);
	}

	/**
	 * Runs the specified runnable on the service tread and waits for its
	 * completion.
	 *
	 * @see InstrumentationTestCase#runTestOnUiThread(Runnable)
	 * @param r
	 *            The runnable to run on the pseudo-main thread.
	 */
	protected void runOnServiceThread(final Runnable r) {
		final CountDownLatch serviceSignal = new CountDownLatch(1);
		serviceHandler.post(new Runnable() {

			@Override
			public void run() {
				r.run();
				serviceSignal.countDown();
			}
		});

		try {
			serviceSignal.await();
		} catch (InterruptedException ie) {
			fail("The Service thread has been interrupted");
		}
	}

	/**
	 * Runnable interface allowing service initialization personalization.
	 *
	 * @author Antoine Martin
	 *
	 */
	protected interface ServiceRunnable {
		public void run(Service service);
	}

	/**
	 * Initialize the service in its own thread and returns it.
	 *
	 * @param bound
	 *            if {@code true}, the service will be created as if it was
	 *            bound by a client. if {@code false}, it will be created by a
	 *            {@code startService} call.
	 * @param r
	 *            {@link ServiceRunnable} instance that will be called with the
	 *            created service.
	 * @return The created service.
	 */
	protected SdlAoaRouterService startService(final boolean bound, final ServiceRunnable r) {
		final Holder<SdlAoaRouterService> serviceHolder = new Holder<SdlAoaRouterService>();

		// I want to create my service in its own 'Main thread'
		// So it can use its handler
		runOnServiceThread(new Runnable() {

			@Override
			public void run() {
				SdlAoaRouterService service = null;
				Intent intent = new Intent(getContext(), SdlAoaRouterService.class);
				intent.putExtra("SdlAoaRouterServiceTest", true);
				if (bound) {
					/* IBinder binder = */bindService(intent);
				} else {
					startService(intent);
				}
				service = getService();
				if (r != null)
					r.run(service);
				serviceHolder.value = service;
			}
		});

		return serviceHolder.value;
	}

	public static class ServiceSyncHelper {
		// The semaphore will wakeup clients
		protected final Semaphore semaphore = new Semaphore(0);

		/**
		 * Waits for some response coming from the service.
		 *
		 * @param timeout
		 *            The maximum time to wait.
		 * @throws InterruptedException
		 *             if the Thread is interrupted or reaches the timeout.
		 */
		public synchronized void waitListener(long timeout) throws InterruptedException {
			if (!semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS))
				throw new InterruptedException();
		}
	}

	/**
	 * <p>
	 * Shuts down the service under test.  Ensures all resources are cleaned up and
	 * garbage collected before moving on to the next test. This method is called after each
	 * test method.
	 * </p>
	 * <p>
	 * Subclasses that override this method must call <code>super.tearDown()</code> as their
	 * last statement.
	 * </p>
	 *
	 * @throws Exception
	 */
	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		// teardown service thread
		if (serviceLooper != null)
			serviceLooper.quit();
		serviceHandler = null;
	}

	/**
	 * Tests that {@link #setupService()} runs correctly and issues an
	 * {@link Assert#assertNotNull(String, Object)} if it does.
	 * You can override this test method if you wish.
	 *
	 * @throws Exception
	 */
	@Override
	public void testServiceTestCaseSetUpProperly() throws Exception {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(false, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				aoaService.startUpSequence();
			}
		});
		assertNotNull(service);
	}

	public void testOnTransportConnect() {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(true, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				aoaService.onTransportConnected(TransportType.MULTIPLEX_AOA);
			}
		});
	}

	public void testOnTransportDisconnect() {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(true, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				aoaService.onTransportDisconnected(TransportType.MULTIPLEX_AOA);
			}
		});
	}

	public void testOnPacketRead() {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(true, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				// try reading null packet.
				aoaService.onPacketRead(new SdlPacket(-1, false, -1, -1, -1, -1, 0, -1, null));
			}
		});
	}

	public void testWriteBytesToTransport() {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(true, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				// try reading null packet.
				aoaService.writeBytesToTransport(new Bundle());
			}
		});
	}

	public void testManuallyWriteBytes() {
		setContext(getSystemContext());
		SdlAoaRouterService service = startService(true, new ServiceRunnable() {
			@Override
			public void run(Service service) {
				SdlAoaRouterService aoaService = (SdlAoaRouterService)service;
				assertNotNull(aoaService);
				// try reading null packet.
				aoaService.manuallyWriteBytes(new byte[1], 0, 1);
			}
		});
	}
}

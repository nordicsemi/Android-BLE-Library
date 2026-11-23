package no.nordicsemi.android.ble;

import android.bluetooth.BluetoothGatt;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BleManagerHandlerDelayedTest {

	private static class TestableBleManagerHandler extends BleManagerHandler {
		@Override
		protected boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
			return false;
		}

		@Override
		protected void onServicesInvalidated() {
		}
	}

	private static int countBleDelayedWorkThreads() {
		int count = 0;
		for (final Thread t : Thread.getAllStackTraces().keySet()) {
			if ("BleDelayedWork".equals(t.getName()) && t.isAlive()) {
				count++;
			}
		}
		return count;
	}

	@Test
	public void postDelayed_reusesSingleThreadAcrossCalls() throws InterruptedException {
		final TestableBleManagerHandler handler = new TestableBleManagerHandler();
		final int callCount = 50;
		final CountDownLatch latch = new CountDownLatch(callCount);
		final AtomicInteger executed = new AtomicInteger(0);

		try {
			for (int i = 0; i < callCount; i++) {
				handler.postDelayed(() -> {
					executed.incrementAndGet();
					latch.countDown();
				}, 10);
			}

			assertTrue("Expected all delayed callbacks to run", latch.await(5, TimeUnit.SECONDS));
			assertEquals(callCount, executed.get());
			assertTrue("Expected at most one BleDelayedWork thread", countBleDelayedWorkThreads() <= 1);
		} finally {
			handler.close();
		}
	}

	@Test
	public void removeCallbacks_cancelsPendingTask() throws InterruptedException {
		final TestableBleManagerHandler handler = new TestableBleManagerHandler();
		final AtomicBoolean ran = new AtomicBoolean(false);
		final Runnable task = () -> ran.set(true);

		try {
			handler.postDelayed(task, 100);
			handler.removeCallbacks(task);

			Thread.sleep(250);
			assertFalse("Cancelled callback should not execute", ran.get());
		} finally {
			handler.close();
		}
	}
}

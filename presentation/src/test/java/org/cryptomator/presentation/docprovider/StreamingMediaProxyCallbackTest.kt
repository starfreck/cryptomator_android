package org.cryptomator.presentation.docprovider

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class StreamingMediaProxyCallbackTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun testOnGetSizeReturnsExpectedSize() {
		val file = File(tempDir, "test.mp4").apply { createNewFile() }
		val lock = java.lang.Object()
		val callback = StreamingMediaProxyCallback(
			file = file,
			expectedSize = 508_196_374L,
			lock = lock,
			isCompleted = { false },
			errorProvider = { null },
			onReleased = {}
		)
		assertEquals(508_196_374L, callback.onGetSize())
	}

	@Test
	fun testOnReadReturnsZeroAtEof() {
		val file = File(tempDir, "test.mp4").apply { createNewFile() }
		val lock = java.lang.Object()
		val callback = StreamingMediaProxyCallback(
			file = file,
			expectedSize = 100L,
			lock = lock,
			isCompleted = { true },
			errorProvider = { null },
			onReleased = {}
		)
		val data = ByteArray(10)
		assertEquals(0, callback.onRead(100L, 10, data))
		assertEquals(0, callback.onRead(150L, 10, data))
	}

	@Test
	fun testOnReadReadsAvailableBytesImmediately() {
		val file = File(tempDir, "test.mp4")
		val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
		FileOutputStream(file).use { it.write(bytes) }

		val lock = java.lang.Object()
		val callback = StreamingMediaProxyCallback(
			file = file,
			expectedSize = 10L,
			lock = lock,
			isCompleted = { true },
			errorProvider = { null },
			onReleased = {}
		)
		val buffer = ByteArray(5)
		val read = callback.onRead(0L, 5, buffer)
		assertEquals(5, read)
		assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer)

		val buffer2 = ByteArray(5)
		val read2 = callback.onRead(5L, 5, buffer2)
		assertEquals(5, read2)
		assertArrayEquals(byteArrayOf(6, 7, 8, 9, 10), buffer2)
	}

	@Test
	fun testOnReadWaitsForDataAndReadsWhenWritten() {
		val file = File(tempDir, "test.mp4").apply { createNewFile() }
		val lock = java.lang.Object()
		val isCompleted = AtomicBoolean(false)
		val error = AtomicReference<Throwable?>(null)

		val callback = StreamingMediaProxyCallback(
			file = file,
			expectedSize = 20L,
			lock = lock,
			isCompleted = { isCompleted.get() },
			errorProvider = { error.get() },
			onReleased = {}
		)

		val writerThread = Thread {
			Thread.sleep(50)
			FileOutputStream(file).use { it.write(ByteArray(20) { idx -> idx.toByte() }) }
			synchronized(lock) {
				lock.notifyAll()
			}
		}
		writerThread.start()

		val buffer = ByteArray(10)
		val read = callback.onRead(0L, 10, buffer)
		writerThread.join()

		assertEquals(10, read)
		assertEquals(0.toByte(), buffer[0])
		assertEquals(9.toByte(), buffer[9])
	}

	@Test
	fun testOnReadThrowsWhenErrorSet() {
		val file = File(tempDir, "test.mp4").apply { createNewFile() }
		val lock = java.lang.Object()
		val isCompleted = AtomicBoolean(false)
		val error = AtomicReference<Throwable?>(IllegalStateException("Network failure"))

		val callback = StreamingMediaProxyCallback(
			file = file,
			expectedSize = 100L,
			lock = lock,
			isCompleted = { isCompleted.get() },
			errorProvider = { error.get() },
			onReleased = {}
		)

		try {
			callback.onRead(0L, 10, ByteArray(10))
			fail("Expected ErrnoException")
		} catch (e: ErrnoException) {
			assertEquals(OsConstants.EIO, e.errno)
		}
	}
}

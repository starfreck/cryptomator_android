package org.cryptomator.presentation.docprovider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

class StreamingMediaProxyCallback(
	private val file: File,
	private val expectedSize: Long,
	private val lock: java.lang.Object,
	private val isCompleted: () -> Boolean,
	private val errorProvider: () -> Throwable?,
	private val onHeartbeat: () -> Unit = {},
	private val onReleased: () -> Unit
) : ProxyFileDescriptorCallback() {

	private val raf by lazy { RandomAccessFile(file, "r") }
	private var lastHeartbeat = 0L

	override fun onGetSize(): Long {
		Timber.tag("CryptomatorStream").i("ProxyCallback.onGetSize: %s (disk=%s)", expectedSize, file.length())
		return expectedSize
	}

	override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
		if (offset >= expectedSize || size <= 0) {
			return 0
		}
		val now = System.currentTimeMillis()
		if (now - lastHeartbeat > 10_000L) {
			lastHeartbeat = now
			onHeartbeat()
		}

		val targetOffset = minOf(offset + size, expectedSize)
		val startTime = System.currentTimeMillis()
		val timeoutMs = 60_000L

		synchronized(lock) {
			while (file.length() < targetOffset && !isCompleted() && errorProvider() == null) {
				val elapsed = System.currentTimeMillis() - startTime
				if (elapsed >= timeoutMs) {
					Timber.tag("CryptomatorStream").e(
						"ProxyCallback.onRead timeout at offset %s (size=%s, disk=%s, expected=%s)",
						offset, size, file.length(), expectedSize
					)
					throw ErrnoException("onRead timeout", OsConstants.ETIMEDOUT)
				}
				try {
					lock.wait(minOf(timeoutMs - elapsed, 100L))
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					throw ErrnoException("onRead interrupted", OsConstants.EINTR)
				}
			}
		}

		val err = errorProvider()
		if (err != null && file.length() < targetOffset) {
			Timber.tag("CryptomatorStream").e(err, "ProxyCallback.onRead error at offset %s: %s", offset, err.message)
			throw ErrnoException("download failed", OsConstants.EIO)
		}

		val currentLength = file.length()
		if (offset >= currentLength) {
			return if (isCompleted()) 0 else {
				throw ErrnoException("read past available", OsConstants.EAGAIN)
			}
		}

		val available = (currentLength - offset).coerceAtLeast(0L)
		val toRead = minOf(size.toLong(), available).toInt()
		if (toRead <= 0) {
			return 0
		}

		synchronized(raf) {
			raf.seek(offset)
			val bytesRead = raf.read(data, 0, toRead)
			return if (bytesRead < 0) 0 else bytesRead
		}
	}

	override fun onRelease() {
		Timber.tag("CryptomatorStream").i("ProxyCallback.onRelease: %s (disk=%s)", file.name, file.length())
		try {
			raf.close()
		} catch (ignored: Exception) {}
		onReleased()
	}
}

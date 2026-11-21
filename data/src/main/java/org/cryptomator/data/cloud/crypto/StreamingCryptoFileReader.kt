package org.cryptomator.data.cloud.crypto

import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.DownloadFileReplacingProgressAware
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DownloadState
import org.cryptomator.domain.usecases.cloud.Progress
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Pipelined reader that streams ciphertext chunks from the cloud repository directly
 * into an in-memory piped channel, decrypting on the fly without writing unencrypted
 * or intermediate ciphertext files to flash storage.
 */
class StreamingCryptoFileReader(
	private val cloudContentRepository: CloudContentRepository<Cloud, CloudNode, CloudFolder, CloudFile>,
	private val cryptor: Supplier<Cryptor>
) {

	@Throws(BackendException::class)
	fun read(cryptoFile: CryptoFile, data: OutputStream, progressAware: ProgressAware<DownloadState>) {
		val ciphertextFile = cryptoFile.cloudFile
		val pipeBufferSize = 1024 * 1024 // 1 MB piped memory buffer
		val pipedOut = PipedOutputStream()
		val pipedIn = PipedInputStream(pipedOut, pipeBufferSize)
		val downloadError = AtomicReference<Throwable?>(null)

		val downloadThread = Thread({
			try {
				pipedOut.use { out ->
					cloudContentRepository.read(
						ciphertextFile,
						out,
						DownloadFileReplacingProgressAware(cryptoFile, progressAware)
					)
				}
			} catch (t: Throwable) {
				downloadError.set(t)
			} finally {
				try {
					pipedOut.close()
				} catch (_: IOException) {
				}
			}
		}, "StreamingCryptoDownloadProducer-${cryptoFile.name}")

		try {
			downloadThread.start()
			progressAware.onProgress(Progress.started(DownloadState.decryption(cryptoFile)))
			Channels.newChannel(pipedIn).use { readableByteChannel ->
				DecryptingReadableByteChannel(readableByteChannel, cryptor.get(), true).use { decryptingReadableByteChannel ->
					val buff = ByteBuffer.allocate(cryptor.get().fileContentCryptor().ciphertextChunkSize())
					val cleartextSize = cryptoFile.size ?: Long.MAX_VALUE
					var decrypted: Long = 0
					var read: Int
					while (decryptingReadableByteChannel.read(buff).also { read = it } > 0) {
						buff.flip()
						data.write(buff.array(), 0, buff.remaining())
						decrypted += read.toLong()
						progressAware.onProgress(
							Progress.progress(DownloadState.decryption(cryptoFile))
								.between(0)
								.and(cleartextSize)
								.withValue(decrypted)
						)
					}
				}
			}
		} catch (e: Throwable) {
			try {
				pipedIn.close()
			} catch (_: IOException) {
			}
			downloadThread.interrupt()
			try {
				downloadThread.join(2000)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			val err = downloadError.get()
			if (err is BackendException) throw err
			if (err is IOException) throw FatalBackendException(err)
			if (err != null) throw FatalBackendException(err)
			if (e is BackendException) throw e
			if (e is IOException) throw FatalBackendException(e)
			throw FatalBackendException(e)
		} finally {
			try {
				pipedIn.close()
			} catch (_: IOException) {
			}
			try {
				downloadThread.join()
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			progressAware.onProgress(Progress.completed(DownloadState.decryption(cryptoFile)))
		}

		val err = downloadError.get()
		if (err != null) {
			if (err is BackendException) throw err
			if (err is IOException) throw FatalBackendException(err)
			throw FatalBackendException(err)
		}
	}
}

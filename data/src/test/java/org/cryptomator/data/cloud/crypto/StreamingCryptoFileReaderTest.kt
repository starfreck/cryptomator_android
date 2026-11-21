package org.cryptomator.data.cloud.crypto

import com.google.common.io.BaseEncoding
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.FileContentCryptor
import org.cryptomator.cryptolib.api.FileHeader
import org.cryptomator.cryptolib.api.FileHeaderCryptor
import org.cryptomator.data.util.CopyStream.copyStreamToStream
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.ProgressAware
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class StreamingCryptoFileReaderTest {

	private val cloudContentRepository: CloudContentRepository<Cloud, CloudNode, CloudFolder, CloudFile> = mock()
	private val cryptor: Cryptor = mock()
	private val fileContentCryptor: FileContentCryptor = mock()
	private val fileHeaderCryptor: FileHeaderCryptor = mock()

	private val root: RootCryptoFolder = mock()
	private val cryptoFile: CryptoFile = mock()
	private val ciphertextFile: CloudFile = mock()

	private lateinit var reader: StreamingCryptoFileReader

	@BeforeEach
	fun setUp() {
		whenever(cryptor.fileContentCryptor()).thenReturn(fileContentCryptor)
		whenever(cryptor.fileHeaderCryptor()).thenReturn(fileHeaderCryptor)
		whenever(cryptoFile.cloudFile).thenReturn(ciphertextFile)
		whenever(cryptoFile.name).thenReturn("test_file")
		whenever(cryptoFile.size).thenReturn(32L)

		reader = StreamingCryptoFileReader(cloudContentRepository) { cryptor }
	}

	@Test
	@DisplayName("read() pipelines download and decrypt concurrently")
	@Throws(BackendException::class)
	fun testReadStreamsAndDecrypts() {
		val ciphertextData = "hhhhhCIPHER_1!CIPHER_2!".toByteArray(StandardCharsets.UTF_8)
		val header = mock<FileHeader>()

		whenever(fileContentCryptor.cleartextChunkSize()).thenReturn(8)
		whenever(fileContentCryptor.ciphertextChunkSize()).thenReturn(9)
		whenever(fileHeaderCryptor.headerSize()).thenReturn(5)
		whenever(fileHeaderCryptor.decryptHeader(StandardCharsets.UTF_8.encode("hhhhh"))).thenReturn(header)
		whenever(fileContentCryptor.decryptChunk(eq(StandardCharsets.UTF_8.encode("CIPHER_1!")), any(), eq(header), any()))
			.then { StandardCharsets.UTF_8.encode("PLAINT_1") }
		whenever(fileContentCryptor.decryptChunk(eq(StandardCharsets.UTF_8.encode("CIPHER_2!")), any(), eq(header), any()))
			.then { StandardCharsets.UTF_8.encode("PLAINT_2") }

		Mockito.doAnswer { invocation: InvocationOnMock ->
			val out = invocation.getArgument<OutputStream>(1)
			copyStreamToStream(ByteArrayInputStream(ciphertextData), out)
			null
		}.whenever(cloudContentRepository).read(eq(ciphertextFile), any(), any())

		val out = ByteArrayOutputStream()
		reader.read(cryptoFile, out, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)

		MatcherAssert.assertThat(out.toString(), CoreMatchers.`is`("PLAINT_1PLAINT_2"))
	}

	@Test
	@DisplayName("read() propagates BackendException from download thread")
	fun testReadPropagatesBackendException() {
		Mockito.doAnswer {
			throw NoSuchCloudFileException("File not found on remote")
		}.whenever(cloudContentRepository).read(eq(ciphertextFile), any(), any())

		val out = ByteArrayOutputStream()
		Assertions.assertThrows(NoSuchCloudFileException::class.java) {
			reader.read(cryptoFile, out, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)
		}
	}
}

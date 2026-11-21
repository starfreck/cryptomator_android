package org.cryptomator.data.cloud.mega

import android.content.Context
import dev.carlsen.mega.Mega
import dev.carlsen.mega.dto.QuotaResp
import dev.carlsen.mega.model.MegaFS
import dev.carlsen.mega.model.Node
import dev.carlsen.mega.model.NodeType
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import org.cryptomator.domain.MegaCloud
import org.cryptomator.domain.exception.CloudNodeAlreadyExistsException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.UnknownHostException

class MegaCloudContentRepositoryTest {

	private val cloud = MegaCloud.aMegaCloud().withId(1L).withUsername("alice@example.com").build()
	private val mega = mock<Mega>()
	private val context = mock<Context>()
	private val fileSystem = MegaFS()

	private val rootNode = Node().apply {
		hash = "root_hash"
		name = ""
		nodeType = NodeType.ROOT
	}

	private val folder1Node = Node().apply {
		hash = "f1_hash"
		name = "vault"
		nodeType = NodeType.FOLDER
	}

	private val folder2Node = Node().apply {
		hash = "f2_hash"
		name = "d"
		nodeType = NodeType.FOLDER
	}

	private val fileNode = Node().apply {
		hash = "file_hash"
		name = "masterkey.cryptomator"
		nodeType = NodeType.FILE
		size = 512L
		timestamp = Instant.fromEpochMilliseconds(1700000000000L)
	}

	private lateinit var repository: MegaCloudContentRepository

	@BeforeEach
	fun setup() {
		fileSystem.root = rootNode
		whenever(mega.getFileSystem()).thenReturn(fileSystem)
		repository = MegaCloudContentRepository(cloud, mega, context)
	}

	@Test
	fun testRootReturnsRootFolder() {
		val root = repository.root(cloud)
		assertEquals("root_hash", root.hash)
		assertEquals("/", root.path)
		assertEquals("", root.name)
		assertEquals(cloud, root.cloud)
	}

	@Test
	fun testResolveRootPath() {
		val root = repository.resolve(cloud, "/")
		assertEquals("root_hash", root.hash)
		assertEquals("/", root.path)
	}

	@Test
	fun testResolveSubfolderPathAndCache() {
		whenever(mega.getChildren(rootNode)).thenReturn(listOf(folder1Node))
		whenever(mega.getChildren(folder1Node)).thenReturn(listOf(folder2Node))

		val resolved = repository.resolve(cloud, "/vault/d")
		assertEquals("f2_hash", resolved.hash)
		assertEquals("/vault/d", resolved.path)
		assertEquals("d", resolved.name)
		assertEquals("/vault", resolved.parent?.path)

		// Second resolve uses cached hash
		whenever(mega.getNodeByHash("f2_hash")).thenReturn(folder2Node)
		val cachedResolved = repository.resolve(cloud, "/vault/d")
		assertEquals("f2_hash", cachedResolved.hash)
		verify(mega, times(1)).getChildren(folder1Node)
	}

	@Test
	fun testExistsReturnsTrueWhenNodeExists() {
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(listOf(folder1Node))

		val folder = MegaFolder(hash = "f1_hash", name = "vault", path = "/vault", parent = repository.root(cloud), cloud = cloud)
		assertTrue(repository.exists(folder))
	}

	@Test
	fun testExistsReturnsFalseWhenNodeNotFound() {
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(emptyList())

		val folder = MegaFolder(hash = "", name = "nonexistent", path = "/nonexistent", parent = repository.root(cloud), cloud = cloud)
		assertFalse(repository.exists(folder))
	}

	@Test
	fun testListFolderContents() {
		whenever(mega.getNodeByHash("f1_hash")).thenReturn(folder1Node)
		whenever(mega.getChildren(folder1Node)).thenReturn(listOf(folder2Node, fileNode))

		val parent = MegaFolder(hash = "f1_hash", name = "vault", path = "/vault", parent = repository.root(cloud), cloud = cloud)
		val children = repository.list(parent)

		assertEquals(2, children.size)
		val childFolder = children.filterIsInstance<MegaFolder>().first()
		assertEquals("d", childFolder.name)
		assertEquals("/vault/d", childFolder.path)

		val childFile = children.filterIsInstance<MegaFile>().first()
		assertEquals("masterkey.cryptomator", childFile.name)
		assertEquals("/vault/masterkey.cryptomator", childFile.path)
		assertEquals(512L, childFile.size)
	}

	@Test
	fun testCreateFolderSuccess() = runBlocking {
		val targetFolder = MegaFolder(hash = "", name = "newFolder", path = "/newFolder", parent = repository.root(cloud), cloud = cloud)
		val createdNode = Node().apply {
			hash = "created_hash"
			name = "newFolder"
			nodeType = NodeType.FOLDER
		}

		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(emptyList())
		whenever(mega.createDir(eq("newFolder"), eq(rootNode))).thenReturn(createdNode)

		val result = repository.create(targetFolder)
		assertEquals("created_hash", result.hash)
		assertEquals("newFolder", result.name)
	}

	@Test
	fun testDeleteNode() = runBlocking {
		whenever(mega.getNodeByHash("f1_hash")).thenReturn(folder1Node)

		val folder = MegaFolder(hash = "f1_hash", name = "vault", path = "/vault", parent = repository.root(cloud), cloud = cloud)
		repository.delete(folder)

		verify(mega).delete(folder1Node, true)
	}

	@Test
	fun testMoveFolder() = runBlocking {
		val srcFolder = MegaFolder(hash = "f1_hash", name = "vault", path = "/vault", parent = repository.root(cloud), cloud = cloud)
		val destFolder = MegaFolder(hash = "", name = "vault", path = "/dest/vault", parent = MegaFolder(hash = "f2_hash", name = "dest", path = "/dest"), cloud = cloud)

		whenever(mega.getNodeByHash("f1_hash")).thenReturn(folder1Node)
		whenever(mega.getNodeByHash("f2_hash")).thenReturn(folder2Node)

		repository.move(srcFolder, destFolder)

		verify(mega).move(folder1Node, folder2Node)
	}

	@Test
	fun testMetadataReturnsFileMetadata() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(listOf(fileNode))

		val file = MegaFile(hash = "", name = "masterkey.cryptomator", path = "/masterkey.cryptomator", parent = root, cloud = cloud)
		val meta = repository.metadata(file)

		assertNotNull(meta)
		assertEquals("file_hash", meta?.hash)
		assertEquals(512L, meta?.size)
		assertNotNull(meta?.modified)
	}

	@Test
	fun testMetadataReturnsNullWhenFileNotFound() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(emptyList())

		val file = MegaFile(hash = "", name = "missing.txt", path = "/missing.txt", parent = root, cloud = cloud)
		val meta = repository.metadata(file)

		assertNull(meta)
	}

	@Test
	fun testWriteThrowsCloudNodeAlreadyExistsExceptionWhenReplaceFalse() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(listOf(fileNode))

		val targetFile = MegaFile(hash = "", name = "masterkey.cryptomator", path = "/masterkey.cryptomator", parent = root, cloud = cloud)
		val dataSource = mock<DataSource>()

		assertThrows(CloudNodeAlreadyExistsException::class.java) {
			repository.write(targetFile, dataSource, ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, replace = false, size = 100L)
		}
	}

	@Test
	fun testWriteSuccessNewFile() = runBlocking {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(emptyList())

		val uploadedNode = Node().apply {
			hash = "uploaded_hash"
			name = "file.txt"
			nodeType = NodeType.FILE
			size = 100L
			timestamp = Instant.fromEpochMilliseconds(1700000000000L)
		}
		whenever(mega.uploadFile(eq(rootNode), eq("file.txt"), eq(100L), any(), any(), any()))
			.thenReturn(uploadedNode)

		val targetFile = MegaFile(hash = "", name = "file.txt", path = "/file.txt", parent = root, cloud = cloud)
		val dataSource = mock<DataSource>()
		whenever(dataSource.open(context)).thenReturn(ByteArrayInputStream("content".toByteArray()))

		val result = repository.write(targetFile, dataSource, ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, replace = false, size = 100L)
		assertEquals("uploaded_hash", result.hash)
		assertEquals(100L, result.size)
		verify(mega, never()).delete(any(), any())
	}

	@Test
	fun testWriteAtomicReplaceExistingFile() = runBlocking {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenReturn(rootNode)
		whenever(mega.getChildren(rootNode)).thenReturn(listOf(fileNode))

		val uploadedNode = Node().apply {
			hash = "new_uploaded_hash"
			name = "temp_name"
			nodeType = NodeType.FILE
			size = 200L
			timestamp = Instant.fromEpochMilliseconds(1700000000000L)
		}
		whenever(mega.uploadFile(eq(rootNode), any(), eq(200L), any(), any(), any()))
			.thenReturn(uploadedNode)

		val targetFile = MegaFile(hash = "", name = "masterkey.cryptomator", path = "/masterkey.cryptomator", parent = root, cloud = cloud)
		val dataSource = mock<DataSource>()
		whenever(dataSource.open(context)).thenReturn(ByteArrayInputStream("new_content".toByteArray()))

		val result = repository.write(targetFile, dataSource, ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, replace = true, size = 200L)
		assertEquals("new_uploaded_hash", result.hash)
		verify(mega).delete(fileNode, true)
		verify(mega).rename(uploadedNode, "masterkey.cryptomator")
	}

	@Test
	fun testMegaExceptionNotFoundMapping() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer { throw dev.carlsen.mega.model.MegaException("Not found", null, -9) }
		assertThrows(NoSuchCloudFileException::class.java) {
			repository.list(root)
		}
	}

	@Test
	fun testMegaExceptionSessionExpiredMapping() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer { throw dev.carlsen.mega.model.MegaException("Session expired", null, -15) }
		assertThrows(org.cryptomator.domain.exception.authentication.WrongCredentialsException::class.java) {
			repository.list(root)
		}
	}

	@Test
	fun testMegaExceptionRateLimitedMapping() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer { throw dev.carlsen.mega.model.MegaException("Rate limited", null, -4) }
		assertThrows(NetworkConnectionException::class.java) {
			repository.list(root)
		}
	}

	@Test
	fun testMegaExceptionOverQuotaMapping() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer { throw dev.carlsen.mega.model.MegaException("Over quota", null, -17) }
		val ex = assertThrows(FatalBackendException::class.java) {
			repository.list(root)
		}
		assertTrue(ex.message?.contains("transfer quota exceeded") == true)
	}

	@Test
	fun testNetworkExceptionMapping() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer { throw UnknownHostException("api.mega.co.nz") }

		assertThrows(NetworkConnectionException::class.java) {
			repository.list(root)
		}
	}

	@Test
	fun testCheckAuthenticationWithQuota() = runBlocking {
		val quotaResp = mock<QuotaResp>()
		whenever(quotaResp.spaceCapacityBytes).thenReturn((20L * 1024 * 1024 * 1024).toULong())
		whenever(quotaResp.spaceUsedBytes).thenReturn((5L * 1024 * 1024 * 1024).toULong())
		whenever(mega.getQuota()).thenReturn(quotaResp)

		val result = repository.checkAuthenticationAndRetrieveCurrentAccount(cloud)
		assertTrue(result.contains("alice@example.com"))
		assertTrue(result.contains("5.0 GB / 20.0 GB"))
	}

	@Test
	fun testCheckAuthenticationFallbackOnQuotaFailure() = runBlocking {
		whenever(mega.getQuota()).thenAnswer { throw IllegalStateException("Quota API failed") }

		val result = repository.checkAuthenticationAndRetrieveCurrentAccount(cloud)
		assertEquals("alice@example.com", result)
	}

	@Test
	fun testListFiltersOutCryptomatorTmpFilesAndTriggersDeleteForStale() = runBlocking {
		val tmpFileNode = Node().apply {
			hash = "stale_tmp_hash"
			name = "vault.cryptomator_tmp_1234abcd"
			nodeType = NodeType.FILE
			size = 100L
			timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 7200_000L) // 2 hours old
		}
		whenever(mega.getNodeByHash("f1_hash")).thenReturn(folder1Node)
		whenever(mega.getChildren(folder1Node)).thenReturn(listOf(folder2Node, tmpFileNode))

		val parent = MegaFolder(hash = "f1_hash", name = "vault", path = "/vault", parent = repository.root(cloud), cloud = cloud)
		val children = repository.list(parent)

		// tmp file must be excluded from visible listing
		assertEquals(1, children.size)
		assertEquals("d", children.first().name)
	}

	@Test
	fun testReadCachesFileAndServesFromCacheOnSecondRead() = runBlocking {
		val tempCacheDir = File(System.getProperty("java.io.tmpdir"), "mega_test_cache_${System.currentTimeMillis()}").apply { mkdirs() }
		try {
			whenever(context.cacheDir).thenReturn(tempCacheDir)

			val testCachedNode = Node().apply {
				hash = "cached_file_hash"
				name = "doc.txt"
				nodeType = NodeType.FILE
				size = 12L
				timestamp = Instant.fromEpochMilliseconds(1700000000000L)
			}
			whenever(mega.getNodeByHash("cached_file_hash")).thenReturn(testCachedNode)
			whenever(mega.downloadFile(eq(testCachedNode), any(), any(), any())).thenAnswer { invocation ->
				val sink = invocation.getArgument<kotlinx.io.Sink>(1)
				val bytes = "hello cached".toByteArray()
				sink.write(bytes)
				sink.flush()
				null
			}

			val targetFile = MegaFile(
				hash = "cached_file_hash",
				name = "doc.txt",
				path = "/doc.txt",
				parent = repository.root(cloud),
				cloud = cloud,
				size = 12L
			)

			// First read: downloads from mega and populates cache
			val out1 = ByteArrayOutputStream()
			repository.read(targetFile, out1, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)
			assertEquals("hello cached", out1.toString())
			verify(mega, times(1)).downloadFile(eq(testCachedNode), any(), any(), any())

			// Second read: should hit local disk cache, mega.downloadFile is NOT called again
			val out2 = ByteArrayOutputStream()
			repository.read(targetFile, out2, ProgressAware.NO_OP_PROGRESS_AWARE_DOWNLOAD)
			assertEquals("hello cached", out2.toString())
			verify(mega, times(1)).downloadFile(eq(testCachedNode), any(), any(), any())
		} finally {
			tempCacheDir.deleteRecursively()
		}
	}

	@Test
	fun testDeleteRemovesMatchingCachedFiles() = runBlocking {
		val tempCacheDir = File(System.getProperty("java.io.tmpdir"), "mega_test_cache_del_${System.currentTimeMillis()}").apply { mkdirs() }
		try {
			whenever(context.cacheDir).thenReturn(tempCacheDir)
			val megaCacheFolder = File(tempCacheDir, "mega_cache").apply { mkdirs() }
			val cacheFile = File(megaCacheFolder, "cached_file_hash_12")
			cacheFile.writeText("hello cached")
			assertTrue(cacheFile.exists())

			val targetNode = Node().apply {
				hash = "cached_file_hash"
				name = "doc.txt"
				nodeType = NodeType.FILE
			}
			whenever(mega.getNodeByHash("cached_file_hash")).thenReturn(targetNode)

			val targetFile = MegaFile(
				hash = "cached_file_hash",
				name = "doc.txt",
				path = "/doc.txt",
				parent = repository.root(cloud),
				cloud = cloud
			)

			repository.delete(targetFile)
			verify(mega).delete(targetNode, true)
			assertFalse(cacheFile.exists())
		} finally {
			tempCacheDir.deleteRecursively()
		}
	}

	@Test
	fun testMegaExceptionOverQuotaWithWaitTime() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer {
			throw dev.carlsen.mega.model.MegaException("Bandwidth limit reached. Try again in 3600 seconds.", null, -17)
		}
		val ex = assertThrows(FatalBackendException::class.java) {
			repository.list(root)
		}
		assertTrue(ex.message?.contains("retry in 3600 seconds") == true)
	}

	@Test
	fun testMegaExceptionOverQuotaDefaultResetHint() {
		val root = repository.root(cloud)
		whenever(mega.getNodeByHash("root_hash")).thenAnswer {
			throw dev.carlsen.mega.model.MegaException("Over quota", null, -17)
		}
		val ex = assertThrows(FatalBackendException::class.java) {
			repository.list(root)
		}
		assertTrue(ex.message?.contains("resets in ~4–6 hours") == true)
	}
}

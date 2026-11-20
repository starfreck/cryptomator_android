package org.cryptomator.data.cloud.mega

import org.cryptomator.domain.MegaCloud
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Date

class MegaNodesTest {

	private val cloud = MegaCloud.aMegaCloud().withId(1L).withUsername("alice@example.com").build()

	@Test
	fun testMegaFolderProperties() {
		val root = MegaFolder(hash = "rootHash", name = "", path = "/", parent = null, cloud = cloud)
		val subfolder = MegaFolder(hash = "subHash", name = "docs", path = "/docs", parent = root, cloud = cloud)

		assertEquals("rootHash", root.hash)
		assertEquals("", root.name)
		assertEquals("/", root.path)
		assertNull(root.parent)
		assertEquals(cloud, root.cloud)

		assertEquals("subHash", subfolder.hash)
		assertEquals("docs", subfolder.name)
		assertEquals("/docs", subfolder.path)
		assertEquals(root, subfolder.parent)

		val newCloud = MegaCloud.aMegaCloud().withId(2L).withUsername("bob@example.com").build()
		val copiedFolder = subfolder.withCloud(newCloud)
		assertEquals(newCloud, copiedFolder.cloud)
	}

	@Test
	fun testMegaFileProperties() {
		val root = MegaFolder(hash = "rootHash", name = "", path = "/", parent = null, cloud = cloud)
		val modifiedDate = Date(1700000000000L)
		val file = MegaFile(
			hash = "fileHash",
			name = "test.txt",
			path = "/test.txt",
			parent = root,
			cloud = cloud,
			size = 1024L,
			modified = modifiedDate
		)

		assertEquals("fileHash", file.hash)
		assertEquals("test.txt", file.name)
		assertEquals("/test.txt", file.path)
		assertEquals(root, file.parent)
		assertEquals(cloud, file.cloud)
		assertEquals(1024L, file.size)
		assertEquals(modifiedDate, file.modified)
	}
}

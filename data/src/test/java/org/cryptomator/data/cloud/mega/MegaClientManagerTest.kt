package org.cryptomator.data.cloud.mega

import org.cryptomator.domain.MegaCloud
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MegaClientManagerTest {

	@Test
	fun testClientCachingAndClearing() {
		val cloud = MegaCloud.aMegaCloud()
			.withId(100L)
			.withUsername("testuser@example.com")
			.build()

		val client1 = MegaClientManager.getClient(cloud)
		assertNotNull(client1)

		val client2 = MegaClientManager.getClient(cloud)
		assertSame(client1, client2)

		MegaClientManager.clear(cloud)

		val client3 = MegaClientManager.getClient(cloud)
		assertNotNull(client3)
		assertNotSame(client1, client3)
	}
}

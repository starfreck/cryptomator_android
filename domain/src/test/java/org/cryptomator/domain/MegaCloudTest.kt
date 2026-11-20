package org.cryptomator.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MegaCloudTest {

	@Test
	fun testMegaCloudCreationAndProperties() {
		val cloud = MegaCloud.aMegaCloud()
			.withId(101L)
			.withUsername("alice@example.com")
			.withAccessToken("testSessionKey")
			.build()

		assertEquals(101L, cloud.id())
		assertEquals("alice@example.com", cloud.username())
		assertEquals("testSessionKey", cloud.accessToken())
		assertEquals(CloudType.MEGA, cloud.type())
		assertTrue(cloud.persistent())
		assertTrue(cloud.requiresNetwork())
		assertEquals(false, cloud.isReadOnly())
	}

	@Test
	fun testCopyMegaCloudUpdatesSession() {
		val original = MegaCloud.aMegaCloud()
			.withId(202L)
			.withUsername("bob@example.com")
			.withAccessToken("oldSession")
			.build()

		val copy = MegaCloud.aCopyOf(original)
			.withAccessToken("newSession")
			.build()

		assertEquals(202L, copy.id())
		assertEquals("bob@example.com", copy.username())
		assertEquals("newSession", copy.accessToken())
		assertEquals(CloudType.MEGA, copy.type())
	}

	@Test
	fun testConfigurationMatches() {
		val cloud1 = MegaCloud.aMegaCloud()
			.withId(1L)
			.withUsername("alice@example.com")
			.withAccessToken("sess1")
			.build()

		val cloud2 = MegaCloud.aMegaCloud()
			.withId(2L)
			.withUsername("alice@example.com")
			.withAccessToken("sess2")
			.build()

		val cloudOther = MegaCloud.aMegaCloud()
			.withId(3L)
			.withUsername("charlie@example.com")
			.withAccessToken("sess1")
			.build()

		assertTrue(cloud1.configurationMatches(cloud2))
		assertEquals(false, cloud1.configurationMatches(cloudOther))
	}
}

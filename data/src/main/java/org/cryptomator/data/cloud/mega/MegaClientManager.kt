package org.cryptomator.data.cloud.mega

import dev.carlsen.mega.Mega
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.cryptomator.domain.MegaCloud
import java.util.concurrent.ConcurrentHashMap

object MegaClientManager {
	private val clients = ConcurrentHashMap<String, Mega>()

	private fun clientKey(cloud: MegaCloud): String =
		cloud.id()?.toString() ?: cloud.username()?.substringBefore(" (") ?: "default"

	fun getClient(cloud: MegaCloud): Mega {
		val key = clientKey(cloud)
		clients[key]?.let { return it }

		return synchronized(this) {
			clients[key]?.let { return it }
			val client = Mega(
				retries = 5,
				requestTimeout = 60.seconds,
				connectionTimeout = 30.seconds,
				// Pinned to 1 to prevent chunk scrambling race condition in dev.carlsen.mega:1.0.0-beta08.
				// TODO: Bump to dev.carlsen.mega 1.0.0-beta09+ once published to Maven Central to re-enable parallel uploads.
				maxConcurrentChunks = 1
			)
			val token = cloud.accessToken()
			if (!token.isNullOrEmpty()) {
				runBlocking {
					try {
						client.fastLogin(token)
					} catch (e: Exception) {
						// session might be expired
					}
				}
			}
			clients[key] = client
			client
		}
	}

	fun clear(cloud: MegaCloud) {
		val key = clientKey(cloud)
		clients.remove(key)
	}
}

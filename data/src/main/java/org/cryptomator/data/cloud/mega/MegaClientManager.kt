package org.cryptomator.data.cloud.mega

import dev.carlsen.mega.Mega
import kotlinx.coroutines.runBlocking
import org.cryptomator.domain.MegaCloud
import java.util.concurrent.ConcurrentHashMap

object MegaClientManager {
	private val clients = ConcurrentHashMap<String, Mega>()

	fun getClient(cloud: MegaCloud): Mega {
		val key = cloud.username() ?: cloud.id()?.toString() ?: "default"
		return clients.computeIfAbsent(key) {
			val client = Mega(retries = 2)
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
			client
		}
	}

	fun clear(cloud: MegaCloud) {
		val key = cloud.username() ?: cloud.id()?.toString() ?: "default"
		clients.remove(key)
	}
}

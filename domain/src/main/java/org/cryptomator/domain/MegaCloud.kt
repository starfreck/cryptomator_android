package org.cryptomator.domain

class MegaCloud private constructor(builder: Builder) : Cloud {

	private val id: Long? = builder.id
	private val accessToken: String? = builder.accessToken
	private val username: String? = builder.username

	companion object {
		@JvmStatic
		fun aMegaCloud(): Builder = Builder()

		@JvmStatic
		fun aCopyOf(cloud: MegaCloud): Builder = Builder()
			.withId(cloud.id())
			.withAccessToken(cloud.accessToken())
			.withUsername(cloud.username())
	}

	override fun id(): Long? = id
	fun accessToken(): String? = accessToken
	fun username(): String? = username

	override fun type(): CloudType = CloudType.MEGA
	override fun configurationMatches(cloud: Cloud?): Boolean = cloud is MegaCloud && cloud.username == this.username
	override fun persistent(): Boolean = true
	override fun requiresNetwork(): Boolean = true
	override fun isReadOnly(): Boolean = false

	override fun toString(): String = "MEGA"

	class Builder {
		internal var id: Long? = null
		internal var accessToken: String? = null
		internal var username: String? = null

		fun withId(id: Long?): Builder = apply { this.id = id }
		fun withAccessToken(token: String?): Builder = apply { this.accessToken = token }
		fun withUsername(username: String?): Builder = apply { this.username = username }

		fun build(): MegaCloud = MegaCloud(this)
	}
}

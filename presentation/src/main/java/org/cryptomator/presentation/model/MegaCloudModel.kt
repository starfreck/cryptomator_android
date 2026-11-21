package org.cryptomator.presentation.model

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.MegaCloud
import org.cryptomator.presentation.R

class MegaCloudModel(cloud: Cloud) : CloudModel(cloud) {

	override fun name(): Int {
		return R.string.cloud_names_mega
	}

	override fun username(): String? {
		val nick = cloud().nickname()
		val user = cloud().username()
		return if (!nick.isNullOrBlank()) {
			"$nick • $user"
		} else {
			user
		}
	}

	fun id(): Long? {
		return cloud().id()
	}

	private fun cloud(): MegaCloud {
		return toCloud() as MegaCloud
	}

	override fun cloudType(): CloudTypeModel {
		return CloudTypeModel.MEGA
	}
}

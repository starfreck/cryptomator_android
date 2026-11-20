package org.cryptomator.data.cloud.mega

import android.content.Context
import org.cryptomator.data.repository.CloudContentRepositoryFactory
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudType
import org.cryptomator.domain.MegaCloud
import org.cryptomator.domain.repository.CloudContentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MegaCloudContentRepositoryFactory @Inject constructor(
	private val context: Context
) : CloudContentRepositoryFactory {

	override fun supports(cloud: Cloud): Boolean {
		return cloud.type() == CloudType.MEGA
	}

	override fun cloudContentRepositoryFor(cloud: Cloud): CloudContentRepository<MegaCloud, MegaNode, MegaFolder, MegaFile> {
		val megaCloud = cloud as MegaCloud
		val client = MegaClientManager.getClient(megaCloud)
		return MegaCloudContentRepository(megaCloud, client, context)
	}
}

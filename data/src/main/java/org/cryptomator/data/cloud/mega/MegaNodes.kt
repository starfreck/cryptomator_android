package org.cryptomator.data.cloud.mega

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import org.cryptomator.domain.CloudFolder
import org.cryptomator.domain.CloudNode
import java.util.Date

sealed interface MegaNode : CloudNode {
	val hash: String
	override val cloud: Cloud?
	override val name: String
	override val path: String
	override val parent: MegaFolder?
}

data class MegaFolder(
	override val hash: String,
	override val name: String,
	override val path: String,
	override val parent: MegaFolder? = null,
	override val cloud: Cloud? = null
) : MegaNode, CloudFolder {
	override fun withCloud(cloud: Cloud?): CloudFolder = copy(cloud = cloud)
}

data class MegaFile(
	override val hash: String,
	override val name: String,
	override val path: String,
	override val parent: MegaFolder,
	override val cloud: Cloud? = null,
	override val size: Long? = null,
	override val modified: Date? = null
) : MegaNode, CloudFile

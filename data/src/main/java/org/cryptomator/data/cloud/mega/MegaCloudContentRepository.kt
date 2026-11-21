package org.cryptomator.data.cloud.mega

import android.content.Context
import dev.carlsen.mega.Mega
import dev.carlsen.mega.model.Node
import dev.carlsen.mega.util.CancellationToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.cryptomator.domain.MegaCloud
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.CancellationException
import org.cryptomator.domain.exception.CloudNodeAlreadyExistsException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.exception.authentication.WrongCredentialsException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.cryptomator.domain.usecases.cloud.DownloadState
import org.cryptomator.domain.usecases.cloud.Progress
import org.cryptomator.domain.usecases.cloud.UploadState
import java.io.File
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import javax.net.ssl.SSLException

class MegaCloudContentRepository(
	private val cloud: MegaCloud,
	private val mega: Mega,
	private val context: Context
) : CloudContentRepository<MegaCloud, MegaNode, MegaFolder, MegaFile> {

	private val pathToHashCache = Collections.synchronizedMap(
		object : LinkedHashMap<String, String>(128, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
				return size > 500
			}
		}
	)

	private val cacheDir: File?
		get() = try {
			context.cacheDir?.let {
				File(it, "mega_cache").apply { if (!exists()) mkdirs() }
			}
		} catch (e: Exception) {
			null
		}

	private fun pruneCacheIfNecessary() {
		val dir = cacheDir ?: return
		val files = dir.listFiles { _, name -> !name.endsWith(".tmp") } ?: return
		val totalSize = files.sumOf { it.length() }
		if (totalSize > MAX_TOTAL_CACHE_SIZE) {
			val sorted = files.sortedBy { it.lastModified() }
			var currentSize = totalSize
			for (f in sorted) {
				if (currentSize <= MAX_TOTAL_CACHE_SIZE * 0.7) break
				currentSize -= f.length()
				f.delete()
			}
		}
	}

	private inline fun <T> handleExceptions(block: () -> T): T {
		return try {
			block()
		} catch (e: BackendException) {
			throw e
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw CancellationException(e)
		} catch (e: UnknownHostException) {
			throw NetworkConnectionException(e)
		} catch (e: SocketException) {
			throw NetworkConnectionException(e)
		} catch (e: SocketTimeoutException) {
			throw NetworkConnectionException(e)
		} catch (e: SSLException) {
			throw NetworkConnectionException(e)
		} catch (e: dev.carlsen.mega.model.MegaException) {
			when (e.code) {
				-9 -> throw NoSuchCloudFileException(e.message ?: "Resource not found")
				-11, -15, -16 -> throw WrongCredentialsException(cloud)
				-3, -4, -18 -> throw NetworkConnectionException(e)
				-17 -> {
					val waitMatch = Regex("""(\d+)\s*(?:seconds?|secs?|s|hours?|hrs?|h|minutes?|mins?|m)""", RegexOption.IGNORE_CASE).find(e.message ?: "")
					val waitHint = if (waitMatch != null) " (retry in ${waitMatch.value})" else " (resets in ~4–6 hours)"
					throw FatalBackendException("MEGA transfer quota exceeded$waitHint. Free accounts have bandwidth limits (wait or upgrade account).")
				}
				else -> {
					val msg = e.message ?: ""
					if (msg.contains("401") || msg.contains("403") || msg.contains("Session expired", ignoreCase = true)) {
						throw WrongCredentialsException(cloud)
					}
					throw FatalBackendException(e)
				}
			}
		} catch (e: Exception) {
			val msg = e.message ?: ""
			if (msg.contains("401") || msg.contains("403") || msg.contains("Session expired", ignoreCase = true)) {
				throw WrongCredentialsException(cloud)
			}
			if (msg.contains("ENOTFOUND") || msg.contains("ECONNREFUSED") || msg.contains("timed out", ignoreCase = true)) {
				throw NetworkConnectionException(e)
			}
			throw FatalBackendException(e)
		}
	}

	private fun normalizePath(path: String): String {
		if (path == "/" || path.isEmpty()) return "/"
		val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
		return "/" + segments.joinToString("/")
	}

	private fun getRootNode(): Node {
		val root = mega.getFileSystem().root ?: throw WrongCredentialsException(cloud)
		pathToHashCache["/"] = root.hash
		return root
	}

	private fun findNodeByPath(path: String): Node? {
		val normalized = normalizePath(path)
		if (normalized == "/") return getRootNode()

		pathToHashCache[normalized]?.let { cachedHash ->
			val node = mega.getNodeByHash(cachedHash)
			val expectedName = normalized.substringAfterLast('/')
			if (node != null && node.name == expectedName) return node
			pathToHashCache.remove(normalized)
		}

		val segments = normalized.trim('/').split('/')
		var current: Node = getRootNode()
		var currentPath = ""
		for (segment in segments) {
			currentPath = if (currentPath.isEmpty()) "/$segment" else "$currentPath/$segment"
			val children = mega.getChildren(current)
			current = children.firstOrNull { it.name == segment } ?: return null
			pathToHashCache[currentPath] = current.hash
		}
		pathToHashCache[normalized] = current.hash
		return current
	}

	override fun root(cloud: MegaCloud): MegaFolder {
		val rootNode = getRootNode()
		return MegaFolder(
			hash = rootNode.hash,
			name = "",
			path = "/",
			parent = null,
			cloud = cloud
		)
	}

	override fun resolve(cloud: MegaCloud, path: String): MegaFolder {
		val normalized = normalizePath(path)
		if (normalized == "/") return root(cloud)
		val node = findNodeByPath(normalized)
		val segments = normalized.trim('/').split('/')
		val name = segments.last()
		val parentPath = normalized.substringBeforeLast('/', "")
		val parent = if (parentPath.isEmpty() || parentPath == "/") root(cloud) else resolve(cloud, parentPath)
		return MegaFolder(
			hash = node?.hash ?: "",
			name = name,
			path = normalized,
			parent = parent,
			cloud = cloud
		)
	}

	override fun file(parent: MegaFolder, name: String): MegaFile = file(parent, name, null)

	override fun file(parent: MegaFolder, name: String, size: Long?): MegaFile {
		val parentNode = if (parent.hash.isNotEmpty()) mega.getNodeByHash(parent.hash) else findNodeByPath(parent.path)
		val childNode = parentNode?.let { mega.getChildren(it) }?.firstOrNull { it.name == name }
		val newPath = if (parent.path == "/") "/$name" else "${parent.path}/$name"
		if (childNode != null) {
			pathToHashCache[newPath] = childNode.hash
		}
		return MegaFile(
			hash = childNode?.hash ?: "",
			name = name,
			path = newPath,
			parent = parent,
			cloud = cloud,
			size = size ?: childNode?.size,
			modified = childNode?.timestamp?.let { Date(it.toEpochMilliseconds()) }
		)
	}

	override fun folder(parent: MegaFolder, name: String): MegaFolder {
		val parentNode = if (parent.hash.isNotEmpty()) mega.getNodeByHash(parent.hash) else findNodeByPath(parent.path)
		val childNode = parentNode?.let { mega.getChildren(it) }?.firstOrNull { it.name == name }
		val newPath = if (parent.path == "/") "/$name" else "${parent.path}/$name"
		if (childNode != null) {
			pathToHashCache[newPath] = childNode.hash
		}
		return MegaFolder(
			hash = childNode?.hash ?: "",
			name = name,
			path = newPath,
			parent = parent,
			cloud = cloud
		)
	}

	override fun exists(node: MegaNode): Boolean = handleExceptions {
		if (node.hash.isNotEmpty()) {
			val found = mega.getNodeByHash(node.hash)
			if (found != null) return@handleExceptions true
		}
		val p = node.parent
		val parentNode = if (p != null && p.hash.isNotEmpty()) {
			mega.getNodeByHash(p.hash)
		} else {
			findNodeByPath(p?.path ?: "/")
		} ?: return@handleExceptions false
		val matched = mega.getChildren(parentNode).firstOrNull { it.name == node.name }
		if (matched != null) {
			pathToHashCache[node.path] = matched.hash
			return@handleExceptions true
		}
		return@handleExceptions false
	}

	override fun metadata(file: MegaFile): MegaFile? = handleExceptions {
		val parentNode = if (file.parent.hash.isNotEmpty()) {
			mega.getNodeByHash(file.parent.hash)
		} else {
			findNodeByPath(file.parent.path)
		} ?: return@handleExceptions null

		val node = mega.getChildren(parentNode).firstOrNull { it.name == file.name } ?: return@handleExceptions null
		pathToHashCache[file.path] = node.hash
		return@handleExceptions file.copy(
			hash = node.hash,
			size = node.size,
			modified = Date(node.timestamp.toEpochMilliseconds())
		)
	}

	override fun list(folder: MegaFolder): List<MegaNode> = handleExceptions {
		val parentNode = if (folder.hash.isNotEmpty()) {
			mega.getNodeByHash(folder.hash)
		} else {
			findNodeByPath(folder.path)
		} ?: throw NoSuchCloudFileException(folder.path)

		val children = mega.getChildren(parentNode)
		val oneHourAgo = System.currentTimeMillis() - 3600_000L
		val visibleChildren = ArrayList<Node>(children.size)

		for (child in children) {
			if (child.name.contains(".cryptomator_tmp_")) {
				val timeMs = child.timestamp.toEpochMilliseconds()
				if (timeMs > 0 && timeMs < oneHourAgo) {
					CoroutineScope(Dispatchers.IO).launch {
						try {
							mega.delete(child, true)
						} catch (ignored: Exception) {}
					}
				}
			} else {
				visibleChildren.add(child)
			}
		}

		if (folder.path == "/" || folder.path.count { it == '/' } <= 2) {
			CoroutineScope(Dispatchers.IO).launch {
				try {
					for (child in visibleChildren) {
						if (child.isFolder) {
							val subChildren = mega.getChildren(child)
							val subFolderPath = if (folder.path == "/") "/${child.name}" else "${folder.path}/${child.name}"
							for (subChild in subChildren) {
								if (!subChild.name.contains(".cryptomator_tmp_")) {
									val subChildPath = "$subFolderPath/${subChild.name}"
									pathToHashCache[subChildPath] = subChild.hash
								}
							}
						}
					}
				} catch (ignored: Exception) {}
			}
		}

		visibleChildren.map { child ->
			val childPath = if (folder.path == "/") "/${child.name}" else "${folder.path}/${child.name}"
			pathToHashCache[childPath] = child.hash
			if (child.isFolder) {
				MegaFolder(
					hash = child.hash,
					name = child.name,
					path = childPath,
					parent = folder,
					cloud = cloud
				)
			} else {
				MegaFile(
					hash = child.hash,
					name = child.name,
					path = childPath,
					parent = folder,
					cloud = cloud,
					size = child.size,
					modified = Date(child.timestamp.toEpochMilliseconds())
				)
			}
		}
	}

	override fun create(folder: MegaFolder): MegaFolder = handleExceptions {
		if (folder.name.isEmpty()) return@handleExceptions folder
		var currentFolder = folder
		val p = currentFolder.parent
		val parentNode: Node = if (p != null) {
			val existingParent = if (p.hash.isNotEmpty()) mega.getNodeByHash(p.hash) else findNodeByPath(p.path)
			if (existingParent == null) {
				val createdParent = create(p)
				currentFolder = currentFolder.copy(parent = createdParent)
				if (createdParent.hash.isNotEmpty()) mega.getNodeByHash(createdParent.hash) else findNodeByPath(createdParent.path)
			} else {
				existingParent
			}
		} else {
			getRootNode()
		} ?: throw FatalBackendException("Parent folder does not exist for ${folder.path}")

		val existingChild = mega.getChildren(parentNode).firstOrNull { it.isFolder && it.name == currentFolder.name }
		if (existingChild != null) {
			pathToHashCache[currentFolder.path] = existingChild.hash
			return@handleExceptions currentFolder.copy(hash = existingChild.hash)
		}

		val created = runBlocking {
			mega.createDir(currentFolder.name, parentNode)
		}
		pathToHashCache[currentFolder.path] = created.hash
		currentFolder.copy(hash = created.hash)
	}

	override fun move(source: MegaFolder, target: MegaFolder): MegaFolder = handleExceptions {
		val srcNode = (if (source.hash.isNotEmpty()) mega.getNodeByHash(source.hash) else findNodeByPath(source.path))
			?: throw NoSuchCloudFileException(source.path)
		val targetP = target.parent
		val destParent = (if (targetP != null && targetP.hash.isNotEmpty()) {
			mega.getNodeByHash(targetP.hash)
		} else {
			findNodeByPath(targetP?.path ?: "/")
		}) ?: throw FatalBackendException("Target parent missing")

		runBlocking {
			mega.move(srcNode, destParent)
			if (source.name != target.name) {
				mega.rename(srcNode, target.name)
			}
		}
		pathToHashCache.remove(source.path)
		pathToHashCache[target.path] = srcNode.hash
		target.copy(hash = srcNode.hash)
	}

	override fun move(source: MegaFile, target: MegaFile): MegaFile = handleExceptions {
		val srcNode = (if (source.hash.isNotEmpty()) mega.getNodeByHash(source.hash) else findNodeByPath(source.path))
			?: throw NoSuchCloudFileException(source.path)
		val destParent = (if (target.parent.hash.isNotEmpty()) {
			mega.getNodeByHash(target.parent.hash)
		} else {
			findNodeByPath(target.parent.path)
		}) ?: throw FatalBackendException("Target parent missing")

		runBlocking {
			mega.move(srcNode, destParent)
			if (source.name != target.name) {
				mega.rename(srcNode, target.name)
			}
		}
		pathToHashCache.remove(source.path)
		pathToHashCache[target.path] = srcNode.hash
		target.copy(hash = srcNode.hash)
	}

	override fun read(file: MegaFile, data: OutputStream, progressAware: ProgressAware<DownloadState>) = handleExceptions {
		val node = if (file.hash.isNotEmpty()) {
			mega.getNodeByHash(file.hash)
		} else {
			findNodeByPath(file.path)
		} ?: throw NoSuchCloudFileException(file.path)

		val fileSize = file.size ?: node.size
		val dir = cacheDir
		val cachedFile = if (dir != null && node.hash.isNotEmpty() && fileSize in 1..MAX_CACHE_FILE_SIZE) {
			File(dir, "${node.hash}_$fileSize")
		} else null

		if (cachedFile != null && cachedFile.exists() && cachedFile.length() == fileSize) {
			progressAware.onProgress(Progress.started(DownloadState.download(file)))
			cachedFile.inputStream().buffered().use { input ->
				val buffer = ByteArray(8192)
				var bytesRead: Int
				var transferred = 0L
				while (input.read(buffer).also { bytesRead = it } != -1) {
					if (Thread.currentThread().isInterrupted) {
						throw CancellationException()
					}
					data.write(buffer, 0, bytesRead)
					transferred += bytesRead
					progressAware.onProgress(
						Progress.progress(DownloadState.download(file))
							.between(0L)
							.and(fileSize)
							.withValue(transferred)
					)
				}
			}
			try {
				data.flush()
			} catch (ignored: Exception) {}
			cachedFile.setLastModified(System.currentTimeMillis())
			progressAware.onProgress(Progress.completed(DownloadState.download(file)))
			return@handleExceptions
		}

		val cancellationToken = CancellationToken()
		progressAware.onProgress(Progress.started(DownloadState.download(file)))

		val tempCacheFile = if (cachedFile != null && dir != null) {
			File(dir, "${node.hash}_${fileSize}.tmp")
		} else null

		try {
			if (tempCacheFile != null) {
				val fileOut = tempCacheFile.outputStream()
				val teeOut = object : OutputStream() {
					override fun write(b: Int) {
						data.write(b)
						fileOut.write(b)
					}
					override fun write(b: ByteArray, off: Int, len: Int) {
						data.write(b, off, len)
						fileOut.write(b, off, len)
					}
					override fun flush() {
						data.flush()
						fileOut.flush()
					}
					override fun close() {
						fileOut.close()
					}
				}
				val sink = teeOut.asSink().buffered()
				runBlocking {
					mega.downloadFile(
						node,
						sink,
						cancellationToken,
						onProgress = { transferred, total ->
							if (Thread.currentThread().isInterrupted) {
								cancellationToken.cancel()
							}
							progressAware.onProgress(
								Progress.progress(DownloadState.download(file))
									.between(0L)
									.and(total)
									.withValue(transferred)
							)
						}
					)
				}
				try {
					sink.flush()
					teeOut.flush()
					fileOut.close()
				} catch (ignored: Exception) {}

				if (tempCacheFile.length() == fileSize) {
					if (cachedFile != null && tempCacheFile.renameTo(cachedFile)) {
						pruneCacheIfNecessary()
					} else {
						tempCacheFile.delete()
					}
				} else {
					tempCacheFile.delete()
				}
			} else {
				val sink = data.asSink().buffered()
				runBlocking {
					mega.downloadFile(
						node,
						sink,
						cancellationToken,
						onProgress = { transferred, total ->
							if (Thread.currentThread().isInterrupted) {
								cancellationToken.cancel()
							}
							progressAware.onProgress(
								Progress.progress(DownloadState.download(file))
									.between(0L)
									.and(total)
									.withValue(transferred)
							)
						}
					)
				}
			}
			try {
				data.flush()
			} catch (ignored: Exception) {}
			progressAware.onProgress(Progress.completed(DownloadState.download(file)))
		} catch (e: Exception) {
			tempCacheFile?.delete()
			if (cancellationToken.isCancellationRequested()) {
				throw CancellationException(e)
			}
			throw e
		}
	}

	override fun write(
		file: MegaFile,
		data: DataSource,
		progressAware: ProgressAware<UploadState>,
		replace: Boolean,
		size: Long
	): MegaFile = handleExceptions {
		var parentFolder = file.parent
		var parentNode = if (parentFolder.hash.isNotEmpty()) {
			mega.getNodeByHash(parentFolder.hash)
		} else {
			findNodeByPath(parentFolder.path)
		}
		if (parentNode == null) {
			parentFolder = create(parentFolder)
			parentNode = if (parentFolder.hash.isNotEmpty()) mega.getNodeByHash(parentFolder.hash) else findNodeByPath(parentFolder.path)
		}
		val targetParentNode = parentNode ?: throw FatalBackendException("Parent folder does not exist: ${parentFolder.path}")

		val existing = mega.getChildren(targetParentNode).firstOrNull { it.name == file.name }
		if (existing != null && !replace) {
			throw CloudNodeAlreadyExistsException(file.path)
		}

		val cancellationToken = CancellationToken()
		progressAware.onProgress(Progress.started(UploadState.upload(file)))

		val uploadName = if (existing != null) "${file.name}.cryptomator_tmp_${java.util.UUID.randomUUID().toString().take(8)}" else file.name
		val inputStream = data.open(context) ?: throw FatalBackendException("Cannot open data stream")

		val uploadedNode: Node = try {
			inputStream.use { input ->
				val source = input.asSource().buffered()
				runBlocking {
					mega.uploadFile(
						destNode = targetParentNode,
						name = uploadName,
						fileSize = if (size >= 0L) size else 0L,
						fileInputSource = source,
						cancellationToken = cancellationToken,
						onProgress = { transferred, total ->
							if (Thread.currentThread().isInterrupted) {
								cancellationToken.cancel()
							}
							progressAware.onProgress(
								Progress.progress(UploadState.upload(file))
									.between(0L)
									.and(total)
									.withValue(transferred)
							)
						}
					)
				}
			}
		} catch (e: Exception) {
			if (cancellationToken.isCancellationRequested()) {
				throw CancellationException(e)
			}
			throw e
		}

		if (existing != null) {
			runBlocking {
				try {
					mega.delete(existing, true)
				} catch (ignored: Exception) {}
				mega.rename(uploadedNode, file.name)
			}
			if (existing.hash.isNotEmpty()) {
				cacheDir?.listFiles { _, name -> name.startsWith(existing.hash) }?.forEach { it.delete() }
			}
		}

		pathToHashCache[file.path] = uploadedNode.hash
		progressAware.onProgress(Progress.completed(UploadState.upload(file)))
		file.copy(
			hash = uploadedNode.hash,
			size = uploadedNode.size,
			modified = Date(uploadedNode.timestamp.toEpochMilliseconds())
		)
	}

	override fun delete(node: MegaNode): Unit = handleExceptions {
		val targetNode = if (node.hash.isNotEmpty()) {
			mega.getNodeByHash(node.hash)
		} else {
			findNodeByPath(node.path)
		} ?: return@handleExceptions

		runBlocking {
			mega.delete(targetNode, true)
		}
		pathToHashCache.remove(node.path)
		if (targetNode.hash.isNotEmpty()) {
			cacheDir?.listFiles { _, name -> name.startsWith(targetNode.hash) }?.forEach { it.delete() }
		}
	}

	override fun checkAuthenticationAndRetrieveCurrentAccount(cloud: MegaCloud): String {
		val baseEmail = (cloud.username() ?: "MEGA User").substringBefore(" (")
		return try {
			val quota = runBlocking { mega.getQuota() }
			val usedGb = quota.spaceUsedBytes.toLong().toDouble() / (1024.0 * 1024.0 * 1024.0)
			val totalGb = quota.spaceCapacityBytes.toLong().toDouble() / (1024.0 * 1024.0 * 1024.0)
			"$baseEmail (%.1f GB / %.1f GB)".format(Locale.US, usedGb, totalGb)
		} catch (e: Exception) {
			cloud.username() ?: "MEGA User"
		}
	}

	override fun logout(cloud: MegaCloud) {
		runBlocking {
			try {
				mega.logout()
			} catch (ignored: Exception) {}
		}
		pathToHashCache.clear()
		try {
			cacheDir?.deleteRecursively()
		} catch (ignored: Exception) {}
		MegaClientManager.clear(cloud)
	}

	companion object {
		private const val MAX_CACHE_FILE_SIZE = 50L * 1024 * 1024 // 50 MB
		private const val MAX_TOTAL_CACHE_SIZE = 150L * 1024 * 1024 // 150 MB
	}
}

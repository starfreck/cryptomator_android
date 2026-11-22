# Cryptomator Android Fork Features

This repository extends official [Cryptomator Android](https://github.com/cryptomator/android) with native **MEGA (`mega.nz`) cloud support**, **on-the-fly media streaming**, and **enhanced cache & multi-device sync management**.

---

## 1. Native MEGA Cloud Storage Support (`mega.nz`)

Native integration with MEGA cloud accounts using `dev.carlsen.mega:mega:1.0.0-beta08`.

- **Authentication**:
  - Email, password, and optional two-factor authentication (TOTP 2FA) login dialog (`EnterMegaCredentialsDialog.kt`).
  - Session resumption using persisted access tokens.
  - Server-side session revocation: deleting a MEGA cloud connection revokes active session tokens via `dispatchingCloudContentRepository.logout(cloud)`.
- **Cloud Operations (`MegaCloudContentRepository.kt`)**:
  - Complete filesystem support: folder tree enumeration, directory creation, recursive deletion, moves, file renames, and live storage quota inspection (`X.X GB / Y.Y GB used`).
  - Resilient network configuration: 60-second request timeout, 30-second connection timeout, and 5 retries in `MegaClientManager.kt`.
- **Sequential Chunking Fix (`maxConcurrentChunks = 1`)**:
  - Eliminates upstream Carlsen library race conditions where concurrent worker coroutines scrambled stream bytes across non-FIFO chunk locks. All uploads and downloads now transfer strictly sequentially, preventing ciphertext corruption (`Content tag mismatch` / `Unauthentic ciphertext`).
- **UI & Branding**:
  - Official high-resolution MEGA vector glyphs (`mega.xml`).
  - Custom Cryptomator vault ring badges (`mega_vault.xml`, `mega_vault_selected.xml`).
  - Added to Cloud Services selection, Authenticate Cloud, and Cloud Settings menus.

---

## 2. On-The-Fly Pipelined Media Streaming

Watch videos stored in encrypted vaults instantly without waiting for 100% of the file to download and decrypt.

- **Storage Access Framework (SAF) AppFuse Proxy**:
  - Custom `StreamingMediaProxyCallback` exposes a virtual file descriptor to external players (Photos, VLC, DocumentsUI, etc.).
  - Pipelined decryption: downloads encrypted chunks from the cloud, decrypts Cryptomator headers and blocks on-the-fly, and serves bytes directly to the media player.
  - Initial buffering takes **1 to 3 seconds** for faststart / web-optimized videos (where index atom `moov` is placed at the beginning of the file).
- **Zero-Latency Partial Read Unblocking**:
  - `StreamingMediaProxyCallback.onRead` unblocks immediately when bytes at the requested offset become available on disk, rather than waiting for large block chunks.
- **Refcount Sniffer Protection (`DocumentContentCache.kt`)**:
  - Android media frameworks open and close probe descriptors to inspect container formats. Dual-claim refcounting prevents early file descriptor closures from aborting active background streaming downloads.
- **Auto-Lock Guard**:
  - `DocumentContentCache.hasTransfers()` tracks active streaming sessions, preventing graceful vault auto-locks while playback is actively in progress.
- **Low-Latency Sink Flushing**:
  - Immediate sink flushes in `MegaCloudContentRepository` download callbacks ensure decrypted bytes reach the local consumer pipeline without buffering delays.

---

## 3. Dynamic Cache Management & Multi-Device Sync

Enhanced local storage protection and multi-device consistency.

- **Auto-Evict Decrypted Cache on Vault Lock**:
  - Decrypted file copies are automatically purged from disk (`no_backup/documents-provider/`) and Room DB (`CACHED_FILE_ENTITY`) when all vaults lock (`unlocked == 0`).
  - Ensures private media and sensitive files do not remain permanently on device storage after locking the vault.
  - In-flight uploads and externally pinned documents remain safely protected.
- **Always-Fresh Cloud Listing on Resume**:
  - `BrowseFilesPresenter` triggers a forced cloud folder re-enumeration (`forceRefresh = true`) whenever returning to the app or switching back from external players.
  - Changes made by secondary devices (file additions, deletions, renames) reflect immediately upon opening the app.
- **Reduced Metadata Refresh TTL**:
  - Decreased `REFRESH_INTERVAL_MILLIS` in `DocumentMetadataCache.kt` from 30 seconds to **5 seconds**, keeping file listings fresh across active multi-device sessions.

---

## Architecture & Code Map

| Feature Area | Key Classes & Files |
|---|---|
| **Domain Models** | `domain/src/main/java/org/cryptomator/domain/MegaCloud.kt`<br>`domain/src/main/java/org/cryptomator/domain/CloudType.kt` |
| **MEGA Client & Repository** | `data/src/main/java/org/cryptomator/data/cloud/mega/MegaClientManager.kt`<br>`data/src/main/java/org/cryptomator/data/cloud/mega/MegaCloudContentRepository.kt`<br>`data/src/main/java/org/cryptomator/data/cloud/mega/MegaNodes.kt` |
| **Cache & Transfer Lifecycles** | `data/src/main/java/org/cryptomator/data/documents/DocumentContentCache.kt`<br>`data/src/main/java/org/cryptomator/data/documents/DocumentMetadataCache.kt` |
| **Streaming Documents Proxy** | `presentation/src/main/java/org/cryptomator/presentation/docprovider/CryptomatorDocumentsProvider.kt`<br>`presentation/src/main/java/org/cryptomator/presentation/docprovider/StreamingMediaProxyCallback.kt` |
| **UI Dialogs & Presenters** | `presentation/src/main/java/org/cryptomator/presentation/ui/dialog/EnterMegaCredentialsDialog.kt`<br>`presentation/src/main/java/org/cryptomator/presentation/presenter/BrowseFilesPresenter.kt`<br>`presentation/src/main/java/org/cryptomator/presentation/presenter/AuthenticateCloudPresenter.kt` |
| **Branding Assets** | `presentation/src/main/res/drawable/mega.xml`<br>`presentation/src/main/res/drawable/mega_vault.xml`<br>`presentation/src/main/res/drawable/mega_vault_selected.xml` |

---

## Verification & Tests

To execute the test suite across all modules:

```bash
./gradlew testPlaystoreDebugUnitTest
```

To assemble the debug APK:

```bash
./gradlew assemblePlaystoreDebug
```

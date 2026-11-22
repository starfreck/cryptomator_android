# AI Agent Guide: Cryptomator Android Fork (MEGA Cloud Support)

This guide provides instructions and architectural context for AI agents maintaining this fork.

## 1. Fork Overview & Goals

- **Upstream Repository**: `https://github.com/cryptomator/android.git` (`upstream`)
- **Fork Repository**: `https://github.com/starfreck/cryptomator_android_mega_io.git` (`origin`)
- **Primary Goal**: Provide native MEGA (`mega.nz`) cloud storage support in Cryptomator Android with minimal maintenance overhead when rebasing against upstream updates.

---

## 2. Git Branching & Maintenance Strategy

To keep maintenance trivial and avoid merge conflicts with upstream:

1. **`develop` branch**:
   - Must remain **100% clean and identical to `upstream/develop`**.
   - NEVER commit custom features or fork-specific changes directly onto `develop`.

2. **`feature/mega-support` branch**:
   - Contains all MEGA cloud integration commits.
   - Kept on top of `develop` via git rebase.

### How to Sync with Upstream

When upstream releases updates, follow this exact procedure:

```bash
# 1. Update local develop from upstream
git checkout develop
git fetch upstream develop
git merge upstream/develop
git push origin develop

# 2. Rebase feature branch on top of updated develop
git checkout feature/mega-support
git rebase develop

# 3. Verify tests and build
./gradlew testPlaystoreDebugUnitTest
./gradlew assemblePlaystoreDebug

# 4. Push updated feature branch
git push --force-with-lease origin feature/mega-support
```

---

## 3. Architecture & Code Structure

The MEGA integration is designed surgically using factory patterns so the blast radius on existing Cryptomator code is minimal.

### Modified / Core Extension Points
- **Domain**:
  - `domain/src/main/java/org/cryptomator/domain/CloudType.kt`: Added `MEGA`.
  - `domain/src/main/java/org/cryptomator/domain/MegaCloud.kt`: Domain model for MEGA account connection (holds username, session token, quota info).
- **Data Layer**:
  - `data/build.gradle`: Dependency `dev.carlsen.mega:mega:1.0.0-beta08`.
  - `data/src/main/java/org/cryptomator/data/cloud/mega/`:
    - `MegaClientManager.kt`: Manages `MegaApiAndroid` instances, sessions, credentials, and authentication state.
    - `MegaCloudContentRepository.kt`: Implements `CloudContentRepository` (file listing, streaming read/write, directory creation, deletion, moves, quota lookup).
    - `MegaCloudContentRepositoryFactory.kt`: Injected by Dagger into `CloudContentRepositoryFactories`.
    - `MegaNodes.kt`: Path-to-MegaNode resolution and cache utilities.
  - `data/src/main/java/org/cryptomator/data/repository/CloudRepositoryImpl.java`: Calls `dispatchingCloudContentRepository.logout(cloud)` on deletion to revoke session server-side.
  - `data/src/main/java/org/cryptomator/data/db/mappers/CloudEntityMapper.java`: Maps database entity to/from `MegaCloud`.
- **Presentation Layer**:
  - `presentation/src/main/java/org/cryptomator/presentation/model/CloudTypeModel.kt`: Associated with `R.drawable.mega`, `R.drawable.mega_vault`, `R.drawable.mega_vault_selected`.
  - `presentation/src/main/java/org/cryptomator/presentation/model/MegaCloudModel.kt`: Presentation wrapper.
  - `presentation/src/main/java/org/cryptomator/presentation/ui/dialog/EnterMegaCredentialsDialog.kt`: Clean dialog for email, password, and optional 2FA code.
  - Presenters:
    - `AuthenticateCloudPresenter.kt`: Handles login and creates cloud connection entity.
    - `CloudConnectionListPresenter.kt`: Lists connections and fetches live quota (`X.X GB / Y.Y GB used`).
    - `ChooseCloudServicePresenter.kt`: Displays MEGA at the end of the cloud service list.
    - `CloudSettingsPresenter.kt`: Displays MEGA at the end of settings services list.
  - Drawables:
    - `presentation/src/main/res/drawable/mega.xml`: Real official MEGA red logo.
    - `presentation/src/main/res/drawable/mega_vault.xml`: Cryptomator green vault ring with MEGA glyph.
    - `presentation/src/main/res/drawable/mega_vault_selected.xml`: Selected vault ring with MEGA glyph.

---

## 4. Verification & Testing

Always verify before committing or pushing changes:

```bash
# Run unit tests across all modules
./gradlew testPlaystoreDebugUnitTest

# Build APK
./gradlew assemblePlaystoreDebug
```

Unit test suites for MEGA:
- `data/src/test/java/org/cryptomator/data/cloud/mega/` (`MegaClientManagerTest`, `MegaCloudContentRepositoryTest`, `MegaNodesTest`)
- `domain/src/test/java/org/cryptomator/domain/MegaCloudTest.kt`
- `presentation/src/test/java/org/cryptomator/presentation/model/CloudTypeModelTest.kt`
- `presentation/src/test/java/org/cryptomator/presentation/presenter/ChooseCloudServicePresenterTest.kt`
- `presentation/src/test/java/org/cryptomator/presentation/presenter/CloudSettingsPresenterTest.kt`

---

## 5. Security & Privacy Rules

- **NEVER** commit real user credentials, email addresses, passwords, or session tokens.
- Unit tests must only use synthetic mock accounts (e.g. `alice@example.com`, `bob@example.com`).
- Ensure `dispatchingCloudContentRepository.logout(cloud)` is preserved whenever a cloud connection is deleted so that tokens are revoked on MEGA's authentication servers.

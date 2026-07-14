# TLang Release Process & Versioning Consistency

This document outlines the standard operating procedure for cutting a new release of TLang, ensuring that the version tag, installer versions, and the version reported by the CLI (`tlang version`) are kept in sync.

---

## Single Source of Truth
The single source of truth for the TLang version is `project.version` in `TLang/build.gradle`.
- When the application compiles, this version string is embedded into `version.properties` and read by the CLI (`VersionCommand`).
- The `jpackage` packaging tasks use this same Gradle version to determine the installer output filenames and metadata.

---

## Release Workflow Trigger
Releases are automated via GitHub Actions:
- A push to a tag matching `v*` (e.g. `v0.1.0`) triggers the **Release Packaging** workflow (`.github/workflows/release.yml`).
- This workflow builds the installers on three platforms (Linux, macOS, Windows) and runs the smoke/verification tests on all of them.
- If and only if **all three** platforms successfully compile and pass their verification suites, the workflow creates a GitHub Release and uploads the `.deb`, `.pkg`, and `.msi` installers as release assets.

---

## Step-by-Step Instructions to Cut a Release

Follow these steps to release a new version of TLang:

### 1. Update the Version in Gradle
Open `TLang/build.gradle` and change the version to the target release version (e.g., from `1.0-SNAPSHOT` to `0.1.0`):
```groovy
// In TLang/build.gradle
version = '0.1.0'
```

### 2. Commit and Push to Main
Commit the version bump and push it to the remote repository:
```bash
git add TLang/build.gradle
git commit -m "Bump version to 0.1.0 for release"
git push origin main
```

### 3. Create and Push the Release Tag
Create a git tag corresponding to the version (prefixed with `v`), then push it to GitHub:
```bash
git tag v0.1.0
git push origin v0.1.0
```
This push will trigger the release job. You can monitor the progress under the **Actions** tab of the GitHub repository.

### 4. Post-Release: Bump to next Development Snapshot
After the release workflow successfully completes and publishes the release, update `TLang/build.gradle` to the next development snapshot version (e.g., `0.1.1-SNAPSHOT`) to prevent development builds from reporting as the released version:
```groovy
// In TLang/build.gradle
version = '0.1.1-SNAPSHOT'
```
Commit and push this change to `main`:
```bash
git add TLang/build.gradle
git commit -m "Bump version to 0.1.1-SNAPSHOT for development"
git push origin main
```

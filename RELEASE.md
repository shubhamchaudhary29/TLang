# Releasing TLang

`gradle.properties` is the version source of truth. Development currently uses `0.1.1-SNAPSHOT`; the CLI and distributions receive that value through resource processing.

To prepare a release, change `version` to a non-snapshot semantic version, run `./gradlew clean check distributionSmokeTest`, commit it, then create an annotated `v<version>` tag. The release workflow passes the tag in `RELEASE_TAG`; `verifyVersion` rejects a leading `v` in the project version, malformed tags, snapshots, and tag/project mismatches. Do not retag an existing release.

After the release, bump `gradle.properties` to the next `-SNAPSHOT` version in a follow-up pull request.

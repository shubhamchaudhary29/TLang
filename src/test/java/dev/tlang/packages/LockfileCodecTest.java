package dev.tlang.packages;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LockfileCodecTest {
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String COMMIT = "a".repeat(40);

    @Test void deterministicOrderingAndRoundTrip() {
        PackageLock lock = new PackageLock(1, H1, Map.of(
            "z", PackageRecord.path("z", "../z", H2, List.of()),
            "a", PackageRecord.git("a", "https://example.test/a.git", "main", COMMIT, H1, List.of("z"))));
        String first = LockfileCodec.write(lock);
        String second = LockfileCodec.write(lock);
        assertEquals(first, second);
        assertTrue(first.indexOf("name = \"a\"") < first.indexOf("name = \"z\""));
        assertEquals(lock, LockfileCodec.parse(first, "tlang.lock"));
    }

    @Test void persistsExactGitSha() {
        PackageLock parsed = LockfileCodec.parse(LockfileCodec.write(new PackageLock(1, H1, Map.of(
            "a", PackageRecord.git("a", "https://example.test/a.git", "v1", COMMIT, H2, List.of())))), "lock");
        assertEquals(COMMIT, parsed.packages().get("a").commit());
    }

    @Test void rejectsUnsupportedMalformedAndTamperedLockfiles() {
        String valid = LockfileCodec.write(new PackageLock(1, H1, Map.of(
            "a", PackageRecord.path("a", "../a", H2, List.of()))));
        assertThrows(PackageException.class, () -> LockfileCodec.parse(valid.replace("lock-version = 1", "lock-version = 9"), "lock"));
        assertThrows(PackageException.class, () -> LockfileCodec.parse(valid.replace("path = \"../a\"", "path = \"../../evil\""), "lock"));
        assertThrows(PackageException.class, () -> LockfileCodec.parse("not a lockfile", "lock"));
        assertThrows(PackageException.class, () -> LockfileCodec.parse(valid + valid.substring(valid.indexOf("[[package]]")), "lock"));
    }

    @Test void rejectsMissingRecordsAndCycles() {
        PackageLock missing = new PackageLock(1, H1, Map.of(
            "a", PackageRecord.path("a", "../a", H2, List.of("missing"))));
        assertTrue(assertThrows(PackageException.class,
            () -> LockfileCodec.parse(LockfileCodec.write(missing), "lock")).getMessage().contains("missing"));
        PackageLock cycle = new PackageLock(1, H1, Map.of(
            "a", PackageRecord.path("a", "../a", H1, List.of("b")),
            "b", PackageRecord.path("b", "../b", H2, List.of("a"))));
        assertTrue(assertThrows(PackageException.class,
            () -> LockfileCodec.parse(LockfileCodec.write(cycle), "lock")).getMessage().contains("cycle"));
    }

    @Test void detectsManifestMismatchAndDirectSourceTampering() {
        PackageManifest manifest = new PackageManifest("app", "1.0.0", Map.of(
            "a", DependencySpec.path("a", "../a")));
        PackageLock wrongDigest = new PackageLock(1, H1, Map.of(
            "a", PackageRecord.path("a", "../a", H2, List.of())));
        assertThrows(PackageException.class, () -> LockfileCodec.verifyManifest(manifest, wrongDigest));
        PackageLock wrongSource = new PackageLock(1, PackageHashes.manifest(manifest), Map.of(
            "a", PackageRecord.path("a", "../other", H2, List.of())));
        assertThrows(PackageException.class, () -> LockfileCodec.verifyManifest(manifest, wrongSource));

        PackageManifest empty = new PackageManifest("app", "1.0.0", Map.of());
        PackageLock orphan = new PackageLock(1, PackageHashes.manifest(empty), Map.of(
            "a", PackageRecord.path("a", "../a", H2, List.of())));
        assertTrue(assertThrows(PackageException.class,
            () -> LockfileCodec.verifyManifest(empty, orphan)).getMessage().contains("unreachable"));
    }
}

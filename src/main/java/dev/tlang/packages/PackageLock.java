package dev.tlang.packages;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable validated dependency graph from tlang.lock. */
public record PackageLock(int formatVersion, String manifestSha256, Map<String, PackageRecord> packages) {
    public static final int CURRENT_VERSION = 1;

    public PackageLock {
        packages = Collections.unmodifiableMap(new TreeMap<>(packages));
    }
}

package dev.tlang.packages;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A resolved lock graph plus the verified source root for each package. */
record DependencyResolution(PackageLock lock, Map<String, Path> sourceRoots) {
    DependencyResolution {
        sourceRoots = Collections.unmodifiableMap(new TreeMap<>(sourceRoots));
    }
}

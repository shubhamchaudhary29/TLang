package dev.tlang.packages;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Validated immutable contents of a tlang.toml file. */
public record PackageManifest(String name, String version, Map<String, DependencySpec> dependencies) {
    public PackageManifest {
        dependencies = Collections.unmodifiableMap(new TreeMap<>(dependencies));
    }

    public PackageManifest withDependency(DependencySpec dependency) {
        TreeMap<String, DependencySpec> updated = new TreeMap<>(dependencies);
        updated.put(dependency.name(), dependency);
        return new PackageManifest(name, version, updated);
    }

    public PackageManifest withoutDependency(String dependencyName) {
        TreeMap<String, DependencySpec> updated = new TreeMap<>(dependencies);
        updated.remove(dependencyName);
        return new PackageManifest(name, version, updated);
    }
}

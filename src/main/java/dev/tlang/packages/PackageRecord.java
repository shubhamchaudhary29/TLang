package dev.tlang.packages;

import java.util.Collections;
import java.util.List;

/** One completely resolved dependency stored in tlang.lock. */
public record PackageRecord(
        String name,
        DependencySpec.SourceType sourceType,
        String location,
        String requestedRevision,
        String commit,
        String contentSha256,
        List<String> dependencies) {
    public PackageRecord {
        dependencies = Collections.unmodifiableList(dependencies.stream().sorted().toList());
    }

    public static PackageRecord path(String name, String path, String contentSha256, List<String> dependencies) {
        return new PackageRecord(name, DependencySpec.SourceType.PATH, path, null, null,
            contentSha256, dependencies);
    }

    public static PackageRecord git(String name, String repository, String revision, String commit,
                                     String contentSha256, List<String> dependencies) {
        return new PackageRecord(name, DependencySpec.SourceType.GIT, repository, revision, commit,
            contentSha256, dependencies);
    }
}

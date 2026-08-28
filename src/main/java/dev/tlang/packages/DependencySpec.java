package dev.tlang.packages;

/** One dependency declaration from a manifest. */
public record DependencySpec(String name, SourceType sourceType, String location, String revision) {
    public enum SourceType { PATH, GIT }

    public static DependencySpec path(String name, String path) {
        return new DependencySpec(name, SourceType.PATH, path, null);
    }

    public static DependencySpec git(String name, String repository, String revision) {
        return new DependencySpec(name, SourceType.GIT, repository, revision);
    }
}

package dev.tlang.packages;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic resolver for local and Git package graphs. */
final class DependencyResolver {
    private enum State { VISITING, VISITED }

    private final ProjectLayout project;
    private final GitPackageStore git;
    private final PackageLock previous;
    private final boolean update;
    private final boolean offline;
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, String> identities = new HashMap<>();
    private final Map<String, PackageRecord> records = new TreeMap<>();
    private final Map<String, Path> roots = new TreeMap<>();
    private final Deque<String> stack = new ArrayDeque<>();

    DependencyResolver(ProjectLayout project, PackageLock previous, boolean update, boolean offline) {
        this.project = project;
        this.git = new GitPackageStore(project);
        this.previous = previous;
        this.update = update;
        this.offline = offline;
    }

    DependencyResolution resolve(PackageManifest rootManifest) {
        String rootIdentity;
        try { rootIdentity = "path:" + project.root().toRealPath(); }
        catch (java.io.IOException e) { throw new PackageException("could not canonicalize project root", e); }
        identities.put(rootManifest.name(), rootIdentity);
        states.put(rootManifest.name(), State.VISITING);
        stack.addLast(rootManifest.name());
        for (DependencySpec dependency : rootManifest.dependencies().values()) {
            visit(dependency, project.root());
        }
        stack.removeLast();
        states.put(rootManifest.name(), State.VISITED);
        PackageLock lock = new PackageLock(PackageLock.CURRENT_VERSION,
            PackageHashes.manifest(rootManifest), records);
        return new DependencyResolution(lock, roots);
    }

    private void visit(DependencySpec requested, Path ownerRoot) {
        ResolvedSource source = source(requested, ownerRoot);
        String existingIdentity = identities.putIfAbsent(requested.name(), source.identity());
        if (existingIdentity != null && !existingIdentity.equals(source.identity())) {
            throw new PackageException("dependency '" + requested.name() + "' resolves from conflicting sources");
        }
        State state = states.get(requested.name());
        if (state == State.VISITING) throw cycle(requested.name());
        if (state == State.VISITED) return;

        states.put(requested.name(), State.VISITING);
        stack.addLast(requested.name());
        Path manifestPath = source.root().resolve("tlang.toml");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new PackageException("dependency '" + requested.name() + "' has no valid tlang.toml");
        }
        PackageManifest manifest = ManifestCodec.parse(
            PackageFiles.read(manifestPath, "dependency manifest"), manifestPath.toString());
        if (!manifest.name().equals(requested.name())) {
            throw new PackageException("dependency name '" + requested.name() + "' does not match package name '" + manifest.name() + "'");
        }
        Path entry = source.root().resolve(requested.name() + ".tiny");
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
            throw new PackageException("dependency '" + requested.name() + "' is missing entry module '" + requested.name() + ".tiny'");
        }
        for (DependencySpec dependency : manifest.dependencies().values()) visit(dependency, source.root());

        List<String> edges = new ArrayList<>(manifest.dependencies().keySet());
        PackageRecord record = requested.sourceType() == DependencySpec.SourceType.PATH
            ? PackageRecord.path(requested.name(), project.lockedPath(source.root()), source.contentSha256(), edges)
            : PackageRecord.git(requested.name(), requested.location(), requested.revision(),
                source.commit(), source.contentSha256(), edges);
        records.put(requested.name(), record);
        roots.put(requested.name(), source.root());
        stack.removeLast();
        states.put(requested.name(), State.VISITED);
    }

    private ResolvedSource source(DependencySpec dependency, Path ownerRoot) {
        if (dependency.sourceType() == DependencySpec.SourceType.PATH) {
            Path root = project.resolveLocalPath(ownerRoot, dependency.location(), dependency.name());
            String hash = PackageHashes.tree(root);
            return new ResolvedSource(root, "path:" + root, null, hash);
        }
        PackageRecord pin = previous == null ? null : previous.packages().get(dependency.name());
        boolean usablePin = !update && pin != null
            && pin.sourceType() == DependencySpec.SourceType.GIT
            && pin.location().equals(dependency.location())
            && pin.requestedRevision().equals(dependency.revision());
        if (usablePin) {
            Path root = git.acquireLocked(pin, offline);
            return new ResolvedSource(root, "git:" + pin.location() + "@" + pin.commit(),
                pin.commit(), pin.contentSha256());
        }
        if (offline) throw new PackageException("offline mode cannot resolve uncached Git dependency '" + dependency.name() + "'");
        GitPackageStore.GitSource result = git.resolve(dependency.name(), dependency.location(), dependency.revision());
        return new ResolvedSource(result.root(), "git:" + dependency.location() + "@" + result.commit(),
            result.commit(), result.contentSha256());
    }

    private PackageException cycle(String repeated) {
        List<String> cycle = new ArrayList<>();
        boolean include = false;
        for (String name : stack) {
            if (name.equals(repeated)) include = true;
            if (include) cycle.add(name);
        }
        cycle.add(repeated);
        return new PackageException("dependency cycle detected:\n" + String.join(" -> ", cycle));
    }

    private record ResolvedSource(Path root, String identity, String commit, String contentSha256) {}
}

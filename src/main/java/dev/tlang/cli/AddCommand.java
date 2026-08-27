package dev.tlang.cli;

import dev.tlang.packages.DependencySpec;
import dev.tlang.packages.ManifestCodec;
import dev.tlang.packages.PackageException;
import dev.tlang.packages.PackageLock;
import dev.tlang.packages.PackageManager;
import java.nio.file.Path;

public final class AddCommand implements Command {
    @Override public void execute(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException(
            "Usage: tlang add <name> --path <path>\n   or: tlang add <name> --git <url> --rev <revision>");
        String name = args[0];
        if (!ManifestCodec.DEPENDENCY_NAME.matcher(name).matches()) throw new PackageException("invalid dependency name '" + name + "'");
        DependencySpec dependency;
        if (args.length == 3 && args[1].equals("--path")) {
            dependency = DependencySpec.path(name, args[2]);
        } else if (args.length == 5 && args[1].equals("--git") && args[3].equals("--rev")) {
            dependency = DependencySpec.git(name, args[2], args[4]);
        } else {
            throw new IllegalArgumentException(
                "Usage: tlang add <name> --path <path>\n   or: tlang add <name> --git <url> --rev <revision>");
        }
        PackageLock lock = new PackageManager().add(Path.of("."), dependency);
        System.out.println("Added '" + name + "' and installed " + lock.packages().size() + " package(s).");
    }
}

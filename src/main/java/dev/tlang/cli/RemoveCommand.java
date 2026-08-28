package dev.tlang.cli;

import dev.tlang.packages.PackageLock;
import dev.tlang.packages.PackageManager;
import java.nio.file.Path;

public final class RemoveCommand implements Command {
    @Override public void execute(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: tlang remove <name>");
        PackageLock lock = new PackageManager().remove(Path.of("."), args[0]);
        System.out.println("Removed '" + args[0] + "'; " + lock.packages().size() + " package(s) remain.");
    }
}

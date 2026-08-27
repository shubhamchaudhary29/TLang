package dev.tlang.cli;

import dev.tlang.packages.PackageLock;
import dev.tlang.packages.PackageManager;
import java.nio.file.Path;

public final class InstallCommand implements Command {
    @Override public void execute(String[] args) {
        boolean offline = false;
        boolean update = false;
        for (String argument : args) {
            if (argument.equals("--offline")) offline = true;
            else if (argument.equals("--update")) update = true;
            else throw new IllegalArgumentException("Usage: tlang install [--offline] [--update]");
        }
        PackageLock lock = new PackageManager().install(Path.of("."), offline, update);
        System.out.println("Installed " + lock.packages().size() + " package(s) from tlang.lock.");
    }
}

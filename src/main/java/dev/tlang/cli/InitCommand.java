package dev.tlang.cli;

import dev.tlang.packages.PackageManager;
import java.nio.file.Path;

public final class InitCommand implements Command {
    @Override public void execute(String[] args) {
        String name = null;
        if (args.length == 2 && args[0].equals("--name")) name = args[1];
        else if (args.length != 0) throw new IllegalArgumentException("Usage: tlang init [--name <name>]");
        Path path = new PackageManager().init(Path.of("."), name);
        System.out.println("Created " + path.getFileName() + ".");
        System.out.println("Next: add dependencies with 'tlang add', then run your .tiny file.");
    }
}

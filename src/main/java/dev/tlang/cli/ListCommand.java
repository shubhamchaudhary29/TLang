package dev.tlang.cli;

import dev.tlang.packages.PackageManager;
import java.nio.file.Path;

public final class ListCommand implements Command {
    @Override public void execute(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: tlang list");
        System.out.print(new PackageManager().list(Path.of(".")));
    }
}

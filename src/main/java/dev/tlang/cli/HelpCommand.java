package dev.tlang.cli;

public final class HelpCommand implements Command {
    @Override
    public void execute(String[] args) {
        System.out.println("TLang");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  tlang run <file>");
        System.out.println("  tlang fmt [--check] <file>");
        System.out.println("  tlang init [--name <name>]");
        System.out.println("  tlang add <name> --path <path>");
        System.out.println("  tlang add <name> --git <url> --rev <revision>");
        System.out.println("  tlang remove <name>");
        System.out.println("  tlang install [--offline] [--update]");
        System.out.println("  tlang list");
        System.out.println("  tlang version");
        System.out.println("  tlang help");
        System.out.println("  tlang lsp");
    }
}

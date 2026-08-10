package dev.tlang.cli;

public final class HelpCommand implements Command {
    @Override
    public void execute(String[] args) {
        System.out.println("TLang");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  tlang run <file>");
        System.out.println("  tlang fmt [--check] <file>");
        System.out.println("  tlang version");
        System.out.println("  tlang help");
        System.out.println("  lsp");
    }
}

package dev.tlang.cli;

public final class HelpCommand implements Command {
    @Override
    public void execute(String[] args) {
        System.out.println("TLang");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  tlang run <file>");
        System.out.println("  tlang version");
        System.out.println("  tlang help");
        System.out.println();
        System.out.println("Future commands:");
        System.out.println("  fmt");
        System.out.println("  doctor");
        System.out.println("  test");
        System.out.println("  repl");
        System.out.println("  new");
        System.out.println("  install");
        System.out.println("  publish");
        System.out.println("  lsp");
    }
}

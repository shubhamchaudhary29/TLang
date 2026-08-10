package dev.tlang;

import dev.tlang.cli.TLangCLI;

/**
 * Entry point for the TinyLang interpreter.
 *
 * Forwards arguments to the command-line interface dispatcher.
 */
public final class Main {
    public static void main(String[] args) {
        TLangCLI.main(args);
    }
}

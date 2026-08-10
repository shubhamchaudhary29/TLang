package dev.tlang.cli;

import java.util.HashMap;
import java.util.Map;

public final class TLangCLI {
    private static final Map<String, Command> COMMANDS = new HashMap<>();

    static {
        COMMANDS.put("run", new RunCommand());
        COMMANDS.put("version", new VersionCommand());
        COMMANDS.put("help", new HelpCommand());
        COMMANDS.put("fmt", new FmtCommand());
        COMMANDS.put("lsp", new LspCommand());
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            new HelpCommand().execute(args);
            System.exit(0);
        }

        String cmdName = args[0];
        Command cmd = COMMANDS.get(cmdName);

        if (cmd == null) {
            // Implicit run fallback: if there is only 1 argument, it is not a reserved keyword, and it is an existing file or ends with .tiny
            if (args.length == 1 && !isReserved(cmdName) && (new java.io.File(cmdName).exists() || cmdName.endsWith(".tiny"))) {
                Command runCmd = COMMANDS.get("run");
                runCmd.execute(args);
                return;
            }

            if (isReserved(cmdName)) {
                System.err.println("Error: Command '" + cmdName + "' is reserved for future use and is not implemented yet.");
            } else {
                System.err.println("Error: Unknown command '" + cmdName + "'.");
            }
            System.err.println();
            new HelpCommand().execute(new String[0]);
            System.exit(64);
        }

        // Extract arguments after command name
        String[] cmdArgs = new String[args.length - 1];
        System.arraycopy(args, 1, cmdArgs, 0, cmdArgs.length);

        try {
            cmd.execute(cmdArgs);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isReserved(String cmd) {
        return cmd.equals("doctor") || cmd.equals("test")
            || cmd.equals("repl") || cmd.equals("new") || cmd.equals("install")
            || cmd.equals("publish");
    }
}

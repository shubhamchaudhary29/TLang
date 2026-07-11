package dev.tlang.cli;

import dev.tlang.lsp.TLangLanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.PrintStream;
import java.util.concurrent.Future;

public final class LspCommand implements Command {
    @Override
    public void execute(String[] args) {
        // Capture original stdout before redirecting
        PrintStream originalOut = System.out;
        
        // Redirect stdout to stderr to avoid stray console prints polluting the JSON-RPC stream
        System.setOut(System.err);

        try {
            TLangLanguageServer server = new TLangLanguageServer();
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
                    server,
                    System.in,
                    originalOut
            );

            LanguageClient client = launcher.getRemoteProxy();
            server.connect(client);

            Future<Void> future = launcher.startListening();
            
            // Block main thread until the communication channel is closed (e.g. IDE exits/closes connection)
            future.get();
        } catch (Exception e) {
            System.err.println("LSP Server error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}

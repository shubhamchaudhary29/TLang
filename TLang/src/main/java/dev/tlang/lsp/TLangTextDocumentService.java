package dev.tlang.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.LexerError;
import dev.tlang.errors.ParseError;
import dev.tlang.errors.SemanticError;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;

public final class TLangTextDocumentService implements TextDocumentService {
    private final TLangLanguageServer server;
    private final Map<String, String> documentContents = new ConcurrentHashMap<>();

    public TLangTextDocumentService(TLangLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        documentContents.put(uri, text);
        runDiagnostics(uri, text);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (!params.getContentChanges().isEmpty()) {
            // Full sync kind guarantees the first element contains the entire document content
            String text = params.getContentChanges().get(0).getText();
            documentContents.put(uri, text);
            runDiagnostics(uri, text);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documentContents.remove(uri);
        if (server.getClient() != null) {
            server.getClient().publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Handled dynamically on didChange/didOpen
    }

    private void runDiagnostics(String uri, String source) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            // Stage 1: Lexing
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();

            // Stage 2: Parsing
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            // Stage 3: Semantic Analysis
            Resolver resolver = new Resolver();
            List<SemanticError> errors = resolver.resolve(program);
            if (errors != null) {
                for (SemanticError err : errors) {
                    diagnostics.add(createDiagnostic(source, err.getMessage(), err.getLine(), err.getColumn(), null));
                }
            }
        } catch (LexerError e) {
            diagnostics.add(createDiagnostic(source, e.getRawMessage(), e.getLine(), e.getColumn(), null));
        } catch (ParseError e) {
            Token t = e.getToken();
            if (t != null) {
                diagnostics.add(createDiagnostic(source, e.getRawMessage(), t.getLine(), t.getColumn(), t.getLexeme()));
            } else {
                diagnostics.add(createDiagnostic(source, e.getRawMessage(), 1, 1, null));
            }
        } catch (Throwable t) {
            diagnostics.add(createDiagnostic(source, "Internal LSP Error: " + t.getMessage(), 1, 1, null));
        }

        if (server.getClient() != null) {
            server.getClient().publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    private Diagnostic createDiagnostic(String source, String message, int line, int column, String lexeme) {
        // Convert 1-indexed to 0-indexed
        int lspLine = Math.max(0, line - 1);
        int lspCol = Math.max(0, column - 1);

        Position start = new Position(lspLine, lspCol);
        Position end;

        if (lexeme != null && !lexeme.isEmpty()) {
            end = new Position(lspLine, lspCol + lexeme.length());
        } else {
            int length = findWordLength(source, lspLine, lspCol);
            end = new Position(lspLine, lspCol + length);
        }

        Range range = new Range(start, end);
        Diagnostic diagnostic = new Diagnostic(range, message);
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        diagnostic.setSource("TLang");
        return diagnostic;
    }

    private int findWordLength(String source, int lspLine, int lspCol) {
        String[] lines = source.split("\\r?\\n", -1);
        if (lspLine >= lines.length) {
            return 1;
        }
        String lineText = lines[lspLine];
        if (lspCol >= lineText.length()) {
            return 1;
        }

        int length = 0;
        while (lspCol + length < lineText.length()) {
            char c = lineText.charAt(lspCol + length);
            if (Character.isLetterOrDigit(c) || c == '_') {
                length++;
            } else {
                break;
            }
        }
        return Math.max(1, length);
    }
}

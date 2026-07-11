package dev.tlang.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.LexerError;
import dev.tlang.errors.ParseError;
import dev.tlang.errors.SemanticError;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import dev.tlang.resolver.Symbol;
import dev.tlang.resolver.SymbolReference;

public final class TLangTextDocumentService implements TextDocumentService {
    private final TLangLanguageServer server;
    private final Map<String, String> documentContents = new ConcurrentHashMap<>();
    private final Map<String, List<SymbolReference>> documentSymbolReferences = new ConcurrentHashMap<>();

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
        documentSymbolReferences.remove(uri);
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
            documentSymbolReferences.put(uri, resolver.getSymbolReferences());
            if (errors != null) {
                for (SemanticError err : errors) {
                    diagnostics.add(createDiagnostic(source, err.getMessage(), err.getLine(), err.getColumn(), null));
                }
            }
        } catch (LexerError e) {
            documentSymbolReferences.remove(uri);
            diagnostics.add(createDiagnostic(source, e.getRawMessage(), e.getLine(), e.getColumn(), null));
        } catch (ParseError e) {
            documentSymbolReferences.remove(uri);
            Token t = e.getToken();
            if (t != null) {
                diagnostics.add(createDiagnostic(source, e.getRawMessage(), t.getLine(), t.getColumn(), t.getLexeme()));
            } else {
                diagnostics.add(createDiagnostic(source, e.getRawMessage(), 1, 1, null));
            }
        } catch (Throwable t) {
            documentSymbolReferences.remove(uri);
            diagnostics.add(createDiagnostic(source, "Internal LSP Error: " + t.getMessage(), 1, 1, null));
        }

        if (server.getClient() != null) {
            server.getClient().publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Position pos = params.getPosition();
            List<SymbolReference> refs = documentSymbolReferences.get(uri);
            if (refs == null) return null;

            for (SymbolReference ref : refs) {
                int lspLine = ref.getLine() - 1;
                int lspCol = ref.getColumn() - 1;
                if (pos.getLine() == lspLine && pos.getCharacter() >= lspCol && pos.getCharacter() < lspCol + ref.getLength()) {
                    Symbol sym = ref.getSymbol();
                    String content = sym.getName() + ": " + sym.getKind().name().toLowerCase() + "\n" +
                                     (sym.getLine() == 0 ? "built-in" : "declared at line " + sym.getLine());
                    
                    MarkupContent markup = new MarkupContent(MarkupKind.MARKDOWN, content);
                    Hover hover = new Hover(markup);
                    hover.setRange(new Range(new Position(lspLine, lspCol), new Position(lspLine, lspCol + ref.getLength())));
                    return hover;
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Position pos = params.getPosition();
            List<SymbolReference> refs = documentSymbolReferences.get(uri);
            if (refs == null) {
                return Either.forLeft(new ArrayList<>());
            }

            for (SymbolReference ref : refs) {
                int lspLine = ref.getLine() - 1;
                int lspCol = ref.getColumn() - 1;
                if (pos.getLine() == lspLine && pos.getCharacter() >= lspCol && pos.getCharacter() < lspCol + ref.getLength()) {
                    Symbol sym = ref.getSymbol();
                    if (sym.getLine() == 0) {
                        return Either.forLeft(new ArrayList<>());
                    }

                    int declLine = sym.getLine() - 1;
                    int declCol = sym.getColumn() - 1;
                    int declLength = sym.getName().length();

                    Range range = new Range(
                            new Position(declLine, declCol),
                            new Position(declLine, declCol + declLength)
                    );

                    Location location = new Location(uri, range);
                    List<Location> locations = new ArrayList<>();
                    locations.add(location);
                    return Either.forLeft(locations);
                }
            }
            return Either.forLeft(new ArrayList<>());
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Position pos = params.getPosition();
            List<SymbolReference> refs = documentSymbolReferences.get(uri);
            if (refs == null) {
                return new ArrayList<>();
            }

            Symbol targetSymbol = null;
            for (SymbolReference ref : refs) {
                int lspLine = ref.getLine() - 1;
                int lspCol = ref.getColumn() - 1;
                if (pos.getLine() == lspLine && pos.getCharacter() >= lspCol && pos.getCharacter() < lspCol + ref.getLength()) {
                    targetSymbol = ref.getSymbol();
                    break;
                }
            }

            if (targetSymbol == null) {
                return new ArrayList<>();
            }

            List<Location> locations = new ArrayList<>();
            for (SymbolReference ref : refs) {
                if (ref.getSymbol() == targetSymbol) {
                    boolean isDecl = (ref.getLine() == targetSymbol.getLine() && ref.getColumn() == targetSymbol.getColumn());
                    if (isDecl && !params.getContext().isIncludeDeclaration()) {
                        continue;
                    }

                    int lspLine = ref.getLine() - 1;
                    int lspCol = ref.getColumn() - 1;
                    Range range = new Range(
                            new Position(lspLine, lspCol),
                            new Position(lspLine, lspCol + ref.getLength())
                    );
                    locations.add(new Location(uri, range));
                }
            }

            return locations;
        });
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

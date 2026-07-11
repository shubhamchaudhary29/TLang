package dev.tlang.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class TLangLanguageServerTest {
    private TLangLanguageServer server;
    private List<PublishDiagnosticsParams> publishedDiagnostics;
    private LanguageClient client;

    @BeforeEach
    public void setUp() {
        server = new TLangLanguageServer();
        publishedDiagnostics = new ArrayList<>();

        // Create a Dynamic Proxy mock for LanguageClient
        client = (LanguageClient) Proxy.newProxyInstance(
                LanguageClient.class.getClassLoader(),
                new Class<?>[]{LanguageClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("publishDiagnostics")) {
                        publishedDiagnostics.add((PublishDiagnosticsParams) args[0]);
                    }
                    return null;
                }
        );
        server.connect(client);
    }

    @Test
    public void testInitialize() throws Exception {
        InitializeParams params = new InitializeParams();
        InitializeResult result = server.initialize(params).get();
        assertNotNull(result);
        assertNotNull(result.getCapabilities());
        assertEquals(TextDocumentSyncKind.Full, result.getCapabilities().getTextDocumentSync().getLeft());
    }

    @Test
    public void testCleanFile() {
        String uri = "file:///test.tiny";
        String source = "let x be 42\nshow x\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        assertEquals(1, publishedDiagnostics.size());
        PublishDiagnosticsParams params = publishedDiagnostics.get(0);
        assertEquals(uri, params.getUri());
        assertTrue(params.getDiagnostics().isEmpty());
    }

    @Test
    public void testLexerError() {
        String uri = "file:///test_lexer.tiny";
        String source = "let x be 42\nshow @\n"; // '@' is an invalid token character
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        assertEquals(1, publishedDiagnostics.size());
        PublishDiagnosticsParams params = publishedDiagnostics.get(0);
        assertEquals(1, params.getDiagnostics().size());

        Diagnostic diagnostic = params.getDiagnostics().get(0);
        assertTrue(diagnostic.getMessage().toLowerCase().contains("unexpected character"));
        // Source is 'show @\n', which is line 2 (0-indexed line 1). '@' is at column 6 (0-indexed col 5)
        assertEquals(1, diagnostic.getRange().getStart().getLine());
        assertEquals(5, diagnostic.getRange().getStart().getCharacter());
    }

    @Test
    public void testParseError() {
        String uri = "file:///test_parse.tiny";
        String source = "let x be \nshow x\n"; // Missing variable initializer expression
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        assertEquals(1, publishedDiagnostics.size());
        PublishDiagnosticsParams params = publishedDiagnostics.get(0);
        assertEquals(1, params.getDiagnostics().size());

        Diagnostic diagnostic = params.getDiagnostics().get(0);
        // The parser should complain about newline or missing expression
        assertTrue(diagnostic.getMessage().toLowerCase().contains("expected expression"));
        // Line 1 (0-indexed line 0). Column of NEWLINE token is where it was scanned.
        assertEquals(0, diagnostic.getRange().getStart().getLine());
    }

    @Test
    public void testSemanticErrorsAndClearing() {
        String uri = "file:///test_semantic.tiny";
        
        // 1. Trigger multiple undefined variable semantic errors
        String sourceWithErrors = "show a\nshow b\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, sourceWithErrors)
        ));

        assertEquals(1, publishedDiagnostics.size());
        PublishDiagnosticsParams paramsWithErrors = publishedDiagnostics.get(0);
        assertEquals(2, paramsWithErrors.getDiagnostics().size());

        // Check first diagnostic (variable 'a')
        Diagnostic d1 = paramsWithErrors.getDiagnostics().get(0);
        assertTrue(d1.getMessage().contains("Undefined variable 'a'"));
        assertEquals(0, d1.getRange().getStart().getLine()); // Line 1
        assertEquals(5, d1.getRange().getStart().getCharacter()); // Column 6
        assertEquals(6, d1.getRange().getEnd().getCharacter()); // 'a' is 1 char long

        // Check second diagnostic (variable 'b')
        Diagnostic d2 = paramsWithErrors.getDiagnostics().get(1);
        assertTrue(d2.getMessage().contains("Undefined variable 'b'"));
        assertEquals(1, d2.getRange().getStart().getLine()); // Line 2
        assertEquals(5, d2.getRange().getStart().getCharacter()); // Column 6
        assertEquals(6, d2.getRange().getEnd().getCharacter()); // 'b' is 1 char long

        // 2. Modify to resolve the errors and ensure they clear
        String resolvedSource = "let a be 10\nlet b be 20\nshow a\nshow b\n";
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, 2),
                Collections.singletonList(new TextDocumentContentChangeEvent(resolvedSource))
        ));

        assertEquals(2, publishedDiagnostics.size());
        PublishDiagnosticsParams paramsResolved = publishedDiagnostics.get(1);
        assertTrue(paramsResolved.getDiagnostics().isEmpty());
    }
}

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

    @Test
    public void testHoverVariable() throws Exception {
        String uri = "file:///test_hover_var.tiny";
        String source = "let x be 42\nshow x\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Hover over 'x' on line 1 (let x be 42) -> 'x' starts at char 4
        HoverParams params1 = new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 4));
        Hover hover1 = server.getTextDocumentService().hover(params1).get();
        assertNotNull(hover1);
        assertEquals("x: variable\ndeclared at line 1", hover1.getContents().getRight().getValue());

        // Hover over 'x' on line 2 (show x) -> 'x' starts at char 5
        HoverParams params2 = new HoverParams(new TextDocumentIdentifier(uri), new Position(1, 5));
        Hover hover2 = server.getTextDocumentService().hover(params2).get();
        assertNotNull(hover2);
        assertEquals("x: variable\ndeclared at line 1", hover2.getContents().getRight().getValue());
    }

    @Test
    public void testHoverFunctionAndParameter() throws Exception {
        String uri = "file:///test_hover_fn.tiny";
        String source = "define add taking a and b\n  return a + b\nlet sum be add(1, 2)\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Hover over 'add' on line 3 (let sum be add(1,2)) -> 'add' starts at char 11 on line 3 (index 2)
        HoverParams params1 = new HoverParams(new TextDocumentIdentifier(uri), new Position(2, 11));
        Hover hover1 = server.getTextDocumentService().hover(params1).get();
        assertNotNull(hover1);
        assertEquals("add: function\ndeclared at line 1", hover1.getContents().getRight().getValue());

        // Hover over 'a' on line 2 (return a + b) -> 'a' is at char 9 on line 2 (index 1)
        HoverParams params2 = new HoverParams(new TextDocumentIdentifier(uri), new Position(1, 9));
        Hover hover2 = server.getTextDocumentService().hover(params2).get();
        assertNotNull(hover2);
        assertEquals("a: parameter\ndeclared at line 1", hover2.getContents().getRight().getValue());
    }

    @Test
    public void testHoverBuiltin() throws Exception {
        String uri = "file:///test_hover_builtin.tiny";
        String source = "show now()\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Hover over 'now' on line 1 -> 'now' starts at char 5
        HoverParams params = new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 5));
        Hover hover = server.getTextDocumentService().hover(params).get();
        assertNotNull(hover);
        assertEquals("now: function\nbuilt-in", hover.getContents().getRight().getValue());
    }

    @Test
    public void testHoverEmpty() throws Exception {
        String uri = "file:///test_hover_empty.tiny";
        String source = "let x be 42\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Hover over empty space on line 1 (char 0, which is 'l') -> not a symbol reference
        HoverParams params1 = new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 0));
        Hover hover1 = server.getTextDocumentService().hover(params1).get();
        assertNull(hover1);

        // Hover over whitespace
        HoverParams params2 = new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 3));
        Hover hover2 = server.getTextDocumentService().hover(params2).get();
        assertNull(hover2);
    }

    @Test
    public void testDefinitionVariable() throws Exception {
        String uri = "file:///test_def_var.tiny";
        String source = "let x be 42\nshow x\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Definition of 'x' on line 2 (show x) -> 'x' starts at char 5
        DefinitionParams params = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(1, 5));
        var result = server.getTextDocumentService().definition(params).get();
        assertTrue(result.isLeft());
        var locations = result.getLeft();
        assertEquals(1, locations.size());
        assertEquals(uri, locations.get(0).getUri());
        assertEquals(0, locations.get(0).getRange().getStart().getLine()); // Line 1
        assertEquals(4, locations.get(0).getRange().getStart().getCharacter()); // Column 5
        assertEquals(0, locations.get(0).getRange().getEnd().getLine());
        assertEquals(5, locations.get(0).getRange().getEnd().getCharacter());
    }

    @Test
    public void testDefinitionFunctionAndParameter() throws Exception {
        String uri = "file:///test_def_fn.tiny";
        String source = "define add taking a and b\n  return a + b\nlet sum be add(1, 2)\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Definition of 'add' on line 3 (let sum be add(1,2)) -> 'add' starts at char 11
        DefinitionParams params1 = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(2, 11));
        var result1 = server.getTextDocumentService().definition(params1).get();
        assertTrue(result1.isLeft());
        var locations1 = result1.getLeft();
        assertEquals(1, locations1.size());
        assertEquals(0, locations1.get(0).getRange().getStart().getLine()); // Line 1
        assertEquals(7, locations1.get(0).getRange().getStart().getCharacter()); // "add" starts at char 7 (0-indexed)
        assertEquals(10, locations1.get(0).getRange().getEnd().getCharacter());

        // Definition of 'a' on line 2 (return a + b) -> 'a' is at char 9
        DefinitionParams params2 = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(1, 9));
        var result2 = server.getTextDocumentService().definition(params2).get();
        assertTrue(result2.isLeft());
        var locations2 = result2.getLeft();
        assertEquals(1, locations2.size());
        assertEquals(0, locations2.get(0).getRange().getStart().getLine()); // Line 1
        assertEquals(18, locations2.get(0).getRange().getStart().getCharacter()); // "a" starts at char 18
        assertEquals(19, locations2.get(0).getRange().getEnd().getCharacter());
    }

    @Test
    public void testDefinitionBuiltin() throws Exception {
        String uri = "file:///test_def_builtin.tiny";
        String source = "show now()\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Definition of 'now' on line 1 -> 'now' starts at char 5
        DefinitionParams params = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(0, 5));
        var result = server.getTextDocumentService().definition(params).get();
        assertTrue(result.isLeft());
        assertTrue(result.getLeft().isEmpty());
    }

    @Test
    public void testDefinitionEmpty() throws Exception {
        String uri = "file:///test_def_empty.tiny";
        String source = "let x be 42\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // Definition over empty space or keywords
        DefinitionParams params1 = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(0, 0));
        var result1 = server.getTextDocumentService().definition(params1).get();
        assertTrue(result1.isLeft());
        assertTrue(result1.getLeft().isEmpty());

        DefinitionParams params2 = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(0, 3));
        var result2 = server.getTextDocumentService().definition(params2).get();
        assertTrue(result2.isLeft());
        assertTrue(result2.getLeft().isEmpty());
    }

    @Test
    public void testReferencesVariableAndShadowing() throws Exception {
        String uri = "file:///test_ref_shadow.tiny";
        String source = "let x be 10\nif x > 5\n  let x be 20\n  show x\nshow x\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // 1. References of outer 'x' at Line 0, Char 4, with includeDeclaration: true
        ReferenceParams paramsOuterInclude = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 4),
                new ReferenceContext(true)
        );
        var res1 = server.getTextDocumentService().references(paramsOuterInclude).get();
        assertEquals(3, res1.size());
        
        // Assert outer 'x' references (line 0, line 1, line 4)
        boolean hasLine0 = res1.stream().anyMatch(l -> l.getRange().getStart().getLine() == 0 && l.getRange().getStart().getCharacter() == 4);
        boolean hasLine1 = res1.stream().anyMatch(l -> l.getRange().getStart().getLine() == 1 && l.getRange().getStart().getCharacter() == 3);
        boolean hasLine4 = res1.stream().anyMatch(l -> l.getRange().getStart().getLine() == 4 && l.getRange().getStart().getCharacter() == 5);
        assertTrue(hasLine0);
        assertTrue(hasLine1);
        assertTrue(hasLine4);

        // 2. References of outer 'x' with includeDeclaration: false
        ReferenceParams paramsOuterExclude = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 4),
                new ReferenceContext(false)
        );
        var res2 = server.getTextDocumentService().references(paramsOuterExclude).get();
        assertEquals(2, res2.size());
        assertFalse(res2.stream().anyMatch(l -> l.getRange().getStart().getLine() == 0)); // No declaration
        assertTrue(res2.stream().anyMatch(l -> l.getRange().getStart().getLine() == 1));
        assertTrue(res2.stream().anyMatch(l -> l.getRange().getStart().getLine() == 4));

        // 3. References of inner shadowed 'x' at Line 3, Char 7, with includeDeclaration: true
        ReferenceParams paramsInnerInclude = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(3, 7),
                new ReferenceContext(true)
        );
        var res3 = server.getTextDocumentService().references(paramsInnerInclude).get();
        assertEquals(2, res3.size());
        
        // Assert inner 'x' references (line 2, line 3)
        boolean hasLine2 = res3.stream().anyMatch(l -> l.getRange().getStart().getLine() == 2 && l.getRange().getStart().getCharacter() == 6);
        boolean hasLine3 = res3.stream().anyMatch(l -> l.getRange().getStart().getLine() == 3 && l.getRange().getStart().getCharacter() == 7);
        assertTrue(hasLine2);
        assertTrue(hasLine3);
    }

    @Test
    public void testReferencesFunction() throws Exception {
        String uri = "file:///test_ref_fn.tiny";
        String source = "define add taking a and b\n  return a + b\nlet sum be add(1, 2)\nshow add(3, 4)\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        // References of 'add' at Line 2, Char 11, with includeDeclaration: false
        ReferenceParams paramsExclude = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(2, 11),
                new ReferenceContext(false)
        );
        var res = server.getTextDocumentService().references(paramsExclude).get();
        assertEquals(2, res.size());
        assertTrue(res.stream().anyMatch(l -> l.getRange().getStart().getLine() == 2 && l.getRange().getStart().getCharacter() == 11));
        assertTrue(res.stream().anyMatch(l -> l.getRange().getStart().getLine() == 3 && l.getRange().getStart().getCharacter() == 5));
    }

    @Test
    public void testReferencesEmpty() throws Exception {
        String uri = "file:///test_ref_empty.tiny";
        String source = "let x be 42\n";
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "tiny", 1, source)
        ));

        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 0), // 'l' in let
                new ReferenceContext(true)
        );
        var res = server.getTextDocumentService().references(params).get();
        assertNotNull(res);
        assertTrue(res.isEmpty());
    }
}


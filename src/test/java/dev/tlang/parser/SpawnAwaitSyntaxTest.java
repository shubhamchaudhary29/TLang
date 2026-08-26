package dev.tlang.parser;

import dev.tlang.ast.AwaitExpr;
import dev.tlang.ast.BinaryExpr;
import dev.tlang.ast.Expr;
import dev.tlang.ast.SpawnExpr;
import dev.tlang.ast.Stmt;
import dev.tlang.ast.VarStmt;
import dev.tlang.errors.ParseError;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.TokenType;
import dev.tlang.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpawnAwaitSyntaxTest {
    @Test
    void spawnAndAwaitAreKeywordsAndDedicatedAstNodes() {
        var tokens = new Lexer("let result be await spawn work(10)\n").tokenize();
        assertTrue(tokens.stream().anyMatch(token -> token.getType() == TokenType.SPAWN));
        assertTrue(tokens.stream().anyMatch(token -> token.getType() == TokenType.AWAIT));

        List<Stmt> program = new Parser(tokens).parse();
        Expr initializer = ((VarStmt) program.get(0)).getInitializer();
        assertInstanceOf(AwaitExpr.class, initializer);
        assertInstanceOf(SpawnExpr.class, ((AwaitExpr) initializer).getTask());
    }

    @Test
    void awaitHasUnaryPrecedence() {
        List<Stmt> program = new Parser(new Lexer(
            "let result be (await task) + 1\n").tokenize()).parse();
        Expr initializer = ((VarStmt) program.get(0)).getInitializer();
        assertInstanceOf(BinaryExpr.class, initializer);
        assertInstanceOf(AwaitExpr.class,
            ((dev.tlang.ast.GroupingExpr) ((BinaryExpr) initializer).getLeft()).getExpression());
    }

    @Test
    void spawnRejectsValuesThatAreNotInvokedCalls() {
        for (String operand : List.of("10", "\"value\"", "worker")) {
            ParseError error = assertThrows(ParseError.class,
                () -> new Parser(new Lexer("let task be spawn " + operand + "\n").tokenize()).parse());
            assertTrue(error.getRawMessage().contains("'spawn' must be followed by a function call."));
            assertEquals(TokenType.SPAWN, error.getToken().getType());
        }
    }

    @Test
    void resolverTraversesSpawnCalleeArgumentsAndAwaitOperand() {
        List<Stmt> program = new Parser(new Lexer(
            "let task be spawn missing(argument)\nlet result be await unknownTask\n").tokenize()).parse();
        var errors = new Resolver().resolve(program);
        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("missing")));
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("argument")));
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("unknownTask")));
    }
}

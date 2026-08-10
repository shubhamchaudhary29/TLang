package dev.tlang.runtime.json;

import dev.tlang.errors.RuntimeError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.tlang.lexer.Token;

/**
 * Hand-written recursive descent JSON parser.
 */
public final class JsonParser {
    private final String source;
    private final Token token;
    private int cursor = 0;

    public JsonParser(String source, Token token) {
        this.source = source;
        this.token = token;
    }

    public Object parse() {
        skipWhitespace();
        Object value = parseValue();
        skipWhitespace();
        if (cursor < source.length()) {
            throw error("Unexpected trailing characters in JSON.");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (cursor >= source.length()) {
            throw error("Unexpected end of JSON input.");
        }
        char c = peek();
        if (c == '{') {
            return parseObject();
        } else if (c == '[') {
            return parseArray();
        } else if (c == '"') {
            return parseString();
        } else if (c == 't' || c == 'f') {
            return parseBoolean();
        } else if (c == 'n') {
            return parseNull();
        } else if (Character.isDigit(c) || c == '-') {
            return parseNumber();
        } else {
            throw error("Unexpected character '" + c + "' in JSON.");
        }
    }

    private Map<String, Object> parseObject() {
        consume('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            consume('}');
            return map;
        }

        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected string key in JSON object.");
            }
            String key = parseString();
            skipWhitespace();
            consume(':');
            skipWhitespace();
            Object val = parseValue();
            map.put(key, val);

            skipWhitespace();
            char next = peek();
            if (next == '}') {
                consume('}');
                break;
            } else if (next == ',') {
                consume(',');
            } else {
                throw error("Expected ',' or '}' in JSON object.");
            }
        }
        return map;
    }

    private List<Object> parseArray() {
        consume('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            consume(']');
            return list;
        }

        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') {
                consume(']');
                break;
            } else if (next == ',') {
                consume(',');
            } else {
                throw error("Expected ',' or ']' in JSON array.");
            }
        }
        return list;
    }

    private String parseString() {
        consume('"');
        StringBuilder sb = new StringBuilder();
        while (cursor < source.length() && peek() != '"') {
            char c = advance();
            if (c == '\\') {
                if (cursor >= source.length()) {
                    throw error("Unterminated string escape sequence.");
                }
                char esc = advance();
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (cursor + 4 > source.length()) {
                            throw error("Malformed Unicode escape sequence.");
                        }
                        String hex = source.substring(cursor, cursor + 4);
                        cursor += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid Unicode escape sequence: \\u" + hex);
                        }
                        break;
                    }
                    default:
                        throw error("Invalid escape sequence: \\" + esc);
                }
            } else {
                if (c < 0x20) {
                    throw error("Unescaped control character in JSON string.");
                }
                sb.append(c);
            }
        }
        consume('"');
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (match("true")) {
            return true;
        }
        if (match("false")) {
            return false;
        }
        throw error("Expected boolean value in JSON.");
    }

    private Object parseNull() {
        if (match("null")) {
            return null;
        }
        throw error("Expected null value in JSON.");
    }

    private Object parseNumber() {
        int start = cursor;
        if (peek() == '-') {
            advance();
        }
        if (peek() == '0') {
            advance();
        } else {
            while (cursor < source.length() && Character.isDigit(peek())) {
                advance();
            }
        }

        boolean hasDecimalOrExp = false;
        if (cursor < source.length() && peek() == '.') {
            hasDecimalOrExp = true;
            advance();
            if (cursor >= source.length() || !Character.isDigit(peek())) {
                throw error("Malformed JSON number.");
            }
            while (cursor < source.length() && Character.isDigit(peek())) {
                advance();
            }
        }

        if (cursor < source.length() && (peek() == 'e' || peek() == 'E')) {
            hasDecimalOrExp = true;
            advance();
            if (cursor < source.length() && (peek() == '+' || peek() == '-')) {
                advance();
            }
            if (cursor >= source.length() || !Character.isDigit(peek())) {
                throw error("Malformed JSON number.");
            }
            while (cursor < source.length() && Character.isDigit(peek())) {
                advance();
            }
        }

        String lexeme = source.substring(start, cursor);
        if (hasDecimalOrExp) {
            throw error("TLang has no non-integer numeric type yet. Non-integer JSON numbers are not supported.");
        }

        try {
            return Integer.parseInt(lexeme);
        } catch (NumberFormatException e) {
            throw error("Malformed JSON number or out of bounds integer: " + lexeme);
        }
    }

    private void skipWhitespace() {
        while (cursor < source.length()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                advance();
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (cursor >= source.length()) return '\0';
        return source.charAt(cursor);
    }

    private char advance() {
        if (cursor >= source.length()) return '\0';
        return source.charAt(cursor++);
    }

    private void consume(char expected) {
        if (peek() != expected) {
            throw error("Expected '" + expected + "' but got '" + peek() + "'.");
        }
        advance();
    }

    private boolean match(String prefix) {
        if (source.startsWith(prefix, cursor)) {
            cursor += prefix.length();
            return true;
        }
        return false;
    }

    private RuntimeError error(String message) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < cursor; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new RuntimeError(token, "JSON parse error: " + message + " (at line " + line + ", column " + col + ", index " + cursor + ")");
    }
}

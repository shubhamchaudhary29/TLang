package dev.tlang.errors;

public final class ErrorFormatter {
    public static String format(String source, String fileName,
                                 int line, int column,
                                 String label, String message) {
        String displayFileName = (fileName != null && !fileName.isEmpty()) ? fileName : "script";
        if (line <= 0 || column <= 0 || source == null) {
            return displayFileName + ": " + label + ": " + message;
        }

        String prefix = displayFileName + ":" + line + ":" + column + ": " + label + ": " + message;

        String[] lines = source.split("\\r?\\n", -1);
        if (line - 1 < 0 || line - 1 >= lines.length) {
            return prefix;
        }

        String sourceLine = lines[line - 1];
        StringBuilder caret = new StringBuilder();
        for (int i = 0; i < column - 1; i++) {
            if (i < sourceLine.length()) {
                char c = sourceLine.charAt(i);
                if (Character.isWhitespace(c)) {
                    caret.append(c);
                } else {
                    caret.append(' ');
                }
            } else {
                caret.append(' ');
            }
        }
        caret.append('^');

        return prefix + "\n" + sourceLine + "\n" + caret.toString();
    }
}

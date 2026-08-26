package dev.tlang.errors;

public final class ErrorFormatter {
    private ErrorFormatter() {}

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

    /** Format a structured runtime diagnostic without exposing JVM frames or causes. */
    public static String format(RuntimeError error) {
        StringBuilder result = new StringBuilder();
        result.append(error.getKind().displayName()).append(": ").append(error.getMessage());

        SourceLocation location = error.getLocation();
        if (location.hasPosition()) {
            appendPrimaryLocation(result, location);
        }

        if (!error.getFrames().isEmpty()) {
            result.append("\n\nStack trace:");
            for (RuntimeStackFrame frame : error.getFrames()) {
                result.append("\n  at ").append(frame.name());
                SourceLocation frameLocation = frame.location();
                if (frameLocation.hasPosition()) {
                    result.append(" (")
                        .append(frameLocation.displaySourceName())
                        .append(":")
                        .append(frameLocation.line())
                        .append(":")
                        .append(frameLocation.column())
                        .append(")");
                }
            }
        }

        return result.toString();
    }

    private static void appendPrimaryLocation(StringBuilder result, SourceLocation location) {
        result.append("\n\n  --> ")
            .append(location.displaySourceName())
            .append(":")
            .append(location.line())
            .append(":")
            .append(location.column());

        String source = location.source();
        if (source == null) {
            return;
        }
        String[] lines = source.split("\\r?\\n", -1);
        if (location.line() > lines.length) {
            return;
        }

        String sourceLine = lines[location.line() - 1];
        int width = Integer.toString(location.line()).length();
        String gutter = " ".repeat(width);
        result.append("\n  ").append(gutter).append(" |")
            .append("\n  ").append(location.line()).append(" | ").append(sourceLine)
            .append("\n  ").append(gutter).append(" | ");

        for (int index = 0; index < location.column() - 1; index++) {
            char character = index < sourceLine.length() ? sourceLine.charAt(index) : ' ';
            result.append(character == '\t' ? '\t' : ' ');
        }
        result.append('^');
    }
}

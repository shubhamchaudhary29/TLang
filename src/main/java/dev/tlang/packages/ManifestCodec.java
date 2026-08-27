package dev.tlang.packages;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Strict parser and deterministic writer for TLang's documented TOML subset. */
public final class ManifestCodec {
    public static final Pattern PACKAGE_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    public static final Pattern DEPENDENCY_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern VERSION = Pattern.compile(
        "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
            + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern GIT_REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");

    private ManifestCodec() {}

    public static PackageManifest parse(String text, String sourceName) {
        if (text == null) throw error(sourceName, 1, "manifest is empty");
        if (text.startsWith("\ufeff")) text = text.substring(1);
        rejectControlCharacters(text, sourceName);

        Map<String, String> packageFields = new LinkedHashMap<>();
        Map<String, DependencySpec> dependencies = new TreeMap<>();
        String table = null;
        boolean packageSeen = false;
        boolean dependenciesSeen = false;
        List<Statement> statements = statements(text, sourceName);
        for (Statement statement : statements) {
            String value = statement.text().trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                if (value.startsWith("[[") || value.indexOf(']', 1) != value.length() - 1) {
                    throw error(sourceName, statement.line(), "malformed table header");
                }
                table = value.substring(1, value.length() - 1).trim();
                if (table.equals("package")) {
                    if (packageSeen) throw error(sourceName, statement.line(), "duplicate [package] table");
                    packageSeen = true;
                } else if (table.equals("dependencies")) {
                    if (dependenciesSeen) throw error(sourceName, statement.line(), "duplicate [dependencies] table");
                    dependenciesSeen = true;
                } else {
                    throw error(sourceName, statement.line(), "unsupported table '[" + table + "]'");
                }
                continue;
            }
            if (table == null) throw error(sourceName, statement.line(), "fields must be inside a table");
            int equals = findTopLevel(value, '=');
            if (equals < 1) throw error(sourceName, statement.line(), "expected 'key = value'");
            String key = value.substring(0, equals).trim();
            String encoded = value.substring(equals + 1).trim();
            if (!KEY.matcher(key).matches()) throw error(sourceName, statement.line(), "invalid field name '" + key + "'");
            if (table.equals("package")) {
                if (!key.equals("name") && !key.equals("version")) {
                    throw error(sourceName, statement.line(), "unsupported package field '" + key + "'");
                }
                if (packageFields.putIfAbsent(key, parseString(encoded, sourceName, statement.line())) != null) {
                    throw error(sourceName, statement.line(), "duplicate package field '" + key + "'");
                }
            } else {
                if (!DEPENDENCY_NAME.matcher(key).matches()) {
                    throw error(sourceName, statement.line(), "invalid dependency name '" + key
                        + "' (dependency names must be importable TLang identifiers)");
                }
                if (dependencies.containsKey(key)) {
                    throw error(sourceName, statement.line(), "duplicate dependency declaration '" + key + "'");
                }
                dependencies.put(key, parseDependency(key, encoded, sourceName, statement.line()));
            }
        }

        if (!packageSeen) throw error(sourceName, 1, "missing [package] table");
        String name = packageFields.get("name");
        if (name == null) throw error(sourceName, 1, "missing package name");
        validatePackageName(name, "package name", sourceName, 1);
        String version = packageFields.get("version");
        if (version == null) throw error(sourceName, 1, "missing package version");
        if (!VERSION.matcher(version).matches() || hasLeadingZeroPrereleaseNumber(version)) {
            throw error(sourceName, 1, "invalid package version '" + version + "'");
        }
        if (dependencies.containsKey(name)) throw error(sourceName, 1, "package '" + name + "' cannot depend on itself");
        return new PackageManifest(name, version, dependencies);
    }

    public static String write(PackageManifest manifest) {
        StringBuilder out = new StringBuilder();
        out.append("[package]\nname = \"").append(escape(manifest.name())).append("\"\n")
            .append("version = \"").append(escape(manifest.version())).append("\"\n\n")
            .append("[dependencies]\n");
        for (DependencySpec dependency : manifest.dependencies().values()) {
            out.append(dependency.name()).append(" = { ");
            if (dependency.sourceType() == DependencySpec.SourceType.PATH) {
                out.append("path = \"").append(escape(dependency.location())).append("\"");
            } else {
                out.append("git = \"").append(escape(dependency.location())).append("\", rev = \"")
                    .append(escape(dependency.revision())).append("\"");
            }
            out.append(" }\n");
        }
        return out.toString();
    }

    private static DependencySpec parseDependency(String name, String encoded, String source, int line) {
        if (!encoded.startsWith("{") || !encoded.endsWith("}")) {
            throw error(source, line, "dependency '" + name + "' must be an inline table");
        }
        String body = encoded.substring(1, encoded.length() - 1).trim();
        Map<String, String> fields = new LinkedHashMap<>();
        if (!body.isEmpty()) {
            for (String part : splitTopLevel(body, ',')) {
                int equals = findTopLevel(part, '=');
                if (equals < 1) throw error(source, line, "malformed dependency '" + name + "'");
                String key = part.substring(0, equals).trim();
                if (!key.equals("path") && !key.equals("git") && !key.equals("rev")) {
                    throw error(source, line, "unsupported field '" + key + "' in dependency '" + name + "'");
                }
                String previous = fields.putIfAbsent(key, parseString(part.substring(equals + 1).trim(), source, line));
                if (previous != null) throw error(source, line, "duplicate field '" + key + "' in dependency '" + name + "'");
            }
        }
        boolean path = fields.containsKey("path");
        boolean git = fields.containsKey("git");
        if (path == git) throw error(source, line, "dependency '" + name + "' must specify exactly one of path or git");
        if (path) {
            if (fields.containsKey("rev")) throw error(source, line, "path dependency '" + name + "' cannot specify rev");
            String location = fields.get("path");
            if (location.isBlank()) throw error(source, line, "path for dependency '" + name + "' is empty");
            try {
                if (Path.of(location).isAbsolute() || looksLikeForeignAbsolutePath(location)) {
                    throw error(source, line, "path dependency '" + name + "' must be relative");
                }
            } catch (java.nio.file.InvalidPathException e) {
                throw error(source, line, "path for dependency '" + name + "' is invalid");
            }
            return DependencySpec.path(name, location);
        }
        String revision = fields.get("rev");
        if (revision == null || revision.isBlank()) throw error(source, line, "Git dependency '" + name + "' requires a non-empty rev");
        if (!validGitRevision(revision)) throw error(source, line, "Git revision for '" + name + "' is unsafe or invalid");
        validateGitUri(fields.get("git"), name, source, line);
        return DependencySpec.git(name, fields.get("git"), revision);
    }

    static boolean validGitRevision(String revision) {
        return revision != null && GIT_REVISION.matcher(revision).matches()
            && !revision.contains("..") && !revision.contains("//")
            && !revision.endsWith(".") && !revision.endsWith("/");
    }

    static boolean looksLikeForeignAbsolutePath(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*") || path.startsWith("\\\\");
    }

    private static boolean hasLeadingZeroPrereleaseNumber(String version) {
        int dash = version.indexOf('-');
        if (dash < 0) return false;
        int plus = version.indexOf('+', dash);
        String prerelease = version.substring(dash + 1, plus < 0 ? version.length() : plus);
        for (String identifier : prerelease.split("\\.")) {
            if (identifier.length() > 1 && identifier.charAt(0) == '0'
                    && identifier.chars().allMatch(Character::isDigit)) return true;
        }
        return false;
    }

    public static void validateGitUri(String raw, String name, String source, int line) {
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("ssh") || scheme.equals("file"))) {
                throw error(source, line, "Git URL for '" + name + "' must use https, ssh, or file");
            }
            if ((scheme.equals("https") || scheme.equals("ssh")) && uri.getHost() == null) {
                throw error(source, line, "malformed Git URL for '" + name + "'");
            }
            if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
                throw error(source, line, "Git URL for '" + name + "' must not embed credentials");
            }
            if (uri.getRawFragment() != null || hasControl(raw) || raw.startsWith("-")) {
                throw error(source, line, "unsafe Git URL for '" + name + "'");
            }
        } catch (URISyntaxException e) {
            throw error(source, line, "malformed Git URL for '" + name + "'");
        }
    }

    private static List<Statement> statements(String text, String source) {
        List<Statement> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        int braces = 0;
        boolean string = false;
        boolean escape = false;
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String raw = stripComment(lines[index]);
            if (current.isEmpty() && raw.isBlank()) continue;
            if (current.isEmpty()) startLine = index + 1;
            if (!current.isEmpty()) current.append(' ');
            current.append(raw.trim());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (escape) { escape = false; continue; }
                if (string && c == '\\') { escape = true; continue; }
                if (c == '"') { string = !string; continue; }
                if (!string && c == '{') braces++;
                if (!string && c == '}') braces--;
                if (braces < 0) throw error(source, index + 1, "unmatched '}'");
            }
            if (string) throw error(source, index + 1, "unterminated string");
            if (!string && braces == 0) {
                if (!current.toString().isBlank()) result.add(new Statement(startLine, current.toString()));
                current.setLength(0);
            }
        }
        if (braces != 0) throw error(source, startLine, "unterminated inline table");
        return result;
    }

    private static String stripComment(String line) {
        boolean string = false;
        boolean escape = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) { escape = false; continue; }
            if (string && c == '\\') { escape = true; continue; }
            if (c == '"') string = !string;
            else if (!string && c == '#') return line.substring(0, i);
        }
        return line;
    }

    static String parseString(String encoded, String source, int line) {
        if (encoded.length() < 2 || encoded.charAt(0) != '"' || encoded.charAt(encoded.length() - 1) != '"') {
            throw error(source, line, "expected a quoted string");
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < encoded.length() - 1; i++) {
            char c = encoded.charAt(i);
            if (c != '\\') { value.append(c); continue; }
            if (++i >= encoded.length() - 1) throw error(source, line, "invalid string escape");
            char escaped = encoded.charAt(i);
            switch (escaped) {
                case '"', '\\' -> value.append(escaped);
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                default -> throw error(source, line, "unsupported string escape '\\" + escaped + "'");
            }
        }
        if (hasControl(value.toString())) throw error(source, line, "control characters are not allowed in manifest values");
        return value.toString();
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static List<String> splitTopLevel(String input, char delimiter) {
        List<String> parts = new ArrayList<>();
        boolean string = false;
        boolean escape = false;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escape) { escape = false; continue; }
            if (string && c == '\\') { escape = true; continue; }
            if (c == '"') string = !string;
            else if (!string && c == delimiter) { parts.add(input.substring(start, i).trim()); start = i + 1; }
        }
        parts.add(input.substring(start).trim());
        return parts;
    }

    private static int findTopLevel(String input, char target) {
        boolean string = false;
        boolean escape = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escape) { escape = false; continue; }
            if (string && c == '\\') { escape = true; continue; }
            if (c == '"') string = !string;
            else if (!string && c == target) return i;
        }
        return -1;
    }

    private static void validatePackageName(String name, String label, String source, int line) {
        if (!PACKAGE_NAME.matcher(name).matches()) throw error(source, line, "invalid " + label + " '" + name + "'");
    }

    private static void rejectControlCharacters(String text, String source) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c == 0x7f) {
                throw error(source, 1, "control characters are not allowed");
            }
        }
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static PackageException error(String source, int line, String message) {
        return new PackageException((source == null ? "tlang.toml" : source) + ":" + line + ": " + message);
    }

    private record Statement(int line, String text) {}
}

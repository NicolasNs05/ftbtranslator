package com.ftbtranslator.core.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Lossless span-based SNBT parser and rewriter tailored for FTB Quests {@code .snbt} files.
 * <p>
 * Rather than re-serializing the whole SNBT AST (which would alter formatting, comments or numeric
 * suffixes like {@code 1b}, {@code 2.0d}, {@code [I; 1, 2]}), this parser locates the exact character
 * spans of translatable string literals and replaces only those literals while preserving every other
 * byte of the file.
 */
public final class SnbtParser {

    /** Matches FTB Quests translation key references like {@code {ftbquests.chapter.intro.title}}. */
    private static final Pattern TRANSLATION_KEY_REF = Pattern.compile(
            "^\\{[A-Za-z0-9_.-]+\\}$");

    /** Field names targeted in hardcoded chapter and reward_table SNBT files. */
    private static final Set<String> HARDCODED_SCALAR_FIELDS = Set.of("title", "subtitle");
    private static final Set<String> HARDCODED_LIST_FIELDS = Set.of("description", "subtitle");

    /**
     * A translatable string literal located at {@code [ tokenStart, tokenEnd )} in the raw SNBT text
     * (bounds include the surrounding quotes).
     */
    public record StringSpan(int tokenStart, int tokenEnd, char quoteChar, String unescapedValue) {
    }

    private SnbtParser() {
    }

    /**
     * Returns true when {@code value} is a translation reference like {@code {quest.title}} that must
     * NOT be treated as hardcoded text.
     */
    public static boolean isTranslationKeyReference(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (!TRANSLATION_KEY_REF.matcher(trimmed).matches()) {
            return false;
        }
        // Must contain at least one dot to be a lang key (excludes things like "{image:...}" which have ':')
        return trimmed.indexOf('.') > 0;
    }

    /**
     * Extracts all string value spans from an FTB Quests {@code lang/<locale>.snbt} file.
     * Keys (followed by {@code :}) are never extracted; string values and string array elements are.
     */
    public static List<StringSpan> extractLangValueSpans(String snbt) {
        List<StringSpan> spans = new ArrayList<>();
        int len = snbt.length();
        int i = 0;
        while (i < len) {
            char c = snbt.charAt(i);
            if (c == '#' || (c == '/' && i + 1 < len && snbt.charAt(i + 1) == '/')) {
                i = skipLineComment(snbt, i);
                continue;
            }
            if (c == '"' || c == '\'') {
                ParsedLiteral lit = readQuotedLiteral(snbt, i);
                int after = skipHorizontalWhitespace(snbt, lit.endIndex);
                boolean isKey = after < len && snbt.charAt(after) == ':';
                if (!isKey && !lit.unescaped.isBlank()) {
                    spans.add(new StringSpan(i, lit.endIndex, c, lit.unescaped));
                }
                i = lit.endIndex;
                continue;
            }
            i++;
        }
        return spans;
    }

    /**
     * Merges multiple FTB Quests {@code lang/<locale>.snbt} documents in priority order.
     * <p>
     * The first document (e.g. {@code en_us.snbt}) is used as the primary base. Any keys present in
     * fallback documents (e.g. {@code ja_jp.snbt}, {@code zh_cn.snbt}) that are missing or blank in the
     * primary document are merged into the returned SNBT string so partial translations never leave
     * quests untranslated.
     */
    public static String mergeLangSnbtDocuments(List<String> orderedSnbtDocs) {
        if (orderedSnbtDocs == null || orderedSnbtDocs.isEmpty()) {
            return "";
        }
        if (orderedSnbtDocs.size() == 1) {
            return orderedSnbtDocs.get(0);
        }
        String base = orderedSnbtDocs.get(0);
        Map<String, LangKeyEntry> baseEntries = extractLangKeyEntries(base);
        Map<String, String> blankKeyReplacements = new LinkedHashMap<>();
        List<LangKeyEntry> missingEntriesToAppend = new ArrayList<>();

        for (int idx = 1; idx < orderedSnbtDocs.size(); idx++) {
            Map<String, LangKeyEntry> fallbackEntries = extractLangKeyEntries(orderedSnbtDocs.get(idx));
            for (LangKeyEntry candidate : fallbackEntries.values()) {
                if (!candidate.hasNonBlankContent()) {
                    continue;
                }
                LangKeyEntry inBase = baseEntries.get(candidate.key());
                if (inBase == null) {
                    missingEntriesToAppend.add(candidate);
                    baseEntries.put(candidate.key(), candidate);
                } else if (!inBase.hasNonBlankContent() && !blankKeyReplacements.containsKey(candidate.key())) {
                    blankKeyReplacements.put(candidate.key(), candidate.rawValueSlice());
                }
            }
        }

        String result = base;
        if (!blankKeyReplacements.isEmpty()) {
            Map<String, LangKeyEntry> currentBase = extractLangKeyEntries(result);
            StringBuilder sb = new StringBuilder(result.length() + 128);
            int cursor = 0;
            for (LangKeyEntry entry : currentBase.values()) {
                String replacementVal = blankKeyReplacements.get(entry.key());
                if (replacementVal != null) {
                    sb.append(result, cursor, entry.valueStart());
                    sb.append(replacementVal);
                    cursor = entry.valueEnd();
                }
            }
            sb.append(result, cursor, result.length());
            result = sb.toString();
        }

        if (!missingEntriesToAppend.isEmpty()) {
            int lastBrace = result.lastIndexOf('}');
            StringBuilder appended = new StringBuilder(result.length() + missingEntriesToAppend.size() * 48);
            if (lastBrace >= 0) {
                appended.append(result, 0, lastBrace);
                if (lastBrace > 0 && result.charAt(lastBrace - 1) != '\n') {
                    appended.append('\n');
                }
                for (LangKeyEntry missing : missingEntriesToAppend) {
                    appended.append('\t').append(missing.rawKeyToken()).append(": ").append(missing.rawValueSlice()).append('\n');
                }
                appended.append(result.substring(lastBrace));
            } else {
                appended.append("{\n");
                for (LangKeyEntry missing : missingEntriesToAppend) {
                    appended.append('\t').append(missing.rawKeyToken()).append(": ").append(missing.rawValueSlice()).append('\n');
                }
                appended.append("}\n");
            }
            result = appended.toString();
        }
        return result;
    }

    /**
     * Reads an FTB Quests {@code lang/<locale>.snbt} document as {@code key -> values}. A plain string
     * value becomes a single-element list; a string array keeps every element (blank ones included) so
     * two locales of the same key can be aligned element by element.
     */
    public static Map<String, List<String>> extractLangValues(String snbt) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (snbt == null || snbt.isEmpty()) {
            return result;
        }
        for (LangKeyEntry entry : extractLangKeyEntries(snbt).values()) {
            String slice = entry.rawValueSlice();
            List<String> values = new ArrayList<>();
            int p = 0;
            int len = slice.length();
            while (p < len) {
                char ch = slice.charAt(p);
                if (ch == '"' || ch == '\'') {
                    ParsedLiteral lit = readQuotedLiteral(slice, p);
                    values.add(lit.unescaped);
                    p = lit.endIndex;
                } else {
                    p++;
                }
            }
            if (!values.isEmpty()) {
                result.put(entry.key(), values);
            }
        }
        return result;
    }

    private record LangKeyEntry(
            String key,
            String rawKeyToken,
            int valueStart,
            int valueEnd,
            String rawValueSlice,
            boolean hasNonBlankContent) {
    }

    private static Map<String, LangKeyEntry> extractLangKeyEntries(String snbt) {
        Map<String, LangKeyEntry> map = new LinkedHashMap<>();
        int len = snbt.length();
        int i = 0;
        while (i < len) {
            char c = snbt.charAt(i);
            if (c == '#' || (c == '/' && i + 1 < len && snbt.charAt(i + 1) == '/')) {
                i = skipLineComment(snbt, i);
                continue;
            }
            if (c == '"' || c == '\'') {
                ParsedLiteral keyLit = readQuotedLiteral(snbt, i);
                int afterKey = skipWhitespaceAndComments(snbt, keyLit.endIndex);
                if (afterKey < len && snbt.charAt(afterKey) == ':') {
                    String rawKeyToken = snbt.substring(i, keyLit.endIndex);
                    i = recordLangKeyValue(snbt, keyLit.unescaped, rawKeyToken, afterKey + 1, map);
                } else {
                    i = keyLit.endIndex;
                }
                continue;
            }
            if (isIdentifierStart(c) || (c >= '0' && c <= '9')) {
                int idEnd = readIdentifierEnd(snbt, i);
                if (idEnd > i) {
                    String ident = snbt.substring(i, idEnd);
                    int afterKey = skipWhitespaceAndComments(snbt, idEnd);
                    if (afterKey < len && snbt.charAt(afterKey) == ':') {
                        i = recordLangKeyValue(snbt, ident, ident, afterKey + 1, map);
                    } else {
                        i = idEnd;
                    }
                    continue;
                }
            }
            i++;
        }
        return map;
    }

    private static int recordLangKeyValue(String snbt, String key, String rawKeyToken,
                                          int afterColon, Map<String, LangKeyEntry> map) {
        int len = snbt.length();
        int valStart = skipWhitespaceAndComments(snbt, afterColon);
        if (valStart >= len) {
            return len;
        }
        char first = snbt.charAt(valStart);
        if (first == '"' || first == '\'') {
            ParsedLiteral valLit = readQuotedLiteral(snbt, valStart);
            String slice = snbt.substring(valStart, valLit.endIndex);
            boolean nonBlank = !valLit.unescaped.isBlank();
            map.putIfAbsent(key, new LangKeyEntry(key, rawKeyToken, valStart, valLit.endIndex, slice, nonBlank));
            return valLit.endIndex;
        }
        if (first == '[') {
            int p = valStart + 1;
            boolean nonBlank = false;
            while (p < len) {
                p = skipWhitespaceAndComments(snbt, p);
                if (p >= len) {
                    break;
                }
                char ch = snbt.charAt(p);
                if (ch == ']') {
                    int valEnd = p + 1;
                    String slice = snbt.substring(valStart, valEnd);
                    map.putIfAbsent(key, new LangKeyEntry(key, rawKeyToken, valStart, valEnd, slice, nonBlank));
                    return valEnd;
                }
                if (ch == '"' || ch == '\'') {
                    ParsedLiteral elem = readQuotedLiteral(snbt, p);
                    if (!elem.unescaped.isBlank()) {
                        nonBlank = true;
                    }
                    p = elem.endIndex;
                } else {
                    p++;
                }
            }
            return p;
        }
        return valStart;
    }

    /**
     * Extracts hardcoded {@code title}, {@code subtitle} and {@code description} string spans from a
     * chapter or reward_table {@code .snbt} file, ignoring {@code {translation.key}} references.
     */
    public static List<StringSpan> extractHardcodedQuestSpans(String snbt) {
        List<StringSpan> spans = new ArrayList<>();
        int len = snbt.length();
        int i = 0;
        while (i < len) {
            char c = snbt.charAt(i);
            if (c == '#' || (c == '/' && i + 1 < len && snbt.charAt(i + 1) == '/')) {
                i = skipLineComment(snbt, i);
                continue;
            }
            if (c == '"' || c == '\'') {
                ParsedLiteral lit = readQuotedLiteral(snbt, i);
                int afterKey = skipWhitespaceAndComments(snbt, lit.endIndex);
                if (afterKey < len && snbt.charAt(afterKey) == ':') {
                    i = handleKeyAfterColon(snbt, lit.unescaped, afterKey + 1, spans);
                } else {
                    i = lit.endIndex;
                }
                continue;
            }
            if (isIdentifierStart(c)) {
                int idEnd = readIdentifierEnd(snbt, i);
                String ident = snbt.substring(i, idEnd);
                int afterKey = skipWhitespaceAndComments(snbt, idEnd);
                if (afterKey < len && snbt.charAt(afterKey) == ':') {
                    i = handleKeyAfterColon(snbt, ident, afterKey + 1, spans);
                } else {
                    i = idEnd;
                }
                continue;
            }
            i++;
        }
        return spans;
    }

    private static int handleKeyAfterColon(String snbt, String key, int valueStart, List<StringSpan> spans) {
        int len = snbt.length();
        int pos = skipWhitespaceAndComments(snbt, valueStart);
        if (pos >= len) {
            return len;
        }
        char first = snbt.charAt(pos);
        if (HARDCODED_SCALAR_FIELDS.contains(key) && (first == '"' || first == '\'')) {
            ParsedLiteral val = readQuotedLiteral(snbt, pos);
            if (shouldTranslateHardcoded(val.unescaped)) {
                spans.add(new StringSpan(pos, val.endIndex, first, val.unescaped));
            }
            return val.endIndex;
        }
        if (HARDCODED_LIST_FIELDS.contains(key) && first == '[') {
            int p = pos + 1;
            while (p < len) {
                p = skipWhitespaceAndComments(snbt, p);
                if (p >= len) {
                    break;
                }
                char ch = snbt.charAt(p);
                if (ch == ']') {
                    return p + 1;
                }
                if (ch == '"' || ch == '\'') {
                    ParsedLiteral elem = readQuotedLiteral(snbt, p);
                    if (shouldTranslateHardcoded(elem.unescaped)) {
                        spans.add(new StringSpan(p, elem.endIndex, ch, elem.unescaped));
                    }
                    p = elem.endIndex;
                } else {
                    p++;
                }
            }
            return p;
        }
        return pos;
    }

    private static boolean shouldTranslateHardcoded(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (isTranslationKeyReference(text)) {
            return false;
        }
        return true;
    }

    /**
     * Rewrites {@code snbt} by replacing every span with the translated value returned by
     * {@code translator}, properly escaped as an SNBT double-quoted string literal ({@code \"}).
     */
    public static String rewriteSpans(String snbt, List<StringSpan> spans, Function<String, String> translator) {
        if (spans.isEmpty()) {
            return snbt;
        }
        StringBuilder out = new StringBuilder(snbt.length() + spans.size() * 16);
        int cursor = 0;
        for (StringSpan span : spans) {
            out.append(snbt, cursor, span.tokenStart());
            String translated = translator.apply(span.unescapedValue());
            if (translated == null || translated.isEmpty()) {
                translated = span.unescapedValue();
            }
            out.append('"').append(escapeSnbtDoubleQuoted(translated)).append('"');
            cursor = span.tokenEnd();
        }
        out.append(snbt, cursor, snbt.length());
        return out.toString();
    }

    /**
     * Escapes backslashes, double quotes and control characters so a translated value is always valid
     * inside an SNBT double-quoted literal.
     */
    public static String escapeSnbtDoubleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private record ParsedLiteral(String unescaped, int endIndex) {
    }

    private static ParsedLiteral readQuotedLiteral(String s, int quoteStart) {
        char quote = s.charAt(quoteStart);
        StringBuilder unescaped = new StringBuilder();
        int i = quoteStart + 1;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        unescaped.append('\\');
                        i += 2;
                    }
                    case '"' -> {
                        unescaped.append('"');
                        i += 2;
                    }
                    case '\'' -> {
                        unescaped.append('\'');
                        i += 2;
                    }
                    case 'n' -> {
                        unescaped.append('\n');
                        i += 2;
                    }
                    case 'r' -> {
                        unescaped.append('\r');
                        i += 2;
                    }
                    case 't' -> {
                        unescaped.append('\t');
                        i += 2;
                    }
                    case 'u' -> {
                        if (i + 6 <= len) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                unescaped.append((char) Integer.parseInt(hex, 16));
                                i += 6;
                                continue;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        unescaped.append(next);
                        i += 2;
                    }
                    default -> {
                        unescaped.append(next);
                        i += 2;
                    }
                }
                continue;
            }
            if (c == quote) {
                return new ParsedLiteral(unescaped.toString(), i + 1);
            }
            unescaped.append(c);
            i++;
        }
        return new ParsedLiteral(unescaped.toString(), len);
    }

    private static int skipLineComment(String s, int index) {
        int len = s.length();
        int i = index;
        while (i < len && s.charAt(i) != '\n') {
            i++;
        }
        return i < len ? i + 1 : len;
    }

    private static int skipHorizontalWhitespace(String s, int index) {
        int len = s.length();
        int i = index;
        while (i < len) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipWhitespaceAndComments(String s, int index) {
        int len = s.length();
        int i = index;
        while (i < len) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#' || (c == '/' && i + 1 < len && s.charAt(i + 1) == '/')) {
                i = skipLineComment(s, i);
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static int readIdentifierEnd(String s, int start) {
        int len = s.length();
        int i = start;
        while (i < len) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}

package com.ftbtranslator.core.text;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Result of {@link TokenMasker#mask(String)}: text safe to send to an AI plus the data needed to
 * rebuild the original technical tokens.
 *
 * @param masked   trimmed text with protected tokens replaced by {@code __TK0__}, {@code __TK1__}...
 * @param tokens   original token text, indexed by placeholder number
 * @param leading  whitespace stripped from the start (re-applied after translation)
 * @param trailing whitespace stripped from the end (re-applied after translation)
 */
public record MaskedText(String masked, List<String> tokens, String leading, String trailing) {

    /** Technical strings that should never be sent to an AI (IDs, URLs, file paths, pure units). */
    private static final Pattern TECHNICAL_IDENTIFIER = Pattern.compile(
            "^(?:https?://\\S+|discord\\.gg/\\S+|[a-z0-9_.-]+:[a-z0-9_/.-]+|[a-z0-9_]+(?:\\.[a-z0-9_]+){2,}|[A-Za-z0-9_/-]+\\.(?:png|json|snbt|ogg|ttf|mcmeta))$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "^[+-]?\\d+(?:[.,]\\d+)?\\s*(?:%|x|s|ms|t|m|km|blocks?|px|mb|gb|fe|fe/t|eu|eu/t|rf|rf/t|mb/t|hp|mp|xp|lv|lvl)?$",
            Pattern.CASE_INSENSITIVE);

    public MaskedText {
        tokens = List.copyOf(tokens);
    }

    /**
     * True when, ignoring placeholders, there is genuine human-readable text worth translating (skipping
     * technical identifiers, URLs, single-letter hotkeys and numeric units so zero tokens are wasted).
     */
    public boolean hasTranslatableContent() {
        String trimmedRaw = masked.strip();
        if (trimmedRaw.isEmpty() || TECHNICAL_IDENTIFIER.matcher(trimmedRaw).matches()) {
            return false;
        }
        String withoutTokens = TokenMasker.PLACEHOLDER_PATTERN.matcher(trimmedRaw).replaceAll(" ").strip();
        if (withoutTokens.isEmpty() || NUMBER_WITH_UNIT.matcher(withoutTokens).matches()) {
            return false;
        }
        int letterCount = 0;
        boolean hasNonAsciiLetter = false;
        for (int i = 0; i < withoutTokens.length(); ) {
            int cp = withoutTokens.codePointAt(i);
            if (Character.isLetter(cp)) {
                letterCount++;
                if (cp > 127) {
                    hasNonAsciiLetter = true;
                }
            }
            i += Character.charCount(cp);
        }
        // Single ASCII letter (like keybind "W", "A", "S", "D", "X", "I", "V") does not need translation
        if (letterCount == 0 || (letterCount == 1 && !hasNonAsciiLetter)) {
            return false;
        }
        // Pure snake_case identifier without spaces (e.g. "magic_ore_block")
        if (withoutTokens.indexOf(' ') < 0 && withoutTokens.indexOf('_') >= 0) {
            return false;
        }
        return true;
    }
}

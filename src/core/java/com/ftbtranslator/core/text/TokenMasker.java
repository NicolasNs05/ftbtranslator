package com.ftbtranslator.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic protection of technical tokens before text is sent to an AI model.
 * <p>
 * Every match of {@link #TOKEN_PATTERN} is replaced by a short placeholder ({@code __TK0__},
 * {@code __TK1__}...) and restored after translation. If the model drops, invents or corrupts a
 * placeholder the translation is rejected ({@link #unmask} returns {@code null}) so a broken string is
 * never written to disk.
 * <p>
 * Protected tokens:
 * <ul>
 *   <li>FTB Quests tags: {@code {image:...}}, {@code {@pagebreak}} and key references {@code {some.key}}</li>
 *   <li>Patchouli macros: {@code $(...)} and {@code $()}</li>
 *   <li>Java placeholders: {@code %s}, {@code %d}, {@code %1$s}, {@code %2$d}, {@code %.1f}, {@code %%}</li>
 *   <li>Minecraft formatting: {@code &0-&9 &a-&f &k-&r}, {@code §0-§r}, hex {@code &#RRGGBB} / {@code §x§R§R§G§G§B§B}</li>
 *   <li>Line breaks (kept exactly where the source had them)</li>
 * </ul>
 */
public final class TokenMasker {

    /** Order matters: longer / more specific alternatives first. */
    static final Pattern TOKEN_PATTERN = Pattern.compile(String.join("|",
            "\\{@pagebreak\\}",
            "\\{image:[^{}]*\\}",
            "\\{@?[A-Za-z0-9_][^{}\\s]*\\}",
            "\\$\\([^()]*\\)",
            "%%",
            "%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[sdfxXcS]",
            "[&§]#[0-9a-fA-F]{6}",
            "§[xX](?:§[0-9a-fA-F]){6}",
            "[&§][0-9a-fk-orA-FK-OR]",
            "\\r?\\n",
            "\\t",
            "_{2}TK\\d+_{2}"));

    /** Placeholder as produced by {@link #mask}. */
    static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("__TK\\d+__");

    /** Tolerant placeholder matcher for model output ("__TK3__", "__ TK3 __", "__tk3__"). */
    private static final Pattern RESTORE_PATTERN = Pattern.compile("_{2}\\s*[Tt][Kk]\\s*(\\d+)\\s*_{2}");

    /**
     * Matches an unindexed Java printf specifier (e.g. {@code %s}, {@code %d}, {@code %.1f}) so strings
     * containing 2+ unindexed specifiers can be upgraded to positional form ({@code %1$s}, {@code %2$d}).
     */
    private static final Pattern UNINDEXED_FORMAT_SPECIFIER = Pattern.compile(
            "^%([-#+0,(]*\\d*(?:\\.\\d+)?[sdfxXcS])$");

    public MaskedText mask(String input) {
        String text = input == null ? "" : input;
        int start = 0;
        int end = text.length();
        while (start < end && isPlainWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && isPlainWhitespace(text.charAt(end - 1))) {
            end--;
        }
        String leading = text.substring(0, start);
        String trailing = text.substring(end);
        String core = text.substring(start, end);

        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder(core.length() + 16);
        Matcher m = TOKEN_PATTERN.matcher(core);
        int last = 0;
        int unindexedFormatCount = 0;
        while (m.find()) {
            sb.append(core, last, m.start());
            sb.append("__TK").append(tokens.size()).append("__");
            String tokenVal = m.group();
            if (UNINDEXED_FORMAT_SPECIFIER.matcher(tokenVal).matches()) {
                unindexedFormatCount++;
            }
            tokens.add(tokenVal);
            last = m.end();
        }
        sb.append(core, last, core.length());

        // When 2 or more unindexed placeholders (%s, %d, %f...) exist in the same string, convert them
        // to positional specifiers (%1$s, %2$d...) so grammatical reordering by the AI remains valid.
        if (unindexedFormatCount >= 2) {
            int pos = 1;
            for (int i = 0; i < tokens.size(); i++) {
                Matcher fm = UNINDEXED_FORMAT_SPECIFIER.matcher(tokens.get(i));
                if (fm.matches()) {
                    tokens.set(i, "%" + (pos++) + "$" + fm.group(1));
                }
            }
        }

        return new MaskedText(sb.toString(), tokens, leading, trailing);
    }

    /**
     * Restores the original tokens inside a translated string.
     *
     * @return the restored text, or {@code null} when placeholders are missing / unknown (translation rejected)
     */
    public String unmask(String translated, MaskedText source) {
        if (translated == null) {
            return null;
        }
        // Trim whatever whitespace the model added; real line breaks live inside placeholders.
        translated = translated.strip();
        if (translated.isEmpty()) {
            return null;
        }
        List<String> tokens = source.tokens();
        boolean[] seen = new boolean[tokens.size()];
        StringBuilder sb = new StringBuilder(translated.length() + 32);
        Matcher m = RESTORE_PATTERN.matcher(translated);
        int last = 0;
        while (m.find()) {
            int index;
            try {
                index = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 0 || index >= tokens.size()) {
                return null;
            }
            seen[index] = true;
            sb.append(translated, last, m.start());
            sb.append(tokens.get(index));
            last = m.end();
        }
        sb.append(translated, last, translated.length());
        for (boolean s : seen) {
            if (!s) {
                return null;
            }
        }
        return source.leading() + sb + source.trailing();
    }

    private static boolean isPlainWhitespace(char c) {
        return c == ' ' || c == '\u00A0';
    }
}

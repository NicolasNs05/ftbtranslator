package com.ftbtranslator.core.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory glossary of <b>human-made</b> translations ({@code source -> target}) harvested from the
 * official target-language files that mods (and, later, resource packs) already ship.
 * <p>
 * It is used in two ways by {@link com.ftbtranslator.core.TranslationService}:
 * <ol>
 *   <li><b>Exact match:</b> a string identical to a known source (including strings wrapped in outer
 *       Minecraft formatting codes like {@code §6...§r}) is resolved locally, costing zero tokens.</li>
 *   <li><b>Prompt terms:</b> meaningful domain terms (1-4 words, excluding basic English stopwords and
 *       identical {@code source == target} pairs) found inside a batch are injected into the system prompt
 *       so the AI reuses the same terminology chosen by human translators.</li>
 * </ol>
 * When different sources disagree on a translation, the most frequent one wins (ties keep the first seen).
 * The glossary is rebuilt on every run, so it never goes stale and is context-independent.
 */
public final class TranslationGlossary {

    /** Above this ratio of {@code target == source} pairs, a file is treated as an untranslated copy. */
    public static final double UNTRANSLATED_COPY_RATIO = 0.6;

    public static final int MAX_TERM_WORDS = 4;
    public static final int MAX_TERM_CHARS = 40;
    public static final int MIN_TERM_CHARS = 3;

    /** Plain words only (letters, digits, spaces, apostrophes, hyphens): no placeholders or formatting. */
    private static final Pattern PLAIN_TERM = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]*");
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}'\\-]+");

    /**
     * Matches strings wrapped only in leading Minecraft color/style codes ({@code §a}, {@code &6},
     * {@code &#RRGGBB}, {@code §x§R§R§G§G§B§B}) and optional trailing reset codes ({@code §r}, {@code &r}).
     */
    private static final Pattern OUTER_FORMATTING_WRAPPER = Pattern.compile(
            "^((?:(?:[&§]#[0-9a-fA-F]{6}|§[xX](?:§[0-9a-fA-F]){6}|[&§][0-9a-fk-orA-FK-OR])\\s*)*)"
                    + "(.+?)"
                    + "((?:\\s*[&§][rR])*)$",
            Pattern.DOTALL);

    /**
     * Common single-word English stopwords and generic UI labels that appear as standalone buttons in mods
     * ("Not", "From", "All", "Any", "Other", "Add", "Name", "Value"...). They still work for 100% exact
     * full-string lookups via {@link #lookup(String)}, but must never be injected as prompt hints inside
     * longer sentences (where they waste tokens and force wrong gender/plural agreement).
     */
    private static final Set<String> PROMPT_STOPWORDS = Set.of(
            "the", "and", "for", "not", "from", "with", "all", "any", "other", "others", "some", "none",
            "yes", "no", "true", "false", "on", "off", "in", "out", "to", "of", "by", "at", "as",
            "is", "are", "was", "were", "be", "been", "has", "have", "had", "can", "may", "will", "must",
            "should", "would", "could", "this", "that", "these", "those", "its", "his", "her", "our",
            "your", "their", "who", "what", "when", "where", "why", "how", "which", "each", "every",
            "both", "few", "more", "most", "less", "least", "many", "much", "such", "own", "same",
            "so", "than", "too", "very", "just", "only", "also", "even", "still", "already", "yet",
            "ever", "never", "always", "often", "once", "here", "there", "now", "then", "into", "onto",
            "upon", "over", "under", "above", "below", "between", "among", "through", "during", "before",
            "after", "since", "until", "while", "without", "within", "about", "against", "around",
            "near", "far", "up", "down", "left", "right", "back", "next", "previous", "first", "last",
            "new", "old", "good", "bad", "high", "low", "big", "small", "long", "short", "full", "empty",
            "open", "close", "closed", "start", "stop", "add", "remove", "delete", "clear", "reset",
            "save", "load", "edit", "copy", "paste", "search", "find", "show", "hide", "enable",
            "enabled", "disable", "disabled", "active", "inactive", "default", "custom", "general",
            "common", "normal", "basic", "advanced", "main", "info", "help", "error", "warning",
            "name", "names", "value", "values", "type", "types", "mode", "modes", "list", "lists",
            "page", "pages", "time", "times", "day", "days", "use", "used", "using", "set", "sets",
            "get", "gets", "make", "made", "take", "give", "keep", "put", "let", "see", "look",
            "come", "go", "went", "gone", "know", "think", "want", "need", "try", "click", "press",
            "hold", "shift", "ctrl", "alt", "button", "key", "keys", "slot", "slots", "tab", "tabs",
            "menu", "gui", "hud", "config", "configs", "setting", "settings", "option", "options",
            "client", "server", "world", "player", "players", "entity", "entities", "item", "items",
            "block", "blocks", "mod", "mods", "tag", "tags", "id", "ids", "max", "min", "maximum",
            "minimum", "total", "amount", "count", "number", "size", "width", "height", "color",
            "colors", "sound", "sounds", "music", "volume", "text", "title", "description",
            "effect", "effects", "level", "levels", "target", "targets");

    /** source -> (target -> votes), insertion ordered so ties keep the first seen translation. */
    private final Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
    /** lowercase stripped source -> first seen original source key in {@link #votes}. */
    private final Map<String, String> caseInsensitiveIndex = new HashMap<>();
    /** normalized short source term -> original source key in {@link #votes}. */
    private final Map<String, String> termIndex = new HashMap<>();

    /**
     * Adds every key present in both maps as a {@code source -> target} pair.
     * Files whose values are mostly copies of the source text are ignored entirely.
     *
     * @return number of pairs accepted
     */
    public synchronized int addLangPair(Map<String, String> sourceByKey, Map<String, String> targetByKey) {
        if (sourceByKey == null || targetByKey == null || sourceByKey.isEmpty() || targetByKey.isEmpty()) {
            return 0;
        }
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        int identical = 0;
        for (Map.Entry<String, String> e : targetByKey.entrySet()) {
            String src = sourceByKey.get(e.getKey());
            String tgt = e.getValue();
            if (src == null || tgt == null || src.isBlank() || tgt.isBlank()) {
                continue;
            }
            if (src.strip().equalsIgnoreCase(tgt.strip())) {
                identical++;
            }
            pairs.add(Map.entry(src, tgt));
        }
        if (pairs.isEmpty() || (double) identical / pairs.size() > UNTRANSLATED_COPY_RATIO) {
            return 0;
        }
        for (Map.Entry<String, String> p : pairs) {
            addPair(p.getKey(), p.getValue());
        }
        return pairs.size();
    }

    /** Registers a single human translation. Blank values are ignored. */
    public synchronized void addPair(String source, String target) {
        if (source == null || target == null || source.isBlank() || target.isBlank()) {
            return;
        }
        votes.computeIfAbsent(source, k -> new LinkedHashMap<>()).merge(target, 1, Integer::sum);
        String trimmed = source.strip();
        caseInsensitiveIndex.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), source);
        if (isTermCandidate(trimmed)) {
            termIndex.putIfAbsent(normalize(trimmed), source);
        }
    }

    /** Number of distinct source strings known. */
    public synchronized int size() {
        return votes.size();
    }

    public synchronized boolean isEmpty() {
        return votes.isEmpty();
    }

    /** Best human translation for {@code source} (exact case first, then case-insensitive), or {@code null}. */
    public synchronized String lookup(String source) {
        if (source == null) {
            return null;
        }
        Map<String, Integer> candidates = votes.get(source);
        if (candidates == null || candidates.isEmpty()) {
            String canonicalKey = caseInsensitiveIndex.get(source.strip().toLowerCase(Locale.ROOT));
            if (canonicalKey != null) {
                candidates = votes.get(canonicalKey);
            }
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String best = null;
        int bestVotes = 0;
        for (Map.Entry<String, Integer> c : candidates.entrySet()) {
            if (c.getValue() > bestVotes) {
                best = c.getKey();
                bestVotes = c.getValue();
            }
        }
        return best;
    }

    /**
     * Looks up {@code source} in the glossary, also matching when {@code source} is wrapped in leading
     * Minecraft color/style codes ({@code §6}, {@code &a}, {@code &#RRGGBB}) and/or trailing reset codes
     * ({@code §r}, {@code &r}) around a known human-translated core string.
     */
    public synchronized String lookupPreservingOuterFormatting(String source) {
        if (source == null) {
            return null;
        }
        String direct = lookup(source);
        if (direct != null) {
            return direct;
        }
        Matcher m = OUTER_FORMATTING_WRAPPER.matcher(source);
        if (!m.matches()) {
            return null;
        }
        String prefix = m.group(1);
        String core = m.group(2);
        String suffix = m.group(3);
        if (prefix.isEmpty() && suffix.isEmpty()) {
            return null;
        }
        String coreTranslation = lookup(core);
        if (coreTranslation == null) {
            return null;
        }
        return prefix + coreTranslation + suffix;
    }

    /**
     * Finds short glossary terms (1 to {@value #MAX_TERM_WORDS} words) that appear as whole words inside
     * {@code texts}, ordered by first appearance. Matching is case-insensitive.
     * <p>
     * Filters out identical pairs ({@code source == target}) and trivial single-word stopwords, and when
     * a batch contains more candidate terms than {@code maxTerms}, prioritizes multi-word domain terms
     * (2-4 words) so compound lore/mod names are never crowded out by 1-word terms.
     *
     * @return ordered map {@code source term -> human translation}, at most {@code maxTerms} entries
     */
    public synchronized Map<String, String> termsFor(Collection<String> texts, int maxTerms) {
        Map<String, String> allFound = new LinkedHashMap<>();
        if (texts == null || maxTerms <= 0 || termIndex.isEmpty()) {
            return allFound;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String[] words = WORD_SPLIT.split(text.toLowerCase(Locale.ROOT));
            for (int start = 0; start < words.length; start++) {
                if (words[start].isEmpty()) {
                    continue;
                }
                StringBuilder gram = new StringBuilder();
                for (int len = 1; len <= MAX_TERM_WORDS && start + len <= words.length; len++) {
                    String w = words[start + len - 1];
                    if (w.isEmpty()) {
                        break;
                    }
                    if (len > 1) {
                        gram.append(' ');
                    }
                    gram.append(w);
                    String normalizedGram = gram.toString();
                    String sourceKey = termIndex.get(normalizedGram);
                    if (sourceKey == null) {
                        continue;
                    }
                    String cleanSource = sourceKey.strip();
                    if (allFound.containsKey(cleanSource)) {
                        continue;
                    }
                    // A term identical to the whole text is an exact match, not a hint.
                    if (cleanSource.equalsIgnoreCase(text.strip())) {
                        continue;
                    }
                    String target = lookup(sourceKey);
                    if (target == null) {
                        continue;
                    }
                    String cleanTarget = target.strip();
                    // Never waste prompt tokens or glossary slots on untranslated/identical terms ("Item = Item").
                    if (cleanSource.equalsIgnoreCase(cleanTarget)) {
                        continue;
                    }
                    allFound.put(cleanSource, cleanTarget);
                }
            }
        }
        if (allFound.size() <= maxTerms) {
            return allFound;
        }
        // When a large batch has more candidates than maxTerms, prioritize multi-word compound terms
        // (e.g. "Iron Plate", "Unique Skill", "Demon Lord") first, then fill with 1-word domain terms.
        Map<String, String> prioritized = new LinkedHashMap<>(maxTerms);
        for (Map.Entry<String, String> e : allFound.entrySet()) {
            if (e.getKey().indexOf(' ') >= 0) {
                prioritized.put(e.getKey(), e.getValue());
                if (prioritized.size() >= maxTerms) {
                    return prioritized;
                }
            }
        }
        for (Map.Entry<String, String> e : allFound.entrySet()) {
            if (e.getKey().indexOf(' ') < 0) {
                prioritized.put(e.getKey(), e.getValue());
                if (prioritized.size() >= maxTerms) {
                    return prioritized;
                }
            }
        }
        return prioritized;
    }

    private static boolean isTermCandidate(String trimmed) {
        if (trimmed.length() < MIN_TERM_CHARS || trimmed.length() > MAX_TERM_CHARS) {
            return false;
        }
        if (!PLAIN_TERM.matcher(trimmed).matches()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length > MAX_TERM_WORDS) {
            return false;
        }
        if (parts.length == 1 && PROMPT_STOPWORDS.contains(parts[0].toLowerCase(Locale.ROOT))) {
            return false;
        }
        return true;
    }

    private static String normalize(String term) {
        return String.join(" ", WORD_SPLIT.split(term.toLowerCase(Locale.ROOT))).strip();
    }
}

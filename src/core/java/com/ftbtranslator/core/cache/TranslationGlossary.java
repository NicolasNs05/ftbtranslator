package com.ftbtranslator.core.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * In-memory glossary of <b>human-made</b> translations ({@code source -> target}) harvested from the
 * official target-language files that mods (and, later, resource packs) already ship.
 * <p>
 * It is used in two ways by {@link com.ftbtranslator.core.TranslationService}:
 * <ol>
 *   <li><b>Exact match:</b> a string identical to a known source is resolved locally, costing zero tokens.</li>
 *   <li><b>Prompt terms:</b> short terms (1-4 words) found inside a batch are injected into the system
 *       prompt so the AI reuses the same terminology chosen by human translators.</li>
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

    /** source -> (target -> votes), insertion ordered so ties keep the first seen translation. */
    private final Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
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

    /** Best human translation for exactly {@code source}, or {@code null} when unknown. */
    public synchronized String lookup(String source) {
        if (source == null) {
            return null;
        }
        Map<String, Integer> candidates = votes.get(source);
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
     * Finds short glossary terms (1 to {@value #MAX_TERM_WORDS} words) that appear as whole words inside
     * {@code texts}, ordered by first appearance. Matching is case-insensitive.
     *
     * @return ordered map {@code source term -> human translation}, at most {@code maxTerms} entries
     */
    public synchronized Map<String, String> termsFor(Collection<String> texts, int maxTerms) {
        Map<String, String> found = new LinkedHashMap<>();
        if (texts == null || maxTerms <= 0 || termIndex.isEmpty()) {
            return found;
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
                    String sourceKey = termIndex.get(gram.toString());
                    if (sourceKey == null || found.containsKey(sourceKey.strip())) {
                        continue;
                    }
                    // A term identical to the whole text is an exact match, not a hint.
                    if (sourceKey.strip().equalsIgnoreCase(text.strip())) {
                        continue;
                    }
                    String target = lookup(sourceKey);
                    if (target != null) {
                        found.put(sourceKey.strip(), target.strip());
                        if (found.size() >= maxTerms) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }

    private static boolean isTermCandidate(String trimmed) {
        if (trimmed.length() < MIN_TERM_CHARS || trimmed.length() > MAX_TERM_CHARS) {
            return false;
        }
        if (!PLAIN_TERM.matcher(trimmed).matches()) {
            return false;
        }
        return trimmed.split("\\s+").length <= MAX_TERM_WORDS;
    }

    private static String normalize(String term) {
        return String.join(" ", WORD_SPLIT.split(term.toLowerCase(Locale.ROOT))).strip();
    }
}

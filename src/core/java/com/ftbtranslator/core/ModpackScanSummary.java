package com.ftbtranslator.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Dry-run X-Ray summary of the modpack produced by {@link TranslationService#scanPreviewSynchronously}
 * without calling any AI API or spending tokens.
 *
 * @param filesByScope            number of eligible output files found per translation scope
 * @param pendingStringsByScope   unique strings still needing AI translation per scope
 * @param totalUniqueStrings      total unique translatable strings across all enabled scopes
 * @param resolvedFromCache       unique strings already resolved by the local cache or technical filter
 * @param resolvedFromGlossary    unique strings resolved for free by the official mod glossary
 * @param stringsToTranslateWithAi unique masked templates that will be sent to the AI
 * @param estimatedBatches        number of adaptive batches that would be sent to the AI
 * @param estimatedTokens         approximate total tokens (input + output + system prompts) for the run
 */
public record ModpackScanSummary(
        Map<TranslationScope, Integer> filesByScope,
        Map<TranslationScope, Integer> pendingStringsByScope,
        int totalUniqueStrings,
        int resolvedFromCache,
        int resolvedFromGlossary,
        int stringsToTranslateWithAi,
        int estimatedBatches,
        long estimatedTokens) {

    public ModpackScanSummary {
        filesByScope = filesByScope == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(filesByScope));
        pendingStringsByScope = pendingStringsByScope == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(pendingStringsByScope));
    }

    public int filesFor(TranslationScope scope) {
        return filesByScope.getOrDefault(scope, 0);
    }

    public int totalFiles() {
        return filesByScope.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int pendingStringsFor(TranslationScope scope) {
        return pendingStringsByScope.getOrDefault(scope, 0);
    }
}

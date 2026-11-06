package com.ftbtranslator.core;

/**
 * Immutable progress state published by the translation pipeline.
 *
 * @param phase            human-readable phase ("Escaneando", "Traduzindo com IA", "Gravando arquivos"...)
 * @param currentItem      file / task currently being processed
 * @param batchIndex       1-based index of the batch being sent to the AI (0 when idle)
 * @param batchTotal       planned number of batches
 * @param stringsTotal     unique strings that need an AI translation in this run
 * @param stringsDone      strings already processed by the AI in this run
 * @param stringsFromCache unique strings resolved locally without the AI (cache, official glossary and
 *                         technical strings that need no translation)
 * @param stringsFromGlossary subset of {@code stringsFromCache} resolved by the official mod glossary
 * @param stringsFailed    strings whose translation was rejected (kept in the original language)
 * @param tasksTotal       output files that will be generated
 * @param tasksWritten     output files already written
 * @param running          whether a run is active
 * @param paused           whether the run is paused
 */
public record ProgressSnapshot(
        String phase,
        String currentItem,
        int batchIndex,
        int batchTotal,
        int stringsTotal,
        int stringsDone,
        int stringsFromCache,
        int stringsFromGlossary,
        int stringsFailed,
        int tasksTotal,
        int tasksWritten,
        boolean running,
        boolean paused) {

    public static ProgressSnapshot idle() {
        return new ProgressSnapshot("Aguardando", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);
    }

    /** Strings resolved locally by the cache (and technical filter), excluding glossary hits. */
    public int stringsFromCacheOnly() {
        return Math.max(0, stringsFromCache - stringsFromGlossary);
    }

    /** Strings the AI actually translated successfully in this run. */
    public int stringsTranslatedByAi() {
        return Math.max(0, stringsDone - stringsFailed);
    }

    /**
     * Smooth completion ratio in [0, 1] combining batch progress, resolved strings (including cache hits)
     * and written files so the bar advances visibly on every batch.
     */
    public double fraction() {
        if (!running && tasksTotal > 0 && tasksWritten >= tasksTotal) {
            return 1.0;
        }
        double batchPart = 0.0;
        if (batchTotal > 0) {
            double doneBatches = stringsTotal > 0
                    ? (double) stringsDone / stringsTotal
                    : (double) Math.max(0, batchIndex - 1) / batchTotal;
            double inFlightBonus = running && batchIndex > 0
                    ? 0.35 / batchTotal
                    : 0.0;
            batchPart = Math.min(1.0, doneBatches + inFlightBonus);
        } else if (stringsFromCache > 0 && stringsTotal == 0) {
            batchPart = 1.0;
        }

        double writePart = tasksTotal <= 0 ? 0.0 : (double) tasksWritten / tasksTotal;
        if (batchTotal <= 0) {
            return clamp(writePart);
        }
        return clamp(batchPart * 0.90 + writePart * 0.10);
    }

    public ProgressSnapshot withPaused(boolean newPaused) {
        return new ProgressSnapshot(phase, currentItem, batchIndex, batchTotal, stringsTotal, stringsDone,
                stringsFromCache, stringsFromGlossary, stringsFailed, tasksTotal, tasksWritten, running, newPaused);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

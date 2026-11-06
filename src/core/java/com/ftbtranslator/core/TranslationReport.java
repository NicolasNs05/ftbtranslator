package com.ftbtranslator.core;

import java.util.Set;

/**
 * Final summary of a translation run.
 *
 * @param success             true when the run reached the end without a fatal error
 * @param cancelled           true when the user cancelled the run
 * @param message             summary line in Portuguese
 * @param scopes              fronts that were processed
 * @param tasksTotal          output files planned
 * @param tasksWritten        output files written
 * @param stringsTranslated   strings translated by the AI in this run
 * @param stringsFromCache    unique strings served from cache
 * @param stringsFailed       strings left untranslated
 * @param questsChanged       FTB Quests files were (re)generated, a {@code /ftbquests reload} is advisable
 * @param resourcePackChanged the generated resource pack was (re)written
 * @param durationMillis      wall-clock duration
 */
public record TranslationReport(
        boolean success,
        boolean cancelled,
        String message,
        Set<TranslationScope> scopes,
        int tasksTotal,
        int tasksWritten,
        int stringsTranslated,
        int stringsFromCache,
        int stringsFailed,
        boolean questsChanged,
        boolean resourcePackChanged,
        long durationMillis) {

    /** Whether something worth applying (pack reload / quests reload) was produced. */
    public boolean producedOutput() {
        return tasksWritten > 0;
    }
}

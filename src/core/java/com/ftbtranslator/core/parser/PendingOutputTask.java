package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.TranslationScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a single output file planned during the scan phase of a translation run.
 */
public interface PendingOutputTask {

    /** Which translation front created this task. */
    TranslationScope scope();

    /** Short description shown on the progress bar ("config/ftbquests/quests/lang/pt_br.snbt"). */
    String displayPath();

    /** Target path on disk that will be written when {@link #writeOutput(Function)} executes. */
    Path outputPath();

    /** Registers all source strings needed by this file into the global collector. */
    void collectStrings(Consumer<String> collector);

    /** Writes the translated file using the resolved {@code source -> translated} lookup function. */
    void writeOutput(Function<String, String> translator) throws IOException;
}

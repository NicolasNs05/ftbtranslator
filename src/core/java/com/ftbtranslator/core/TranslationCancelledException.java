package com.ftbtranslator.core;

/**
 * Thrown inside the pipeline when the user cancels the run.
 */
public final class TranslationCancelledException extends RuntimeException {
    public TranslationCancelledException() {
        super("Tradução cancelada pelo usuário.");
    }
}

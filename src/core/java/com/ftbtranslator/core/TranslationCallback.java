package com.ftbtranslator.core;

/**
 * The only channel the core uses to talk to the outside world (GUI, chat, logs...).
 * <p>
 * Implementations must be thread-safe: the core invokes these methods from its worker thread.
 */
public interface TranslationCallback {

    /** Real-time progress update (called frequently; keep it cheap). */
    void onProgress(ProgressSnapshot snapshot);

    /** Human-readable log line in Portuguese. */
    void onLog(LogLevel level, String message);

    /** The run finished (successfully, partially or cancelled). */
    void onComplete(TranslationReport report);

    /** The run aborted because of a fatal error (invalid API key, quota exhausted, I/O failure...). */
    void onError(String message, Throwable cause);
}

package com.jinloes.prpilot.benchmark;

/**
 * One review finding anchored to a file, from either reviewer. {@code line} is 0 when the finding
 * is file-level.
 */
record Finding(String id, String path, int line, String body) {}

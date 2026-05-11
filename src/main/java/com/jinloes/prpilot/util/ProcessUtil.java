package com.jinloes.prpilot.util;

import java.io.File;
import java.util.List;

public final class ProcessUtil {

    private ProcessUtil() {}

    /**
     * Returns the first path in {@code candidates} that points to an existing file, or {@code name}
     * as a fallback for OS PATH resolution.
     */
    public static String findBinary(String name, List<String> candidates) {
        return candidates.stream().filter(p -> new File(p).isFile()).findFirst().orElse(name);
    }
}

package com.jinloes.prpilot.review;

/** Shared diff-building fixtures for the {@link TriggeredChecklists} tests. */
final class TriggeredChecklistsTestSupport {
    private TriggeredChecklistsTestSupport() {}

    static String fileDiff(String path, String... hunkLines) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n@@ -1,1 +1,1 @@\n"
                + String.join("\n", hunkLines)
                + "\n";
    }

    static String deletedFileDiff(String path, String removedLine) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\ndeleted file mode 100644\n--- a/"
                + path
                + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-"
                + removedLine
                + "\n";
    }

    static String sectionFor(String... fileDiffs) {
        return TriggeredChecklists.sectionFor(
                InspectionManifest.fromDiff(String.join("", fileDiffs)).files());
    }

    static String changed(String path) {
        return sectionFor(fileDiff(path, "-old", "+new"));
    }
}

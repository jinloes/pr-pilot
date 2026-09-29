package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parses the externally visible review plus the engine-internal inspection ledger. */
final class ReviewPassParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewPassParser() {}

    static ReviewPassResult parse(String raw, InspectionManifest manifest, File workingDir)
            throws IOException {
        return parse(raw, manifest, workingDir, ClaudeService.MAX_LINE_COMMENTS);
    }

    static ReviewPassResult parse(
            String raw, InspectionManifest manifest, File workingDir, int maxComments)
            throws IOException {
        ReviewResult review = ClaudeService.parseReview(raw, maxComments);
        JsonNode root = parseRoot(raw);
        JsonNode inspection = root.path("inspection");
        if (!inspection.isObject()) {
            return ReviewPassResult.withoutLedger(review);
        }

        Set<String> inspectedTargets = new LinkedHashSet<>();
        JsonNode rawTargets = inspection.path("inspectedTargets");
        if (rawTargets.isArray()) {
            for (JsonNode target : rawTargets) {
                if (target.isTextual() && manifest.containsTarget(target.textValue())) {
                    inspectedTargets.add(target.textValue());
                }
            }
        }

        List<EvidenceRef> evidence = new ArrayList<>();
        JsonNode rawEvidence = inspection.path("evidence");
        if (rawEvidence.isArray()) {
            for (JsonNode item : rawEvidence) {
                EvidenceRef parsed = parseEvidence(item, review, manifest, workingDir);
                if (parsed != null) {
                    evidence.add(parsed);
                }
            }
        }
        return new ReviewPassResult(review, new InspectionLedger(true, inspectedTargets, evidence));
    }

    private static EvidenceRef parseEvidence(
            JsonNode item, ReviewResult review, InspectionManifest manifest, File workingDir) {
        if (!item.isObject()) {
            return null;
        }
        JsonNode indexNode = item.path("findingIndex");
        if (!indexNode.isIntegralNumber()) {
            return null;
        }
        int findingIndex = indexNode.intValue();
        if (findingIndex < 0 || findingIndex >= review.getLineComments().size()) {
            return null;
        }
        LineComment finding = review.getLineComments().get(findingIndex);
        Set<String> anchorTargets = new LinkedHashSet<>();
        manifest.hunkFor(finding.getFile(), finding.getLine())
                .ifPresent(
                        hunk -> {
                            anchorTargets.add(hunk.fileId());
                            anchorTargets.add(hunk.id());
                        });

        Set<String> targetIds = new LinkedHashSet<>();
        JsonNode targets = item.path("targetIds");
        if (targets.isArray()) {
            for (JsonNode target : targets) {
                if (target.isTextual() && manifest.containsTarget(target.textValue())) {
                    targetIds.add(target.textValue());
                }
            }
        }
        if (targetIds.stream().noneMatch(anchorTargets::contains)) {
            return null;
        }

        List<String> relatedFiles = new ArrayList<>();
        JsonNode files = item.path("relatedFiles");
        if (files.isArray()) {
            for (JsonNode file : files) {
                if (file.isTextual() && isConfinedPath(file.textValue(), workingDir)) {
                    relatedFiles.add(InspectionManifest.normalizePath(file.textValue()));
                }
            }
        }
        return new EvidenceRef(findingIndex, targetIds, relatedFiles);
    }

    private static boolean isConfinedPath(String rawPath, File workingDir) {
        if (!InspectionManifest.isSafeRelativePath(rawPath)) {
            return false;
        }
        if (workingDir == null) {
            return true;
        }
        Path root = workingDir.toPath().toAbsolutePath().normalize();
        Path candidate =
                root.resolve(InspectionManifest.normalizePath(rawPath))
                        .toAbsolutePath()
                        .normalize();
        return candidate.startsWith(root);
    }

    private static JsonNode parseRoot(String raw) throws IOException {
        String json = raw.trim();
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (newline > 0 && closing > newline) {
                json = json.substring(newline + 1, closing).trim();
            }
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        return JSON.readTree(json);
    }
}

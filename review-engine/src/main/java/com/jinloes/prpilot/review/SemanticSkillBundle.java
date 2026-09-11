package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only pinned packaged resources can become trusted prompt instructions. */
public final class SemanticSkillBundle {
    private static final String MANIFEST_SHA =
            "0c5aa0570a5c49bb11ff462ca66bef42a2eb5deac73bc3a31c4368a642a74fac";
    private static final String[] RESOURCES = {
        "LICENSE", "intellij-mcp-tools/SKILL.md", "intellij-code-intelligence/SKILL.md"
    };
    private final String instructions;

    private SemanticSkillBundle(String instructions) {
        this.instructions = instructions;
    }

    public static SemanticSkillBundle load() throws IOException {
        return load(
                path -> SemanticSkillBundle.class.getResourceAsStream("/semantic-skills/" + path));
    }

    @FunctionalInterface
    interface Resources {
        InputStream open(String path) throws IOException;
    }

    static SemanticSkillBundle load(Resources resources) throws IOException {
        byte[] manifest = read(resources, "manifest.json");
        if (!sha256(manifest).equals(MANIFEST_SHA))
            throw new IOException("Bundled skill manifest changed");
        var node = new ObjectMapper().readTree(manifest);
        Map<String, String> texts = new LinkedHashMap<>();
        for (String name : RESOURCES) {
            byte[] bytes = read(resources, name);
            if (!sha256(bytes).equals(node.path("resources").path(name).path("sha256").asText()))
                throw new IOException("Bundled skill integrity failure: " + name);
            texts.put(name, new String(bytes, StandardCharsets.UTF_8));
        }
        return new SemanticSkillBundle(texts.get(RESOURCES[1]) + "\n" + texts.get(RESOURCES[2]));
    }

    public String instructions() {
        return instructions;
    }

    private static byte[] read(Resources resources, String name) throws IOException {
        try (InputStream stream = resources.open(name)) {
            if (stream == null) throw new IOException("Missing bundled skill: " + name);
            byte[] bytes = stream.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Bundled skill limit");
            return bytes;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

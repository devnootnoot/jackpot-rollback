package me.nootnoot.edge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EdgeBuildFence {

    public static final String EXPECTED_FILE = "expected-plugin.sha256";

    public static final String REMAP_DIR = ".paper-remapped";

    public static final String REMAP_INDEX = "index.json";

    private EdgeBuildFence() {
    }

    public record Verdict(boolean armed, boolean fresh, List<String> lines) {
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public static String deployedHash(File pluginJar) throws IOException {
        if (pluginJar == null) {
            return "";
        }
        File parent = pluginJar.getParentFile();
        if (parent == null || !REMAP_DIR.equals(parent.getName())) {
            return sha256(pluginJar);
        }
        String recorded = originHash(new File(parent, REMAP_INDEX), pluginJar.getName());
        if (!recorded.isEmpty()) {
            return recorded;
        }
        File original = new File(parent.getParentFile(), pluginJar.getName());
        if (original.isFile()) {
            return sha256(original);
        }
        throw new IOException("this server loaded the remapped copy at " + pluginJar + ", and"
                + " neither " + REMAP_DIR + "/" + REMAP_INDEX + " nor " + original + " says which"
                + " jar it was remapped from, so there is nothing to compare the stamp against");
    }

    public static String originHash(File index, String jarName) {
        if (index == null || !index.isFile()) {
            return "";
        }
        try (Reader reader = Files.newBufferedReader(index.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return "";
            }
            JsonElement hashes = root.getAsJsonObject().get("hashes");
            if (hashes == null || !hashes.isJsonObject()) {
                return "";
            }
            for (Map.Entry<String, JsonElement> entry : hashes.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && jarName.equals(value.getAsString())) {
                    return entry.getKey().toLowerCase(Locale.ROOT);
                }
            }
            return "";
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    public static String readExpected(File marker) throws IOException {
        for (String line : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return "";
    }

    public static Verdict check(File pluginJar, File dataFolder) {
        File marker = new File(dataFolder, EXPECTED_FILE);
        if (!marker.isFile()) {
            return new Verdict(false, true, List.of("no " + EXPECTED_FILE + " next to this"
                    + " plugin, so nothing has told it which build it is supposed to be. That is"
                    + " normal for a real deployment; under build/devenv it means this server"
                    + " directory was provisioned before the fence existed - run gradlew devSetup"
                    + " to arm it."));
        }
        String expected;
        String deployed;
        try {
            expected = readExpected(marker);
            deployed = deployedHash(pluginJar);
        } catch (IOException ex) {
            return new Verdict(true, false, refusal("this jar could not be compared against "
                    + marker + " at all (" + ex + "). An unreadable check is a failed check, never"
                    + " a pass.", null, null));
        }
        if (expected.isEmpty()) {
            return new Verdict(true, false, refusal(marker + " names no build at all, so it"
                    + " proves nothing about the jar sitting next to it.", null, null));
        }
        if (expected.equalsIgnoreCase(deployed)) {
            return new Verdict(true, true, List.of("build fence: this plugin is the jar the last"
                    + " gradle build produced (sha256 " + deployed.substring(0, 16) + ")"));
        }
        return new Verdict(true, false, refusal("the plugin jar this server just loaded is NOT the"
                + " one the last gradle build produced." + (remapped(pluginJar)
                ? " This server remapped the plugin on load, so the compared hash is the source jar"
                + " " + REMAP_DIR + "/" + REMAP_INDEX + " names, not the remapped copy."
                : ""), deployed, expected));
    }

    private static boolean remapped(File pluginJar) {
        File parent = pluginJar == null ? null : pluginJar.getParentFile();
        return parent != null && REMAP_DIR.equals(parent.getName());
    }

    private static List<String> refusal(String headline, String deployed, String expected) {
        List<String> lines = new ArrayList<>();
        lines.add("################################################################");
        lines.add("## STALE EDGE PLUGIN - REFUSING TO ENABLE");
        lines.add("## " + headline);
        if (deployed != null && expected != null) {
            lines.add("##   loaded from disk: " + deployed);
            lines.add("##   built by gradle:  " + expected);
        }
        lines.add("## The protocol fence cannot catch this. It compares Protocol.VERSION, and");
        lines.add("## VERSION only moves when the simulated behaviour or the checksum function");
        lines.add("## moves. Every other edge change - the input frame the edge builds, the rules");
        lines.add("## it enforces, the world it paints - leaves VERSION alone, so a stale plugin");
        lines.add("## shakes hands, starts the match and then behaves like source nobody is");
        lines.add("## reading any more.");
        lines.add("## Fix it with:  gradlew devSetup   (or devRun, which provisions first)");
        lines.add("################################################################");
        return List.copyOf(lines);
    }
}

package me.nootnoot.limbo;

import java.util.ArrayList;
import java.util.List;

public final class LimboProbeMain {

    private LimboProbeMain() {
    }

    public static void main(String[] args) {
        String configPath = System.getProperty("probe.config", args.length > 0 ? args[0] : null);
        LimboConfig config = LimboConfig.load(configPath);
        int protocol = Integer.parseInt(System.getProperty("probe.protocol",
                String.valueOf(config.protocolVersion)));
        String label = System.getProperty("probe.name", String.valueOf(protocol));
        String host = System.getProperty("probe.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("probe.port", String.valueOf(config.bindPort)));

        Capture capture = null;
        for (Capture c : LimboServer.loadCaptures(config)) {
            if (c.protocolVersion == protocol) {
                capture = c;
                break;
            }
        }
        if (capture == null) {
            fail(label, protocol, "the limbo config declares no usable capture for it, so the limbo"
                    + " refuses that client at login");
            return;
        }

        List<String> mismatches = new ArrayList<>();
        Boolean captured = CaptureAudit.detectSectionFluidCount(capture.play, capture.chunkId,
                config.worldSectionCount);
        if (captured == null) {
            mismatches.add("could not tell from the capture whether chunk sections carry the fluid"
                    + " count - play.world.sections=" + config.worldSectionCount
                    + " may not match this capture's dimension height");
        } else if (captured != capture.sectionFluidCount) {
            mismatches.add("the real server's own chunk packets in this capture "
                    + (captured ? "DO" : "do NOT") + " carry the section fluid count, but the limbo"
                    + " builds its void chunks with sectionFluidCount=" + capture.sectionFluidCount);
        }
        crossCheck(mismatches, "play.id.keepalive", "probe.id.keepAlive", capture.keepAliveId);
        crossCheck(mismatches, "play.id.transfer", "probe.id.transfer", capture.transferId);
        crossCheck(mismatches, "play.id.custompayload", "probe.id.customPayload",
                capture.customPayloadId);
        crossCheck(mismatches, "the detected chunk id", "probe.id.chunk", capture.chunkId);
        if (!mismatches.isEmpty()) {
            fail(label, protocol, String.join("; ", mismatches));
            return;
        }

        LimboProbe probe = new LimboProbe(host, port, protocol, "Probe" + protocol,
                config.worldSectionCount, capture.sectionFluidCount, capture.chunkId,
                capture.keepAliveId, capture.transferId, capture.customPayloadId,
                Integer.getInteger("probe.id.disconnect", Protocol.PLAY_DISCONNECT_OUT),
                config.returnChannel,
                Long.parseLong(System.getProperty("probe.play.ms", "20000")));
        LimboProbe.Result result = probe.run();

        String summary = label + " (protocol " + protocol + ", " + capture.versionName + "): "
                + result.configFrames() + " config frames, " + result.playFrames() + " play frames, "
                + result.chunkPackets() + " chunks (" + result.voidChunksValidated() + " decoded"
                + " with sectionFluidCount=" + capture.sectionFluidCount + ")"
                + (result.transferred()
                ? ", returned to " + result.transferHost() + ":" + result.transferPort()
                : ", NO return transfer")
                + (System.getProperty("probe.id.transfer") == null
                ? ", play ids NOT cross-checked (no packet report - run devLimboCapture)"
                : ", play ids match the " + capture.versionName + " packet report");
        if (!result.ok() || !result.transferred() || result.chunkPackets() == 0
                || result.voidChunksValidated() != result.chunkPackets()) {
            System.out.println("FAIL " + summary
                    + (result.failure() == null ? "" : " - " + result.failure()));
            System.exit(1);
            return;
        }
        System.out.println("PASS " + summary);
    }

    private static void crossCheck(List<String> mismatches, String key, String property, int actual) {
        Integer expected = Integer.getInteger(property);
        if (expected != null && expected != actual) {
            mismatches.add(key + " is 0x" + Integer.toHexString(actual).toUpperCase(java.util.Locale.ROOT)
                    + " but this Minecraft version's own packet report says 0x"
                    + Integer.toHexString(expected).toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static void fail(String label, int protocol, String why) {
        System.out.println("FAIL " + label + " (protocol " + protocol + "): " + why);
        System.exit(1);
    }
}

package me.nootnoot.limbo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Capture {

    final int protocolVersion;
    final String versionName;
    final List<byte[]> config;
    final List<byte[]> play;
    final int chunkId;
    final int keepAliveId;
    final int transferId;
    final int customPayloadId;
    final boolean sectionFluidCount;
    final Set<Integer> dropIds;

    Capture(int protocolVersion, String versionName, List<byte[]> config, List<byte[]> play, int chunkId,
            int keepAliveId, int transferId, int customPayloadId, boolean sectionFluidCount) {
        this.protocolVersion = protocolVersion;
        this.versionName = versionName;
        this.config = config;
        this.play = play;
        this.chunkId = chunkId;
        this.keepAliveId = keepAliveId;
        this.transferId = transferId;
        this.customPayloadId = customPayloadId;
        this.sectionFluidCount = sectionFluidCount;
        Set<Integer> drops = new HashSet<>(List.of(0x01, 0x05, 0x06, 0x08));
        drops.add(protocolVersion >= 775 ? 0x54 : 0x52);
        this.dropIds = drops;
    }
}

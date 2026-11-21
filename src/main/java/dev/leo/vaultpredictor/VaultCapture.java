package dev.leo.vaultpredictor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class VaultCapture {
    private enum Phase { ARMED, EJECTING, FINISHED }

    private static final double SPAWN_TOLERANCE = 0.01;
    private final double x;
    private final double y;
    private final double z;
    private final Map<UUID, String> drops = new LinkedHashMap<>();
    private Phase phase = Phase.ARMED;
    private int ejections;
    private boolean interrupted;

    VaultCapture(int blockX, int blockY, int blockZ) {
        // Vanilla dispenses 1.2 blocks above the bottom center, then subtracts 0.125.
        x = blockX + 0.5;
        y = blockY + 1.075;
        z = blockZ + 0.5;
    }

    void updateVault(boolean ejecting) {
        if (phase == Phase.ARMED && ejecting) phase = Phase.EJECTING;
        else if (phase == Phase.EJECTING && !ejecting) phase = Phase.FINISHED;
    }

    boolean observe(UUID id, double spawnX, double spawnY, double spawnZ) {
        if (phase != Phase.EJECTING || interrupted || drops.containsKey(id)
                || Math.abs(spawnX - x) > SPAWN_TOLERANCE || Math.abs(spawnY - y) > SPAWN_TOLERANCE
                || Math.abs(spawnZ - z) > SPAWN_TOLERANCE) return false;
        drops.put(id, null);
        return true;
    }

    void ejected() {
        if (phase == Phase.EJECTING && !interrupted) ejections++;
    }

    void record(UUID id, String item) {
        drops.replace(id, null, Objects.requireNonNull(item));
    }

    boolean pending(UUID id) { return drops.containsKey(id) && drops.get(id) == null; }

    void interrupt() { interrupted = true; }

    boolean complete() {
        return phase == Phase.FINISHED && !interrupted && ejections > 0
                && drops.size() == ejections && !drops.containsValue(null);
    }

    List<String> loot() { return drops.values().stream().filter(Objects::nonNull).toList(); }
}

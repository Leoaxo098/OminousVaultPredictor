package dev.leo.vaultpredictor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

record VaultProfile(Long seed, List<String> observations) {
    VaultProfile { observations = List.copyOf(observations); }

    static Path file(Path directory, String worldKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".properties");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static VaultProfile read(Path file) throws IOException {
        if (Files.notExists(file)) return new VaultProfile(null, List.of());
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        try {
            String savedSeed = properties.getProperty("seed", "");
            Long seed = savedSeed.isEmpty() ? null : Long.parseLong(savedSeed);
            int count = Integer.parseInt(properties.getProperty("count"));
            if (count < 0 || count > properties.size()) throw new IllegalArgumentException("Invalid count");
            List<String> observations = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String loot = properties.getProperty("vault." + i);
                if (loot == null || VaultLoot.split(loot).isEmpty()) throw new IllegalArgumentException("Missing loot");
                observations.add(loot);
            }
            return new VaultProfile(seed, observations);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid saved vault history", ex);
        }
    }

    void write(Path file) throws IOException {
        Properties properties = new Properties();
        if (seed != null) properties.setProperty("seed", seed.toString());
        properties.setProperty("count", Integer.toString(observations.size()));
        for (int i = 0; i < observations.size(); i++) properties.setProperty("vault." + i, observations.get(i));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "vault-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Ominous Vault Predictor");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}


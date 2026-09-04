package com.gecopilot.client.auth;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class TokenStore {
    private final Path file;

    // Hub policy: plugin file access must stay within a plugin subdir of RUNELITE_DIR (~/.runelite).
    public TokenStore() { this(RuneLite.RUNELITE_DIR.toPath().resolve("coffer").resolve("session")); }
    public TokenStore(Path file) { this.file = file; }

    public void save(String token) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, token.getBytes(StandardCharsets.UTF_8));
            try {
                Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException | IOException ignore) { /* non-POSIX FS */ }
        } catch (IOException e) { log.warn("token save failed: {}", e.getMessage()); }
    }

    public Optional<String> load() {
        try {
            if (!Files.exists(file)) return Optional.empty();
            String t = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? Optional.empty() : Optional.of(t);
        } catch (IOException e) { return Optional.empty(); }
    }

    public void clear() {
        try { Files.deleteIfExists(file); } catch (IOException ignore) { }
    }
}

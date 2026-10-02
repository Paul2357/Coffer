package com.gecopilot.client.auth;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.Assert.*;

public class TokenStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void saveLoadClear() throws Exception {
        Path f = tmp.newFolder().toPath().resolve("session");
        TokenStore s = new TokenStore(f);
        assertFalse(s.load().isPresent());
        s.save("tok-123");
        assertEquals(Optional.of("tok-123"), s.load());
        s.clear();
        assertFalse(s.load().isPresent());
    }
}

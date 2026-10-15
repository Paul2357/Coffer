package com.gecopilot.client.api;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ApiClientTokenSwapTest {
    private HttpServer server;
    private volatile String renewHeader;
    private volatile int status = 200;
    private final List<String> seenAuth = Collections.synchronizedList(new ArrayList<>());
    private final List<String> persisted = Collections.synchronizedList(new ArrayList<>());
    private ApiClient api;

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seenAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            if (renewHeader != null) ex.getResponseHeaders().add("X-Coffer-Token", renewHeader);
            byte[] b = "{\"username\":\"u\",\"tier\":\"premium\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        api = new ApiClient(new OkHttpClient(), new com.google.gson.Gson(), "http://127.0.0.1:" + server.getAddress().getPort());
        api.onTokenRenewed(persisted::add);
        api.setToken("a.old.sig");
    }
    @After public void down() { server.stop(0); }

    @Test public void swapsAndPersistsRenewedToken() {
        renewHeader = "a.new.sig";
        assertNotNull(api.me());
        assertEquals("a.new.sig", api.currentTokenForTest());
        assertEquals(Collections.singletonList("a.new.sig"), persisted);
        renewHeader = null;
        api.me();
        assertEquals("Bearer a.new.sig", seenAuth.get(seenAuth.size() - 1)); // next call uses it
    }

    @Test public void noHeaderNoSwap() {
        api.me();
        assertEquals("a.old.sig", api.currentTokenForTest());
        assertTrue(persisted.isEmpty());
    }

    @Test public void ignoresHeaderOnErrorAndMalformed() {
        renewHeader = "a.new.sig"; status = 500;
        api.me();
        assertEquals("a.old.sig", api.currentTokenForTest());
        status = 200; renewHeader = "garbage";
        api.me();
        assertEquals("a.old.sig", api.currentTokenForTest());
        assertTrue(persisted.isEmpty());
    }

    @Test public void staleRequestCannotOverwriteNewerLogin() {
        api.setToken("b.relogin.sig");
        api.maybeSwapToken("Bearer a.old.sig", "a.new.sig"); // renewal answering a pre-login request
        assertEquals("b.relogin.sig", api.currentTokenForTest());
        assertTrue(persisted.isEmpty());
    }

    @Test public void signedOutClientNeverAdoptsHeader() {
        api.clearToken();
        api.maybeSwapToken("Bearer a.old.sig", "a.new.sig");
        assertNull(api.currentTokenForTest());
    }

    /** Iris LOW 1: a logout racing a renewal save must win; the session never comes back. */
    @Test public void logoutDuringSaveIsNotUndone() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> store = new java.util.concurrent.atomic.AtomicReference<>("a.old.sig");
        java.util.concurrent.CountDownLatch inSave = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        api.onTokenRenewed(t -> {
            inSave.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            store.set(t);
        });
        Thread swapper = new Thread(() -> api.maybeSwapToken("Bearer a.old.sig", "a.new.sig"));
        swapper.start();
        assertTrue(inSave.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread logout = new Thread(() -> api.signOut(() -> store.set(null)));
        logout.start();
        Thread.sleep(150);
        assertTrue("logout must wait for the in-flight save", logout.isAlive());
        release.countDown();
        swapper.join(5000); logout.join(5000);
        assertNull("persisted session cleared after the race", store.get());
        assertNull(api.currentTokenForTest());
    }

    @Test public void swapAfterSignOutSavesNothing() {
        java.util.concurrent.atomic.AtomicReference<String> store = new java.util.concurrent.atomic.AtomicReference<>();
        api.onTokenRenewed(store::set);
        api.signOut(() -> store.set(null));
        api.maybeSwapToken("Bearer a.old.sig", "a.new.sig");
        assertNull(store.get());
        assertNull(api.currentTokenForTest());
    }
}

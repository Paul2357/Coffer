package com.gecopilot.client.api;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ApiClientStatusTest {
    private HttpServer server;
    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private ApiClient api;

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        api = new ApiClient(new OkHttpClient(), new com.google.gson.Gson(), "http://127.0.0.1:" + server.getAddress().getPort());
        api.setToken("tok");
    }
    @After public void down() { server.stop(0); }

    @Test public void parsesTheStatusWithoutSendingTheToken() {
        body.set("{\"level\":\"outage\",\"message\":\"Down for maintenance\",\"link\":\"https://meetcoffer.com/s\","
            + "\"since\":\"2026-10-19T14:00:00Z\",\"db\":false}");
        ServiceStatusDto s = api.status();
        assertEquals("/api/status", path.get());
        assertNull("public endpoint: no Authorization header", auth.get());
        assertEquals("outage", s.level);
        assertEquals("Down for maintenance", s.message);
        assertEquals("https://meetcoffer.com/s", s.link);
        assertEquals("2026-10-19T14:00:00Z", s.since);
        assertEquals(Boolean.FALSE, s.db);
        assertTrue(api.hasToken());
    }

    @Test public void nullFieldsAndOldServers() {
        body.set("{\"level\":null,\"message\":null,\"link\":null,\"since\":null,\"db\":null}");
        ServiceStatusDto s = api.status();
        assertNotNull(s);
        assertNull(s.level);
        assertNull(s.db);
        code.set(404);
        body.set("Not found");
        assertNotNull("a server without the endpoint is reachable", api.status());
        code.set(200);
        body.set("<html>garbage");
        assertNotNull(api.status());
    }

    @Test public void serverErrorOrNoServerIsUnreachable() {
        code.set(503);
        assertNull(api.status());
        server.stop(0);
        assertNull(api.status());
    }

    /** A 0.1.29 server has no /api/status and its AuthGuard answers 401 for an unauthenticated call. */
    @Test public void oldServerIsReachableWithNothingToSayAndKeepsTheSession() {
        code.set(401);
        body.set("Unauthorized");
        ServiceStatusDto s = api.status();
        assertNotNull(s);
        assertNull(s.level);
        assertTrue("a 401 on the public status call must not sign the user out", api.hasToken());
        assertEquals(ApiClient.ConnState.OK, api.connState());
    }

    /** The status check never changes the conn state the regular calls set. */
    @Test public void statusDoesNotClearTheUnreachableNotice() {
        code.set(503);
        api.sync(1, 8, "steady", "all", 1, 1, 1.0, 1, new java.util.ArrayList<>(), false);
        assertEquals(ApiClient.ConnState.UNREACHABLE, api.connState());
        code.set(200);
        body.set("{\"level\":null,\"db\":null}");
        assertNotNull(api.status());
        assertEquals(ApiClient.ConnState.UNREACHABLE, api.connState());
        assertEquals("so the plugin can count the database as down",
            Boolean.FALSE, StatusMonitor.withOwnCalls(api.status(), true).db);
    }
}

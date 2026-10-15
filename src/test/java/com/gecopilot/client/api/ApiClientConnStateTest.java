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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** A 401/5xx/network failure must surface as a state change, never as silently-empty tabs. */
public class ApiClientConnStateTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private String body = "{\"tier\":\"premium\",\"flips\":[]}";
    private ApiClient api;
    private final List<ApiClient.ConnState> events = Collections.synchronizedList(new ArrayList<>());

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        api = new ApiClient(new OkHttpClient(), new com.google.gson.Gson(),
            "http://127.0.0.1:" + server.getAddress().getPort());
        api.onConnState(events::add);
        api.setToken("tok");
    }
    @After public void down() { server.stop(0); }

    private void sync() { api.sync(1, 8, "steady", "all", 1, 1, 1.0, 1, new ArrayList<>(), false); }

    @Test public void expired401SignsOutVisibly() {
        status.set(401);
        sync();
        assertFalse(api.hasToken());
        assertEquals(ApiClient.ConnState.SIGNED_OUT, api.connState());
        assertEquals(Collections.singletonList(ApiClient.ConnState.SIGNED_OUT), events);
    }

    @Test public void serverErrorIsUnreachableAndKeepsToken() {
        status.set(503);
        sync();
        assertTrue(api.hasToken());
        assertEquals(ApiClient.ConnState.UNREACHABLE, api.connState());
        status.set(200);
        sync();
        assertEquals(ApiClient.ConnState.OK, api.connState());
        assertEquals(java.util.Arrays.asList(ApiClient.ConnState.UNREACHABLE, ApiClient.ConnState.OK), events);
    }

    @Test public void networkFailureIsUnreachable() {
        server.stop(0);
        sync();
        assertTrue(api.hasToken());
        assertEquals(ApiClient.ConnState.UNREACHABLE, api.connState());
    }

    @Test public void unauthenticatedCallsNeverSignOut() {
        status.set(401);
        api.preview();
        assertTrue(api.hasToken());
        assertEquals(ApiClient.ConnState.OK, api.connState());
    }

    @Test public void loginAfterSignOutRestoresOk() {
        status.set(401);
        sync();
        status.set(200);
        body = "{\"token\":\"new\",\"tier\":\"premium\"}";
        assertTrue(api.login("u", "p").ok);
        assertEquals(ApiClient.ConnState.OK, api.connState());
        assertTrue(api.hasToken());
    }
}

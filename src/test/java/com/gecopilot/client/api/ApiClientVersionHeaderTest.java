package com.gecopilot.client.api;

import com.gecopilot.client.ServerConfig;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class ApiClientVersionHeaderTest {
    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private ApiClient api;

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(ex.getRequestURI().getPath() + "=" + ex.getRequestHeaders().getFirst(ApiClient.VERSION_HEADER));
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        api = new ApiClient(new OkHttpClient(), new com.google.gson.Gson(), "http://127.0.0.1:" + server.getAddress().getPort());
        api.setToken("tok");
    }
    @After public void down() { server.stop(0); }

    @Test public void everyCofferRequestCarriesThePluginVersion() {
        api.status();       // separate status client
        api.alertsStatus(); // main client, authed
        assertEquals(2, seen.size());
        for (String s : seen) assertTrue(s, s.endsWith("=" + ServerConfig.VERSION));
    }
}

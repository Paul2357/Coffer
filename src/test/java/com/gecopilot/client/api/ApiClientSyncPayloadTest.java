package com.gecopilot.client.api;

import com.gecopilot.client.trade.OfferCapture;
import com.google.gson.JsonObject;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** What /api/flips actually receives: the body ApiClient.sync puts on the wire, through the injected Gson. */
public class ApiClientSyncPayloadTest {
    @Test public void slotTimerMovedAtIsNotSentToTheServer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            okio.Buffer buf = new okio.Buffer();
            chain.request().body().writeTo(buf);
            if (chain.request().url().encodedPath().equals("/api/flips")) body.set(buf.readUtf8());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(ResponseBody.create(MediaType.parse("application/json"), "{\"flips\":[]}")).build();
        }).build();
        // RuneLiteAPI.GSON is the instance RuneLite's injector hands the plugin as Gson.
        ApiClient api = new ApiClient(fake, RuneLiteAPI.GSON, "http://coffer.test");
        api.setToken("t");

        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "SELLING", 561, 0, 1000, 210, 1_000);
        c.noteOpen(0, "SELLING", 561, 300, 1000, 210, 60_000); // a fill: movedAt = 60_000
        assertEquals(60_000, c.openAt(0).lastMovedAt());

        api.sync(10_000_000, 4, "steady", "all", 200, 10, 0.3, 75, c.snapshot(), true);

        assertNotNull("sync posted /api/flips", body.get());
        JsonObject sent = RuneLiteAPI.GSON.fromJson(body.get(), JsonObject.class);
        JsonObject offer = sent.getAsJsonArray("openOffers").get(0).getAsJsonObject();
        assertEquals(1_000, offer.get("openedAt").getAsLong());
        assertEquals(300, offer.get("sold").getAsInt());
        assertFalse("transient movedAt stays local: " + offer, offer.has("movedAt"));
        assertFalse(body.get().contains("movedAt"));
    }
}

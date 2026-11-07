package br.com.cobbleverse.generations.web;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP-boundary smoke tests. Minecraft state endpoints are tested in a real server separately. */
class WebPanelServerHttpTest {
    private static final String TOKEN = "f".repeat(64);
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer http;
    private int port;

    @BeforeEach
    void startPanel() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        port = http.getAddress().getPort();
        Constructor<WebPanelServer> constructor = WebPanelServer.class.getDeclaredConstructor(
                MinecraftServer.class, int.class, String.class, String.class, Path.class, HttpServer.class);
        constructor.setAccessible(true);
        WebPanelServer panel = constructor.newInstance(null, port, "", TOKEN, Path.of("unused-token"), http);
        Field current = WebPanelServer.class.getDeclaredField("current");
        current.setAccessible(true);
        current.set(null, panel);
        http.start();
    }

    @AfterEach
    void stopPanel() {
        WebPanelServer.stop();
    }

    @Test
    void servesBundledPageAndProtectsApiWithoutSession() throws Exception {
        HttpResponse<String> page = send("GET", "/", null, null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Cobbleverse Control"));
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'self'"));

        HttpResponse<String> state = send("GET", "/api/state", null, null, null);
        assertEquals(401, state.statusCode());
        assertTrue(state.body().contains("Entre com o token"));
    }

    @Test
    void authenticatesCreatesStrictCookieAndLogsOut() throws Exception {
        HttpResponse<String> login = send("POST", "/api/session", "{\"token\":\"" + TOKEN + "\"}", null, origin());
        assertEquals(200, login.statusCode());
        assertTrue(br.com.cobbleverse.generations.config.ConfigStore.GSON
                .fromJson(login.body(), com.google.gson.JsonObject.class).get("authenticated").getAsBoolean());
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> unknown = send("GET", "/api/unknown", null, cookie, null);
        assertEquals(404, unknown.statusCode());

        HttpResponse<String> logout = send("DELETE", "/api/session", null, cookie, origin());
        assertEquals(200, logout.statusCode());
        assertFalse(br.com.cobbleverse.generations.config.ConfigStore.GSON
                .fromJson(logout.body(), com.google.gson.JsonObject.class).get("authenticated").getAsBoolean());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(401, send("GET", "/api/unknown", null, cookie, null).statusCode());
    }

    @Test
    void rejectsCrossOriginMutationAndEncodedTraversal() throws Exception {
        HttpResponse<String> crossOrigin = send("POST", "/api/session", "{}", null, "http://evil.example");
        assertEquals(403, crossOrigin.statusCode());

        URI traversal = URI.create("http://127.0.0.1:" + port + "/%2e%2e/secrets");
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(traversal).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    @Test
    void rejectsMalformedAndOversizedJsonRequests() throws Exception {
        HttpResponse<String> malformed = send("POST", "/api/session", "{", null, origin());
        assertEquals(400, malformed.statusCode());

        String oversized = "{\"token\":\"" + "x".repeat(65_536) + "\"}";
        HttpResponse<String> tooLarge = send("POST", "/api/session", oversized, null, origin());
        assertEquals(413, tooLarge.statusCode());
    }

    @Test
    void limitsRepeatedInvalidTokens() throws Exception {
        String invalid = "{\"token\":\"wrong\"}";
        for (int i = 0; i < 5; i++)
            assertEquals(401, send("POST", "/api/session", invalid, null, origin()).statusCode());
        assertEquals(429, send("POST", "/api/session", invalid, null, origin()).statusCode());
    }

    private String origin() { return "http://127.0.0.1:" + port; }

    private HttpResponse<String> send(String method, String path, String body, String cookie, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin() + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.header("Content-Type", "application/json; charset=utf-8");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

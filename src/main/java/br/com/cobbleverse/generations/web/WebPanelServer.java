package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.data.PolicyResources;
import br.com.cobbleverse.generations.integration.PcInspection;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, loopback-only HTTP server. It serves bundled assets and a session-protected admin API. */
public final class WebPanelServer {
    private static final int MAX_BODY = 65_536;
    private static final long SESSION_LIFETIME_MS = TimeUnit.HOURS.toMillis(12);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile WebPanelServer current;

    private final MinecraftServer minecraft;
    private final int port;
    private final String httpsOrigin;
    private final String httpsAuthority;
    private final String adminToken;
    private final Path tokenFile;
    private final HttpServer http;
    private final ThreadPoolExecutor executor;
    private final WebPanelService service;
    private final PanelGameRequests gameRequests = new PanelGameRequests(2, Duration.ofSeconds(3));
    private final Semaphore pcReads = new Semaphore(1);
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAction = new ConcurrentHashMap<>();
    // All connections come from loopback, including connections from the proxy.
    private final LoginWindow loginAttempts = new LoginWindow();

    private static final class LoginWindow {
        long started = System.currentTimeMillis();
        int failures;
    }

    private WebPanelServer(MinecraftServer minecraft, int port, String httpsOrigin, String adminToken, Path tokenFile, HttpServer http) {
        this.minecraft = minecraft;
        this.port = port;
        this.httpsOrigin = httpsOrigin;
        this.httpsAuthority = httpsOrigin.isEmpty() ? "" : httpsOrigin.substring("https://".length());
        this.adminToken = adminToken;
        this.tokenFile = tokenFile;
        this.http = http;
        this.service = new WebPanelService(minecraft);
        this.executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "cobbleverse-web-panel");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        http.setExecutor(executor);
        http.createContext("/", this::handle);
    }

    public static synchronized void start(MinecraftServer server) {
        stop();
        Path directory = GenerationControl.store().directory();
        Path settings = directory.resolve("web.properties");
        Path token = directory.resolve("web-access.token");
        try {
            Properties properties = loadSettings(settings);
            if (!Boolean.parseBoolean(properties.getProperty("enabled", "true"))) {
                GenerationControl.LOGGER.info("Painel web desativado em {}.", settings);
                return;
            }
            int port = Integer.parseInt(properties.getProperty("port", "8765").trim());
            if (port < 1024 || port > 65535) throw new IOException("A porta do painel deve ficar entre 1024 e 65535.");
            String httpsOrigin = readHttpsOrigin(properties);
            String secret = loadOrCreateToken(token);
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
            WebPanelServer panel = new WebPanelServer(server, port, httpsOrigin, secret, token, http);
            http.start();
            current = panel;
            GenerationControl.LOGGER.info("Painel web local: http://127.0.0.1:{} — token salvo em {}", port, token);
            if (!httpsOrigin.isEmpty()) GenerationControl.LOGGER.info("Endereço HTTPS configurado para o painel: {}", httpsOrigin);
        } catch (Exception failure) {
            GenerationControl.LOGGER.error("O servidor do jogo iniciou, mas o painel web não pôde abrir. Consulte {}.", settings, failure);
        }
    }

    public static synchronized void stop() {
        WebPanelServer panel = current;
        current = null;
        if (panel == null) return;
        panel.sessions.clear();
        panel.lastAction.clear();
        panel.http.stop(0);
        panel.executor.shutdownNow();
    }

    public static String connectionInfo() {
        WebPanelServer panel = current;
        if (panel == null) return "O painel web não está ativo. Confira o log do servidor e o arquivo web.properties.";
        String remote = panel.httpsOrigin.isEmpty() ? "" : " | HTTPS: " + panel.httpsOrigin;
        return "Painel web: http://127.0.0.1:" + panel.port + remote + " | token em: " + panel.tokenFile;
    }

    private static Properties loadSettings(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        } else {
            Files.createDirectories(file.getParent());
            properties.setProperty("enabled", "true");
            properties.setProperty("port", "8765");
            properties.setProperty("httpsOrigin", "");
            try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                properties.store(output, "Painel administrativo Cobbleverse. O acesso fica limitado a 127.0.0.1.");
            }
        }
        return properties;
    }

    private static String readHttpsOrigin(Properties properties) throws IOException {
        String value = properties.getProperty("httpsOrigin", "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        String error = "httpsOrigin deve ser um endereço HTTPS, com hostname e porta opcional, sem caminho, usuário, query ou fragmento.";
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IOException(error); }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.equals("localhost") || host.equals("127.0.0.1") || port == 0 || port > 65535) {
            throw new IOException(error);
        }
        String origin = "https://" + host;
        String configured = port == -1 ? origin : origin + ":" + port;
        if (!value.equals(configured) && !value.equals(configured + "/")) throw new IOException(error);
        return port == 443 ? origin : configured;
    }

    private static String loadOrCreateToken(Path file) throws IOException {
        if (Files.exists(file)) {
            secureTokenFile(file);
            if (Files.size(file) > 256) throw new IOException("O token do painel excede o tamanho permitido.");
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!value.matches("(?i)[0-9a-f]{64}")) throw new IOException("Token ausente ou inválido; apague o arquivo para gerar outro: " + file);
            return value;
        }
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String value = HexFormat.of().formatHex(bytes);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        secureTokenFile(file);
        Files.writeString(file, value + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        return value;
    }

    /** Keep the admin secret readable only by the server account where the filesystem supports it. */
    private static void secureTokenFile(Path file) {
        try {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                return;
            } catch (UnsupportedOperationException ignored) {
                // Windows uses ACLs instead of POSIX mode bits.
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                var owner = Files.getOwner(file);
                AclEntry ownerOnly = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.of(
                                AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                                AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS,
                                AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
                                AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
                                AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE))
                        .build();
                acl.setAcl(List.of(ownerOnly));
                return;
            }
            GenerationControl.LOGGER.warn("O sistema de arquivos não oferece ACL/POSIX para restringir o token do painel: {}", file);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            GenerationControl.LOGGER.warn("Não foi possível restringir as permissões do token do painel em {}. Proteja o arquivo pelas permissões do sistema operacional.", file, failure);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            securityHeaders(exchange);
            String expectedOrigin = expectedOrigin(exchange);
            if (expectedOrigin == null) { json(exchange, 403, Map.of("message", "Endereço de painel inválido.")); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                String method = exchange.getRequestMethod();
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                boolean mutation = method.equals("POST") || method.equals("DELETE");
                if (origin == null ? mutation : !expectedOrigin.equals(origin)) {
                    json(exchange, 403, Map.of("message", "Origem recusada.")); return;
                }
                routeApi(exchange, path, expectedOrigin.equals(httpsOrigin));
            } else serveAsset(exchange, path);
        } catch (WebPanelService.Problem problem) {
            json(exchange, problem.status, Map.of("message", safeMessage(problem.getMessage())));
        } catch (TooLarge ignored) {
            json(exchange, 413, Map.of("message", "O pedido excede o limite de 64 KiB."));
        } catch (JsonParseException malformed) {
            json(exchange, 400, Map.of("message", "O JSON enviado é inválido."));
        } catch (IllegalArgumentException invalid) {
            json(exchange, 400, Map.of("message", safeMessage(invalid.getMessage())));
        } catch (Exception failure) {
            GenerationControl.LOGGER.warn("Falha ao atender uma solicitação do painel web: {}", failure.toString());
            try { json(exchange, 500, Map.of("message", "Não foi possível concluir a solicitação.")); }
            catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private void routeApi(HttpExchange exchange, String path, boolean secure) throws Exception {
        String method = exchange.getRequestMethod();
        if (path.equals("/api/session") && method.equals("POST")) { login(exchange, secure); return; }
        if (path.equals("/api/session") && method.equals("DELETE")) { logout(exchange, secure); return; }
        String sessionId = authenticatedSession(exchange);
        if (sessionId == null) { json(exchange, 401, Map.of("message", "Entre com o token do painel.")); return; }
        if (path.equals("/api/state") && method.equals("GET")) {
            json(exchange, 200, onServer(() -> service.snapshot(sessionId)));
            return;
        }
        if (path.equals("/api/job") && method.equals("GET")) {
            json(exchange, 200, onServer(() -> service.job(sessionId)));
            return;
        }
        if (path.equals("/api/species") && method.equals("GET")) {
            String query = query(exchange.getRequestURI().getRawQuery()).getOrDefault("q", "").trim();
            if (query.length() < 2 || query.length() > 64) {
                json(exchange, 200, Map.of("ready", true, "items", List.of())); return;
            }
            var results = PolicyResources.searchSpecies(query, 30);
            json(exchange, 200, Map.of("ready", results != null, "items", results == null ? List.of() : results));
            return;
        }
        if (path.equals("/api/pc/players") || path.equals("/api/pc/box")) {
            if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
            inspectPc(exchange, path);
            return;
        }
        if (path.equals("/api/action") && method.equals("POST")) {
            throttle(sessionId);
            var payload = ConfigStore.GSON.fromJson(body(exchange), WebPanelService.ActionRequest.class);
            json(exchange, 200, onServer(() -> service.action(sessionId, payload)));
            return;
        }
        if (path.equals("/api/session")) { methodNotAllowed(exchange, "GET, POST, DELETE"); return; }
        if (path.equals("/api/state") || path.equals("/api/job") || path.equals("/api/species") || path.equals("/api/action")) {
            methodNotAllowed(exchange, path.equals("/api/action") ? "POST" : "GET"); return;
        }
        json(exchange, 404, Map.of("message", "Rota não encontrada."));
    }

    private void inspectPc(HttpExchange exchange, String path) throws Exception {
        if (!pcReads.tryAcquire()) throw new WebPanelService.Problem(503, "Já há uma consulta ao PC em andamento. Tente novamente.");
        try {
            var parameters = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/api/pc/players")) {
                String search = parameters.getOrDefault("q", "");
                var directory = onServer(() -> PcInspection.directory(minecraft));
                json(exchange, 200, Map.of("players", PcInspection.players(directory, search)));
                return;
            }
            UUID id;
            int index;
            try {
                String rawId = parameters.getOrDefault("player", "");
                id = UUID.fromString(rawId);
                if (!id.toString().equalsIgnoreCase(rawId)) throw new IllegalArgumentException();
                index = Integer.parseInt(parameters.getOrDefault("box", "0"));
                if (index < 0 || index >= 4096) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new WebPanelService.Problem(400, "Jogador ou caixa inválida.");
            }
            PcInspection.Lookup lookup;
            try { lookup = onServer(() -> PcInspection.lookup(id, index)); }
            catch (IllegalStateException unsupported) {
                throw new WebPanelService.Problem(422, "O armazenamento de Pokémon deste servidor não é compatível com a consulta.");
            }
            PcInspection.Box box = lookup.live();
            if (box == null) {
                try { box = PcInspection.savedBox(lookup.files(), index); }
                catch (Exception failure) {
                    GenerationControl.LOGGER.warn("Não foi possível ler o PC de {}: {}", id, failure.toString());
                    throw new WebPanelService.Problem(422, "O PC salvo não pôde ser lido. Aguarde o salvamento do jogo e tente novamente.");
                }
            }
            json(exchange, 200, box);
        } finally {
            pcReads.release();
        }
    }

    private void login(HttpExchange exchange, boolean secure) throws Exception {
        JsonObject payload = ConfigStore.GSON.fromJson(body(exchange), JsonObject.class);
        String received = payload != null && payload.has("token") && payload.get("token").isJsonPrimitive()
                ? payload.get("token").getAsString() : "";
        int status = verifyToken(received);
        if (status == 429) { json(exchange, 429, Map.of("message", "Muitas tentativas. Aguarde um minuto.")); return; }
        if (status == 401) {
            json(exchange, 401, Map.of("message", "Token inválido."));
            return;
        }
        if (sessions.size() >= 64) {
            long now = System.currentTimeMillis();
            for (var entry : sessions.entrySet()) {
                String expiredId = entry.getKey();
                if (entry.getValue() <= now && sessions.remove(expiredId, entry.getValue())) {
                    lastAction.remove(expiredId);
                    service.closeSession(expiredId);
                }
            }
        }
        if (sessions.size() >= 64) { json(exchange, 429, Map.of("message", "Limite de sessões administrativas atingido.")); return; }
        byte[] raw = new byte[32]; SECURE_RANDOM.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        sessions.put(id, System.currentTimeMillis() + SESSION_LIFETIME_MS);
        setSessionCookie(exchange, id, 43200, secure);
        json(exchange, 200, Map.of("authenticated", true));
    }

    private int verifyToken(String received) {
        synchronized (loginAttempts) {
            long now = System.currentTimeMillis();
            if (now - loginAttempts.started > 60_000) {
                loginAttempts.started = now;
                loginAttempts.failures = 0;
            }
            if (loginAttempts.failures >= 5) return 429;
            if (!MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
                loginAttempts.failures++;
                return 401;
            }
            loginAttempts.started = now;
            loginAttempts.failures = 0;
            return 200;
        }
    }

    private void logout(HttpExchange exchange, boolean secure) throws IOException {
        String id = cookie(exchange, "GC_SESSION");
        if (id != null) {
            sessions.remove(id);
            lastAction.remove(id);
            service.closeSession(id);
        }
        setSessionCookie(exchange, "", 0, secure);
        json(exchange, 200, Map.of("authenticated", false));
    }

    private void setSessionCookie(HttpExchange exchange, String id, int maxAge, boolean secure) {
        exchange.getResponseHeaders().add("Set-Cookie", "GC_SESSION=" + id
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (secure ? "; Secure" : ""));
    }

    private String authenticatedSession(HttpExchange exchange) {
        String id = cookie(exchange, "GC_SESSION");
        if (id == null) return null;
        Long expiry = sessions.get(id);
        if (expiry == null) return null;
        long now = System.currentTimeMillis();
        if (expiry <= now) {
            sessions.remove(id);
            lastAction.remove(id);
            service.closeSession(id);
            return null;
        }
        sessions.put(id, now + SESSION_LIFETIME_MS);
        return id;
    }

    private void throttle(String sessionId) {
        long now = System.nanoTime();
        Long last = lastAction.get(sessionId);
        if (last != null && now - last < TimeUnit.MILLISECONDS.toNanos(300))
            throw new WebPanelService.Problem(429, "Aguarde um instante antes de enviar outra alteração.");
        lastAction.put(sessionId, now);
    }

    private <T> T onServer(java.util.concurrent.Callable<T> task) throws Exception {
        return gameRequests.call(minecraft::execute, task);
    }

    private void serveAsset(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("%")) {
            json(exchange, 400, Map.of("message", "Caminho inválido.")); return;
        }
        String resource = "web/" + (path.equals("/") ? "index.html" : path.substring(1));
        try (InputStream input = WebPanelServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) { json(exchange, 404, Map.of("message", "Arquivo não encontrado.")); return; }
            byte[] bytes = input.readAllBytes();
            String type = contentType(path);
            exchange.getResponseHeaders().set("Content-Type", type);
            if (path.equals("/")) exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            else exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    }

    private String expectedOrigin(HttpExchange exchange) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        if (authority == null || authority.isBlank() || !authority.equals(authority.trim())) return null;
        if (authority.equalsIgnoreCase(httpsAuthority)) return httpsOrigin;

        authority = authority.toLowerCase(Locale.ROOT);
        String host = authority;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            if (authority.indexOf(':') != colon) return null;
            String forwardedPort = authority.substring(colon + 1);
            try {
                int value = Integer.parseInt(forwardedPort);
                if (value < 1 || value > 65535 || !forwardedPort.equals(Integer.toString(value))) return null;
            } catch (NumberFormatException invalidPort) {
                return null;
            }
            host = authority.substring(0, colon);
        }

        // SSH local forwarding may expose this loopback service on a different browser-side port.
        return host.equals("127.0.0.1") || host.equals("localhost") ? "http://" + authority : null;
    }

    private String cookie(HttpExchange exchange, String name) {
        String raw = exchange.getRequestHeaders().getFirst("Cookie");
        if (raw == null) return null;
        for (String item : raw.split(";")) {
            String part = item.trim();
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }

    private Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (key.length() <= 32 && value.length() <= 128) result.put(key, value);
        }
        return result;
    }

    private String body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new WebPanelService.Problem(415, "Envie o conteúdo no formato JSON.");
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { if (Long.parseLong(length) > MAX_BODY) throw new TooLarge(); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Tamanho de pedido inválido."); }
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new TooLarge();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        json(exchange, 405, Map.of("message", "Método HTTP não permitido."));
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = ConfigStore.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String contentType(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".html") || value.equals("/")) return "text/html; charset=utf-8";
        if (value.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (value.endsWith(".css")) return "text/css; charset=utf-8";
        if (value.endsWith(".svg")) return "image/svg+xml";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".woff2")) return "font/woff2";
        if (value.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Pedido inválido.";
        return message.length() > 512 ? message.substring(0, 512) + "…" : message;
    }

    private static final class TooLarge extends IOException {}
}

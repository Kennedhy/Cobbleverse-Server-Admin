package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.data.PolicyResources;
import br.com.cobbleverse.generations.integration.PcInspection;
import br.com.cobbleverse.generations.integration.PcManagement;
import br.com.cobbleverse.generations.integration.TrainerDifficultyManager;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small, loopback-only HTTP server. It serves bundled assets and a session-protected admin API. */
public final class WebPanelServer {
    private static final String DEFAULT_PORT = "0001";
    private static final int MAX_BODY = 65_536;
    private static final long SESSION_LIFETIME_MS = TimeUnit.HOURS.toMillis(12);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile WebPanelServer current;

    private final MinecraftServer minecraft;
    private final int port;
    private final String httpsOrigin;
    private final String httpsAuthority;
    private final Path tokenFile;
    private final Path viewerTokenFile;
    private final Path credentialDirectory;
    private volatile List<Credential> sharedCredentials;
    private volatile List<Credential> credentials;
    private final HttpServer http;
    private final ThreadPoolExecutor executor;
    private final Executor persistenceExecutor;
    private final WebPanelService service;
    private final PanelGameRequests gameRequests = new PanelGameRequests(2, Duration.ofSeconds(3));
    private final Semaphore pcOperations = new Semaphore(1);
    private final AtomicBoolean restarting = new AtomicBoolean();
    private Path restartSignal;
    private record Credential(String name, String role, String token, boolean shared) {}
    private record Session(String role, String actor, long expiresAt) {}
    public record CreatedCredential(String name, String role, String token) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAction = new ConcurrentHashMap<>();
    // All connections come from loopback, including connections from the proxy.
    private final LoginWindow loginAttempts = new LoginWindow();

    private static final class LoginWindow {
        long started = System.currentTimeMillis();
        int failures;
    }

    private WebPanelServer(MinecraftServer minecraft, int port, String httpsOrigin, String adminToken, Path tokenFile, HttpServer http) {
        this(minecraft, port, httpsOrigin, adminToken, adminToken, tokenFile,
                tokenFile.resolveSibling("web-view-access.token"), http);
    }

    private WebPanelServer(MinecraftServer minecraft, int port, String httpsOrigin, String adminToken,
                           String viewerToken, Path tokenFile, Path viewerTokenFile, HttpServer http) {
        this(minecraft, port, httpsOrigin, adminToken, viewerToken, tokenFile, viewerTokenFile,
                tokenFile.resolveSibling("web-users"), List.of(), http);
    }

    private WebPanelServer(MinecraftServer minecraft, int port, String httpsOrigin, String adminToken,
                           String viewerToken, Path tokenFile, Path viewerTokenFile, Path credentialDirectory,
                           List<Credential> namedCredentials, HttpServer http) {
        this.minecraft = minecraft;
        this.port = port;
        this.httpsOrigin = httpsOrigin;
        this.httpsAuthority = httpsOrigin.isEmpty() ? "" : httpsOrigin.substring("https://".length());
        this.tokenFile = tokenFile;
        this.viewerTokenFile = viewerTokenFile;
        this.credentialDirectory = credentialDirectory.toAbsolutePath().normalize();
        ArrayList<Credential> shared = new ArrayList<>(2);
        if (adminToken != null) shared.add(new Credential("admin-compartilhado", "admin", adminToken, true));
        if (viewerToken != null) shared.add(new Credential("consulta-compartilhada", "viewer", viewerToken, true));
        this.sharedCredentials = List.copyOf(shared);
        this.credentials = credentialsWith(namedCredentials);
        this.http = http;
        this.service = new WebPanelService(minecraft);
        this.executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "cobbleverse-web-panel");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.persistenceExecutor = br.com.cobbleverse.generations.config.ConfigPersistenceExecutor.executor();
        http.setExecutor(executor);
        http.createContext("/", this::handle);
    }

    public static void registerPlayerSessions() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            WebPanelServer panel = current;
            if (panel != null && panel.minecraft == server) panel.service.playerJoined(handler.player.getUuid());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WebPanelServer panel = current;
            if (panel != null && panel.minecraft == server) panel.service.playerDisconnected(handler.player.getUuid());
        });
    }

    public static synchronized void start(MinecraftServer server) {
        stop();
        Path directory = GenerationControl.store().directory();
        Path settings = directory.resolve("web.properties");
        Path token = directory.resolve("web-access.token");
        Path viewerToken = directory.resolve("web-view-access.token");
        try {
            Properties properties = loadSettings(settings);
            if (!Boolean.parseBoolean(properties.getProperty("enabled", "true"))) {
                GenerationControl.LOGGER.info("Painel web desativado em {}.", settings);
                return;
            }
            int port = Integer.parseInt(properties.getProperty("port", DEFAULT_PORT).trim());
            if (port < 1 || port > 65535) throw new IOException("A porta do painel deve ficar entre 1 e 65535.");
            String httpsOrigin = readHttpsOrigin(properties);
            String secret = Files.exists(directory.resolve("web-admin-access.disabled")) ? null : loadOrCreateToken(token);
            String viewSecret = Files.exists(directory.resolve("web-view-access.disabled")) ? null : loadOrCreateToken(viewerToken);
            Path users = directory.resolve("web-users");
            List<Credential> namedCredentials = loadNamedCredentials(users);
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
            WebPanelServer panel = new WebPanelServer(server, port, httpsOrigin, secret, viewSecret,
                    token, viewerToken, users, namedCredentials, http);
            String signal = System.getenv("COBBLEVERSE_RESTART_SIGNAL");
            if (signal != null && !signal.isBlank()) panel.restartSignal = Path.of(signal);
            http.start();
            current = panel;
            GenerationControl.LOGGER.info("Painel web local: http://127.0.0.1:{}", port);
            if (secret != null) GenerationControl.LOGGER.info("Token admin compartilhado salvo em {}", token);
            if (viewSecret != null) GenerationControl.LOGGER.info("Token de consulta compartilhado salvo em {}", viewerToken);
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
        String admin = panel.sharedCredentials.stream().anyMatch(credential -> credential.role().equals("admin"))
                ? "token admin compartilhado em: " + panel.tokenFile : "admin compartilhado desativado";
        String viewer = panel.sharedCredentials.stream().anyMatch(credential -> credential.role().equals("viewer"))
                ? "token de consulta compartilhado em: " + panel.viewerTokenFile : "consulta compartilhada desativada";
        return "Painel web: http://127.0.0.1:" + panel.port + remote + " | " + admin + " | " + viewer;
    }

    /** Create a named credential and return its secret once to the authorized caller. */
    public static synchronized CreatedCredential createCredential(String rawName, String rawRole) throws IOException {
        String name = normalizeCredentialName(rawName);
        String role = normalizeCredentialRole(rawRole);
        if (name.equals("admin-compartilhado") || name.equals("consulta-compartilhada"))
            throw new IllegalArgumentException("Esse nome é reservado para a credencial compartilhada.");
        Path directory = GenerationControl.store().directory().resolve("web-users").toAbsolutePath().normalize();
        Path adminFile = credentialFile(directory, name, "admin");
        Path viewerFile = credentialFile(directory, name, "viewer");
        if (Files.exists(directory.resolve(name + ".revoked")))
            throw new IllegalStateException("Esse identificador já foi revogado e não pode ser reutilizado.");
        if (Files.exists(adminFile) || Files.exists(viewerFile))
            throw new IllegalStateException("Já existe uma credencial com esse nome.");
        if (loadNamedCredentials(directory).size() >= 64)
            throw new IllegalStateException("O limite de 64 credenciais individuais foi atingido.");

        Files.createDirectories(directory);
        secureTokenDirectory(directory);
        Path file = role.equals("admin") ? adminFile : viewerFile;
        String token = generateToken();
        try {
            Files.createFile(file);
            secureTokenFile(file);
            Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(file);
            throw failure;
        }

        WebPanelServer panel = current;
        if (panel != null && panel.credentialDirectory.equals(directory))
            panel.addNamedCredential(new Credential(name, role, token, false));
        return new CreatedCredential(name, role, token);
    }

    /** Revoke a named credential and immediately expire every session created with it. */
    public static synchronized boolean revokeCredential(String rawName) throws IOException {
        String name = normalizeCredentialName(rawName);
        Path directory = GenerationControl.store().directory().resolve("web-users").toAbsolutePath().normalize();
        if (name.equals("admin-compartilhado") || name.equals("consulta-compartilhada")) {
            Path root = GenerationControl.store().directory();
            boolean admin = name.equals("admin-compartilhado");
            Path token = root.resolve(admin ? "web-access.token" : "web-view-access.token");
            Path disabled = root.resolve(admin ? "web-admin-access.disabled" : "web-view-access.disabled");
            if (Files.exists(disabled)) return false;
            if (admin) {
                boolean individualAdmin = loadNamedCredentials(directory).stream().anyMatch(credential -> credential.role().equals("admin"));
                if (!individualAdmin) throw new IllegalStateException("Crie uma credencial admin individual antes de revogar o último acesso administrativo.");
            }
            Files.writeString(disabled, "disabled" + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.deleteIfExists(token);
            WebPanelServer panel = current;
            if (panel != null && panel.credentialDirectory.equals(directory)) panel.disableSharedCredential(name);
            return true;
        }
        Path adminFile = credentialFile(directory, name, "admin");
        Path viewerFile = credentialFile(directory, name, "viewer");
        boolean removed = Files.exists(adminFile) || Files.exists(viewerFile);
        if (!removed) return false;
        if (Files.exists(adminFile)) {
            Path root = GenerationControl.store().directory();
            long otherAdmins = loadNamedCredentials(directory).stream()
                    .filter(credential -> credential.role().equals("admin") && !credential.name().equals(name)).count();
            boolean sharedAdminAvailable = !Files.exists(root.resolve("web-admin-access.disabled"))
                    && Files.isRegularFile(root.resolve("web-access.token"));
            if (otherAdmins == 0 && !sharedAdminAvailable)
                throw new IllegalStateException("Crie outro administrador antes de revogar o último acesso administrativo.");
        }
        Files.createDirectories(directory);
        secureTokenDirectory(directory);
        Files.writeString(directory.resolve(name + ".revoked"), "revoked" + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.deleteIfExists(adminFile);
        Files.deleteIfExists(viewerFile);

        WebPanelServer panel = current;
        if (panel != null && panel.credentialDirectory.equals(directory)) panel.revokeNamedCredential(name);
        return true;
    }

    private synchronized void addNamedCredential(Credential credential) {
        ArrayList<Credential> updated = new ArrayList<>(credentials);
        updated.add(credential);
        credentials = List.copyOf(updated);
    }

    private synchronized void revokeNamedCredential(String name) {
        credentials = credentials.stream()
                .filter(credential -> credential.shared() || !credential.name().equals(name)).toList();
        invalidateActor(name);
    }

    private synchronized void disableSharedCredential(String name) {
        sharedCredentials = sharedCredentials.stream().filter(credential -> !credential.name().equals(name)).toList();
        credentials = credentials.stream().filter(credential -> !credential.name().equals(name)).toList();
        invalidateActor(name);
    }

    private List<Credential> credentialsWith(List<Credential> named) {
        ArrayList<Credential> all = new ArrayList<>(sharedCredentials.size() + named.size());
        all.addAll(sharedCredentials);
        all.addAll(named);
        return List.copyOf(all);
    }

    private void invalidateActor(String name) {
        for (var entry : sessions.entrySet()) {
            if (entry.getValue().actor().equals(name) && sessions.remove(entry.getKey(), entry.getValue())) {
                lastAction.remove(entry.getKey());
                service.closeSession(entry.getKey());
            }
        }
    }

    private static List<Credential> loadNamedCredentials(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        ArrayList<Credential> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                String filename = file.getFileName().toString();
                String role;
                String suffix;
                if (filename.endsWith(".admin.token")) { role = "admin"; suffix = ".admin.token"; }
                else if (filename.endsWith(".viewer.token")) { role = "viewer"; suffix = ".viewer.token"; }
                else continue;
                String name = filename.substring(0, filename.length() - suffix.length());
                if (!name.matches("[a-z0-9_-]{1,24}")) continue;
                if (result.size() >= 64) throw new IOException("O diretório contém mais de 64 credenciais individuais.");
                secureTokenFile(file);
                if (Files.size(file) > 256) throw new IOException("Uma credencial individual excede o tamanho permitido: " + filename);
                String token = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!token.matches("(?i)[0-9a-f]{64}"))
                    throw new IOException("Credencial individual ausente ou inválida: " + filename);
                result.add(new Credential(name, role, token, false));
            }
        }
        result.sort(Comparator.comparing(Credential::name).thenComparing(Credential::role));
        return List.copyOf(result);
    }

    private static Path credentialFile(Path directory, String name, String role) {
        return directory.resolve(name + "." + role + ".token").normalize();
    }

    private static String normalizeCredentialName(String rawName) {
        String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,23}"))
            throw new IllegalArgumentException("Use um nome de 1 a 24 caracteres: letras sem acento, números, hífen ou sublinhado.");
        return name;
    }

    private static String normalizeCredentialRole(String rawRole) {
        String role = rawRole == null ? "" : rawRole.trim().toLowerCase(Locale.ROOT);
        if (!role.equals("admin") && !role.equals("viewer"))
            throw new IllegalArgumentException("O papel deve ser admin ou viewer.");
        return role;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Properties loadSettings(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        } else {
            Files.createDirectories(file.getParent());
            properties.setProperty("enabled", "true");
            properties.setProperty("port", DEFAULT_PORT);
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
        String value = generateToken();
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

    private static void secureTokenDirectory(Path directory) {
        try {
            try {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                return;
            } catch (UnsupportedOperationException ignored) {
                // Windows uses ACLs instead of POSIX mode bits.
            }
            AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl != null) {
                var owner = Files.getOwner(directory);
                AclEntry ownerOnly = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.of(
                                AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                                AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_NAMED_ATTRS,
                                AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ATTRIBUTES,
                                AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_ACL,
                                AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
                                AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
                                AclEntryPermission.LIST_DIRECTORY, AclEntryPermission.ADD_FILE,
                                AclEntryPermission.ADD_SUBDIRECTORY, AclEntryPermission.SYNCHRONIZE))
                        .build();
                acl.setAcl(List.of(ownerOnly));
                return;
            }
            GenerationControl.LOGGER.warn("O sistema de arquivos não oferece ACL/POSIX para restringir o diretório de credenciais: {}", directory);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            GenerationControl.LOGGER.warn("Não foi possível restringir as permissões do diretório de credenciais em {}.", directory, failure);
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
                boolean mutation = Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
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
        if (path.equals("/api/session") && method.equals("GET")) {
            json(exchange, 200, Map.of("authenticated", true, "role", role(sessionId), "actor", actor(sessionId)));
            return;
        }
        if (path.equals("/api/state") && method.equals("GET")) {
            json(exchange, 200, onServer(() -> service.snapshot(sessionId, role(sessionId), actor(sessionId))));
            return;
        }
        if (path.equals("/api/dashboard") && method.equals("GET")) {
            var dashboard = onServer(service::dashboard);
            dashboard.put("restartAvailable", canEdit(sessionId) && restartSignal != null);
            dashboard.put("restarting", restarting.get());
            json(exchange, 200, dashboard);
            return;
        }
        if (path.equals("/api/trainer-operations")) {
            if (method.equals("GET")) {
                json(exchange, 200, onServer(TrainerOperationsSettings::read));
                return;
            }
            if (!method.equals("PUT")) { methodNotAllowed(exchange, "GET, PUT"); return; }
            if (!canEdit(sessionId)) { json(exchange, 403, Map.of("message", "Esta credencial permite apenas consulta.")); return; }
            throttle(sessionId);
            JsonObject payload = ConfigStore.GSON.fromJson(body(exchange), JsonObject.class);
            json(exchange, 200, onServer(() -> TrainerOperationsSettings.update(payload, true)));
            return;
        }
        if (path.equals("/api/trainer-difficulty")) {
            if (method.equals("GET")) {
                if (!TrainerDifficultyManager.catalogReady()) {
                    onServer(() -> { TrainerDifficultyManager.refreshTrainerCatalog(); return null; });
                }
                json(exchange, 200, TrainerDifficultyManager.view());
                return;
            }
            if (!method.equals("PUT")) { methodNotAllowed(exchange, "GET, PUT"); return; }
            if (!canEdit(sessionId)) { json(exchange, 403, Map.of("message", "Esta credencial permite apenas consulta.")); return; }
            throttle(sessionId);
            JsonObject payload = ConfigStore.GSON.fromJson(body(exchange), JsonObject.class);
            try {
                json(exchange, 200, TrainerDifficultyManager.save(payload));
            } catch (IOException failure) {
                throw new WebPanelService.Problem(503, "Não foi possível gravar a configuração de dificuldade dos treinadores.");
            }
            return;
        }
        if (path.equals("/api/player/progress")) {
            if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
            String player = query(exchange.getRequestURI().getRawQuery()).get("player");
            var snapshot = onServer(() -> PlayerProgressAdmin.snapshot(minecraft, player));
            json(exchange, 200, PlayerProgressAdmin.view(snapshot, PlayerProgressAdmin.readLedger(player)));
            return;
        }
        if (path.equals("/api/player/progress/edit")) {
            if (!method.equals("POST")) { methodNotAllowed(exchange, "POST"); return; }
            if (!canEdit(sessionId)) { json(exchange, 403, Map.of("message", "Esta credencial permite apenas consulta.")); return; }
            throttle(sessionId);
            PlayerProgressAdmin.EditRequest payload = ConfigStore.GSON.fromJson(body(exchange), PlayerProgressAdmin.EditRequest.class);
            PlayerProgressAdmin.EditPlan plan = onServer(() -> PlayerProgressAdmin.prepareEdit(minecraft, payload, auditActor(sessionId)));
            PlayerProgressAdmin.PersistedEditPlan persisted;
            try {
                persisted = PlayerProgressAdmin.persist(plan);
                PlayerProgressAdmin.PersistedEditPlan commit = persisted;
                PlayerProgressAdmin.Snapshot updated;
                try {
                    updated = onServer(() -> PlayerProgressAdmin.applyEdit(minecraft, commit));
                } catch (Exception failure) {
                    try { PlayerProgressAdmin.rollbackLedger(commit); }
                    catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                    throw failure;
                }
                json(exchange, 200, PlayerProgressAdmin.view(updated, commit.ledgerAfter()));
            } finally {
                PlayerProgressAdmin.close(plan);
            }
            return;
        }
        if (path.equals("/api/server/restart") && method.equals("POST")) {
            if (!canEdit(sessionId)) throw new WebPanelService.Problem(403, "Esta credencial permite apenas consulta.");
            throttle(sessionId);
            restart(exchange, auditActor(sessionId));
            return;
        }
        if (path.equals("/api/job") && method.equals("GET")) {
            json(exchange, 200, onServer(() -> service.job(sessionId)));
            return;
        }
        if (path.equals("/api/simulate") && method.equals("POST")) {
            throttle(sessionId);
            var payload = ConfigStore.GSON.fromJson(body(exchange), WebPanelService.SimulationRequest.class);
            json(exchange, 200, onServer(() -> service.simulate(payload)));
            return;
        }
        if (path.equals("/api/history") && method.equals("GET")) {
            String revision = query(exchange.getRequestURI().getRawQuery()).get("revision");
            if (revision == null || revision.isBlank()) {
                json(exchange, 200, Map.of("entries", GenerationControl.store().recentChanges()));
            } else {
                long selected;
                try { selected = Long.parseLong(revision); }
                catch (NumberFormatException invalid) { throw new WebPanelService.Problem(400, "Revisão inválida."); }
                if (selected < 1) throw new WebPanelService.Problem(400, "Revisão inválida.");
                try { json(exchange, 200, GenerationControl.store().historyDetail(selected)); }
                catch (IllegalStateException missing) { throw new WebPanelService.Problem(404, "Alteração não encontrada no histórico."); }
            }
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
        if (path.equals("/api/pc/edit")) {
            if (!method.equals("POST")) { methodNotAllowed(exchange, "POST"); return; }
            if (!canEdit(sessionId)) { json(exchange, 403, Map.of("message", "Esta credencial permite apenas consulta.")); return; }
            if (!pcOperations.tryAcquire()) throw new WebPanelService.Problem(503, "Já há uma operação de PC em andamento. Tente novamente.");
            try {
                throttle(sessionId);
                PcManagement.Request payload = ConfigStore.GSON.fromJson(body(exchange), PcManagement.Request.class);
                PcManagement.Result result;
                try {
                    PcManagement.Prepared prepared = onServer(() -> PcManagement.prepare(minecraft, payload, actor(sessionId)));
                    if (!prepared.requiresQueueBarrier()) {
                        result = prepared.immediateResult();
                    } else {
                        PcManagement.awaitPendingSaves(prepared);
                        result = onServer(() -> PcManagement.applyDeferred(minecraft, prepared));
                    }
                } catch (PcManagement.Busy busy) {
                    throw new WebPanelService.Problem(503, safeMessage(busy.getMessage()));
                } catch (PcManagement.Conflict conflict) {
                    throw new WebPanelService.Problem(409, safeMessage(conflict.getMessage()));
                } catch (PcManagement.Unavailable unavailable) {
                    throw new WebPanelService.Problem(422, safeMessage(unavailable.getMessage()));
                } catch (IllegalArgumentException invalid) {
                    throw new WebPanelService.Problem(400, safeMessage(invalid.getMessage()));
                }
                try { result.persisted().get(10, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException timeout) {
                    throw new WebPanelService.Problem(503, "A alteração foi enviada ao armazenamento, mas a gravação ainda está ocupada. Aguarde e atualize o PC.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new WebPanelService.Problem(503, "A gravação foi interrompida. Atualize o PC antes de repetir a alteração.");
                } catch (java.util.concurrent.ExecutionException failure) {
                    throw new WebPanelService.Problem(503, "Não foi possível confirmar a gravação. Atualize o PC antes de repetir a alteração.");
                }
                json(exchange, 200, Map.of("action", result.action(), "player", result.player().toString(),
                        "box", result.box(), "position", result.position(), "message", "PC atualizado."));
            } finally {
                pcOperations.release();
            }
            return;
        }
        if (path.equals("/api/pc/players") || path.equals("/api/pc/box")) {
            if (!method.equals("GET")) { methodNotAllowed(exchange, "GET"); return; }
            inspectPc(exchange, path);
            return;
        }
        if (path.equals("/api/action") && method.equals("POST")) {
            if (!canEdit(sessionId)) { json(exchange, 403, Map.of("message", "Esta credencial permite apenas consulta.")); return; }
            throttle(sessionId);
            var payload = ConfigStore.GSON.fromJson(body(exchange), WebPanelService.ActionRequest.class);
            if (payload != null && "applyLiveSettings".equals(payload.action())) {
                json(exchange, 200, onServer(() -> service.applyLiveGameplaySettings(
                        sessionId, role(sessionId), actor(sessionId), payload.expectedRevision())));
                return;
            }
            if (payload != null && "save".equals(payload.action())) {
                json(exchange, 202, onServer(() -> service.beginSave(sessionId, auditActor(sessionId), payload, persistenceExecutor, minecraft::execute)));
                return;
            }
            if (payload != null && Set.of("restore", "restoreRevision", "discard").contains(payload.action())) {
                json(exchange, 202, onServer(() -> service.beginRecoveryAction(sessionId, auditActor(sessionId), payload, persistenceExecutor, minecraft::execute)));
                return;
            }
            json(exchange, 200, onServer(() -> service.action(sessionId, payload)));
            return;
        }
        if (path.equals("/api/server/restart")) { methodNotAllowed(exchange, "POST"); return; }
        if (path.equals("/api/session")) { methodNotAllowed(exchange, "GET, POST, DELETE"); return; }
        if (path.equals("/api/state") || path.equals("/api/dashboard") || path.equals("/api/job") || path.equals("/api/species") || path.equals("/api/action") || path.equals("/api/simulate") || path.equals("/api/history") || path.equals("/api/pc/edit")) {
            methodNotAllowed(exchange, path.equals("/api/action") || path.equals("/api/simulate") || path.equals("/api/pc/edit") ? "POST" : "GET"); return;
        }
        json(exchange, 404, Map.of("message", "Rota não encontrada."));
    }

    private void inspectPc(HttpExchange exchange, String path) throws Exception {
        if (!pcOperations.tryAcquire()) throw new WebPanelService.Problem(503, "Já há uma operação de PC em andamento. Tente novamente.");
        try {
            var parameters = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/api/pc/players")) {
                String search = parameters.getOrDefault("q", "");
                if (search.length() > 64) throw new WebPanelService.Problem(400, "Busca de jogador muito longa.");
                String cursor = parameters.get("cursor");
                var directory = onServer(() -> PcInspection.directory(minecraft));
                PcInspection.PlayerPage page;
                try { page = PcInspection.players(directory, search, cursor); }
                catch (IOException failure) { throw new WebPanelService.Problem(422, "Não foi possível indexar os jogadores do servidor."); }
                LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                response.put("players", page.players());
                response.put("nextCursor", page.nextCursor());
                json(exchange, 200, response);
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
            pcOperations.release();
        }
    }

    private void login(HttpExchange exchange, boolean secure) throws Exception {
        JsonObject payload = ConfigStore.GSON.fromJson(body(exchange), JsonObject.class);
        String received = payload != null && payload.has("token") && payload.get("token").isJsonPrimitive()
                ? payload.get("token").getAsString() : "";
        Credential credential = null;
        int rejected = 0;
        synchronized (loginAttempts) {
            long now = System.currentTimeMillis();
            if (now - loginAttempts.started > 60_000) {
                loginAttempts.started = now;
                loginAttempts.failures = 0;
            }
            if (loginAttempts.failures >= 5) rejected = 429;
            else {
                credential = verifyToken(received);
                if (credential == null) {
                    loginAttempts.failures++;
                    rejected = 401;
                } else {
                    loginAttempts.started = now;
                    loginAttempts.failures = 0;
                }
            }
        }
        if (rejected == 429) { json(exchange, 429, Map.of("message", "Muitas tentativas. Aguarde um minuto.")); return; }
        if (rejected == 401) {
            json(exchange, 401, Map.of("message", "Token inválido."));
            return;
        }
        if (sessions.size() >= 64) {
            long now = System.currentTimeMillis();
            for (var entry : sessions.entrySet()) {
                String expiredId = entry.getKey();
                if (entry.getValue().expiresAt() <= now && sessions.remove(expiredId, entry.getValue())) {
                    lastAction.remove(expiredId);
                    service.closeSession(expiredId);
                }
            }
        }
        if (sessions.size() >= 64) { json(exchange, 429, Map.of("message", "Limite de sessões administrativas atingido.")); return; }
        byte[] raw = new byte[32]; SECURE_RANDOM.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        String role = credential.role();
        sessions.put(id, new Session(role, credential.name(), System.currentTimeMillis() + SESSION_LIFETIME_MS));
        setSessionCookie(exchange, id, 43200, secure);
        json(exchange, 200, Map.of("authenticated", true, "role", role, "actor", credential.name()));
    }

    private Credential verifyToken(String received) {
        byte[] supplied = received.getBytes(StandardCharsets.UTF_8);
        Credential match = null;
        for (Credential credential : credentials) {
            boolean equal = MessageDigest.isEqual(credential.token().getBytes(StandardCharsets.UTF_8), supplied);
            if (equal && match == null) match = credential;
        }
        return match;
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
        Session session = sessions.get(id);
        if (session == null) return null;
        long now = System.currentTimeMillis();
        if (session.expiresAt() <= now) {
            sessions.remove(id);
            lastAction.remove(id);
            service.closeSession(id);
            return null;
        }
        sessions.put(id, new Session(session.role(), session.actor(), now + SESSION_LIFETIME_MS));
        return id;
    }

    private String role(String sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? "viewer" : session.role();
    }

    private String actor(String sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? "desconhecido" : session.actor();
    }

    private String auditActor(String sessionId) { return "web:" + actor(sessionId); }

    private boolean canEdit(String sessionId) { return role(sessionId).equals("admin"); }

    private void restart(HttpExchange exchange, String actor) throws IOException {
        if (restartSignal == null) {
            throw new WebPanelService.Problem(409, "Inicie o servidor pelo supervisor para habilitar o reinício pelo painel.");
        }
        if (!restarting.compareAndSet(false, true)) {
            throw new WebPanelService.Problem(409, "O servidor já está reiniciando.");
        }
        try {
            Files.writeString(restartSignal, "restart", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            restarting.set(false);
            GenerationControl.LOGGER.warn("Não foi possível sinalizar o reinício ao supervisor.", failure);
            throw new WebPanelService.Problem(503, "O supervisor do servidor não está disponível.");
        }
        GenerationControl.LOGGER.info("Reinício solicitado por {}.", actor);
        try {
            json(exchange, 202, Map.of("restarting", true));
        } finally {
            exchange.close();
            minecraft.execute(() -> minecraft.stop(false));
        }
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

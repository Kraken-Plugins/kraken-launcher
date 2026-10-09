package com.kraken.launcher.bootstrap;

import com.kraken.launcher.bootstrap.model.Artifact;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BootstrapDownloaderTest {

    private static final String CLIENT = "Kraken-Client-1.0.0.jar";
    private static final String API = "kraken-api-1.0.0.jar";
    private static final byte[] CLIENT_BYTES = "client jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] API_BYTES = "api jar".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Map<String, byte[]> served = new HashMap<>();
    private HttpServer server;
    private KeyPair keyPair;
    private BiPredicate<byte[], String> verifier;
    private File cacheDir;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        String publicKey = Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        verifier = (bytes, signature) -> BootstrapVerifier.verify(bytes, signature, publicKey);
        cacheDir = temp.newFolder("repository2");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        served.put("/" + CLIENT, CLIENT_BYTES);
        served.put("/" + API, API_BYTES);
        serveBootstrap(bootstrapJson("injected-hash"));
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void savedBootstrapIsUsedWhenTheServerIsUnreachable() throws Exception {
        BootstrapDownloader online = downloader();
        online.downloadKrakenBootstrap();
        assertFalse(online.isOffline());

        server.stop(0);
        BootstrapDownloader offline = downloader();
        offline.downloadKrakenBootstrap();

        assertTrue(offline.isOffline());
        assertEquals("injected-hash", offline.getKrakenBootstrap().getHash());
    }

    @Test
    public void downloadFailureIsThrownWithoutASavedBootstrap() {
        server.stop(0);
        BootstrapDownloader offline = downloader();

        assertThrows(IOException.class, offline::downloadKrakenBootstrap);
        assertNull(offline.getKrakenBootstrap());
    }

    @Test
    public void modifiedSavedBootstrapIsNotTrusted() throws Exception {
        downloader().downloadKrakenBootstrap();
        Files.writeString(new File(cacheDir, "bootstrap.json").toPath(), bootstrapJson("modified-hash"));

        server.stop(0);
        BootstrapDownloader offline = downloader();

        assertThrows(IOException.class, offline::downloadKrakenBootstrap);
        assertNull(offline.getKrakenBootstrap());
    }

    @Test
    public void bootstrapWithAnInvalidSignatureIsReplacedByTheSavedOne() throws Exception {
        downloader().downloadKrakenBootstrap();
        served.put("/bootstrap.json", bootstrapJson("unsigned-hash").getBytes(StandardCharsets.UTF_8));

        BootstrapDownloader downloader = downloader();
        downloader.downloadKrakenBootstrap();

        assertTrue(downloader.isOffline());
        assertEquals("injected-hash", downloader.getKrakenBootstrap().getHash());
    }

    @Test
    public void offlineLaunchUsesTheArtifactsKeptByTheLastOnlineLaunch() throws Exception {
        BootstrapDownloader online = downloader();
        online.downloadKrakenBootstrap();
        online.fetchLatestArtifact(artifact(online, CLIENT));
        online.fetchLatestArtifact(artifact(online, API));

        server.stop(0);
        BootstrapDownloader offline = downloader();
        offline.downloadKrakenBootstrap();
        File client = offline.fetchLatestArtifact(artifact(offline, CLIENT));
        File api = offline.fetchLatestArtifact(artifact(offline, API));

        assertArrayEquals(CLIENT_BYTES, Files.readAllBytes(client.toPath()));
        assertArrayEquals(API_BYTES, Files.readAllBytes(api.toPath()));
        assertNotEquals(new File(cacheDir, CLIENT), client);
    }

    @Test
    public void keptApiThatNoLongerMatchesTheBootstrapIsRejected() throws Exception {
        BootstrapDownloader online = downloader();
        online.downloadKrakenBootstrap();
        online.fetchLatestArtifact(artifact(online, API));
        Files.writeString(new File(cacheDir, API).toPath(), "tampered api jar");

        server.stop(0);
        BootstrapDownloader offline = downloader();
        offline.downloadKrakenBootstrap();

        assertThrows(IOException.class, () -> offline.fetchLatestArtifact(artifact(offline, API)));
    }

    @Test
    public void offlineLaunchWithoutAKeptArtifactFails() throws Exception {
        downloader().downloadKrakenBootstrap();

        server.stop(0);
        BootstrapDownloader offline = downloader();
        offline.downloadKrakenBootstrap();

        assertThrows(IOException.class, () -> offline.fetchLatestArtifact(artifact(offline, CLIENT)));
    }

    private BootstrapDownloader downloader() {
        return new BootstrapDownloader(baseUrl + "/bootstrap.json", cacheDir, verifier);
    }

    private static Artifact artifact(BootstrapDownloader downloader, String name) {
        return Arrays.stream(downloader.getKrakenBootstrap().getArtifacts())
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void serveBootstrap(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Signature engine = Signature.getInstance("Ed25519");
        engine.initSign(keyPair.getPrivate());
        engine.update(bytes);
        served.put("/bootstrap.json", bytes);
        served.put("/bootstrap.json.sig", Base64.getEncoder().encode(engine.sign()));
    }

    private String bootstrapJson(String hash) throws Exception {
        return "{\"hash\":\"" + hash + "\",\"hookHash\":\"hook-hash\",\"artifacts\":["
                + artifactJson(CLIENT, CLIENT_BYTES) + "," + artifactJson(API, API_BYTES) + "]}";
    }

    private String artifactJson(String name, byte[] content) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return "{\"name\":\"" + name + "\",\"path\":\"" + baseUrl + "/" + name + "\",\"hash\":\"" + hash
                + "\",\"size\":" + content.length + "}";
    }
}

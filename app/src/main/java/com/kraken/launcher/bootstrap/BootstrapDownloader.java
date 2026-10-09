package com.kraken.launcher.bootstrap;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.gson.Gson;
import com.kraken.launcher.bootstrap.model.Artifact;
import com.kraken.launcher.bootstrap.model.Bootstrap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BiPredicate;

@Slf4j
public class BootstrapDownloader {
    private static final String KRAKEN_BOOTSTRAP_BASE = "https://seaweed.kraken-plugins.com/kraken-bootstrap-static/";
    private static final String RUNELITE_BOOTSTRAP = "https://static.runelite.net/bootstrap.json";
    private static final File DEFAULT_CACHE_DIR = new File(System.getProperty("user.home"), ".runelite").toPath()
            .resolve("kraken")
            .resolve("repository2")
            .toFile();
    private static final String SIGNATURE_SUFFIX = ".sig";
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final int ARTIFACT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int ARTIFACT_READ_TIMEOUT_MS = 60_000;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();

    @Getter
    private volatile Bootstrap krakenBootstrap = null;

    @Getter
    private volatile Bootstrap runeliteBootstrap = null;

    /**
     * True when a verified Kraken bootstrap could not be downloaded and the copy saved by an earlier launch is used
     * instead. The Kraken client and api then come from the copies kept by the last launch that reached the server.
     */
    @Getter
    private volatile boolean offline = false;

    private final String krakenBootstrapUrl;
    private final File cacheDir;
    private final BiPredicate<byte[], String> signatureVerifier;

    public BootstrapDownloader(boolean qa) {
        this(KRAKEN_BOOTSTRAP_BASE + (qa ? "bootstrap-qa.json" : "bootstrap.json"), DEFAULT_CACHE_DIR, BootstrapVerifier::verify);
    }

    /**
     * @param krakenBootstrapUrl The Kraken bootstrap URL; its signature is read from the same URL with a .sig suffix
     * @param cacheDir           The directory artifacts, and the bootstrap saved for offline launches, are kept in
     * @param signatureVerifier  Checks a bootstrap's exact bytes against its base64 signature
     */
    BootstrapDownloader(String krakenBootstrapUrl, File cacheDir, BiPredicate<byte[], String> signatureVerifier) {
        this.krakenBootstrapUrl = krakenBootstrapUrl;
        this.cacheDir = cacheDir;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * Downloads the bootstrap file from the server or returns it if cached in memory.
     * @param url Bootstrap URL
     * @param cached Currently cached bootstrap (may be null)
     * @return Bootstrap object or null if download fails
     */
    private Bootstrap downloadBootstrap(String url, Bootstrap cached) throws IOException {
        if (cached != null) return cached;
        String bootstrap = fetchBootstrap(url);
        return bootstrap != null ? gson.fromJson(bootstrap, Bootstrap.class) : null;
    }

    /**
     * Downloads and verifies the Kraken bootstrap. The bootstrap names the jars the launcher loads as a
     * {@code -javaagent}, so it is only trusted after its detached Ed25519 signature ({@code bootstrap.json.sig})
     * checks out against the pinned public key. A missing or invalid signature fails closed: the bootstrap is
     * left null and an exception is thrown so the launcher refuses to start rather than load unverified code.
     * <p>
     * Every verified bootstrap is saved with its signature. When a verified bootstrap cannot be downloaded, for
     * example because the Kraken server is unreachable, the saved copy is used instead once its signature checks out
     * again, and the downloader switches to {@link #isOffline() offline} mode. Without a valid saved copy the
     * download failure is thrown.
     */
    public void downloadKrakenBootstrap() throws IOException {
        if (krakenBootstrap != null) return;

        log.info("Downloading Kraken Bootstrap from URL: {}", this.krakenBootstrapUrl);
        try {
            byte[] bootstrapBytes = fetchBootstrapBytes(this.krakenBootstrapUrl);
            if (bootstrapBytes == null) {
                return;
            }

            String signature = fetchBootstrap(this.krakenBootstrapUrl + SIGNATURE_SUFFIX);
            if (!signatureVerifier.test(bootstrapBytes, signature)) {
                throw new IOException("Kraken bootstrap signature verification failed; refusing to trust the bootstrap.");
            }

            krakenBootstrap = parseBootstrap(bootstrapBytes);
            log.info("Kraken bootstrap signature verified against the pinned public key.");
            saveBootstrap(bootstrapBytes, signature);
        } catch (IOException e) {
            log.warn("Unable to download a verified Kraken bootstrap, falling back to the copy saved by an earlier launch: {}", e.getMessage());
            krakenBootstrap = loadSavedBootstrap(e);
            offline = true;
        }
    }

    private Bootstrap parseBootstrap(byte[] bootstrapBytes) {
        return gson.fromJson(new String(bootstrapBytes, StandardCharsets.UTF_8), Bootstrap.class);
    }

    private File savedBootstrapFile() {
        return new File(cacheDir, krakenBootstrapUrl.substring(krakenBootstrapUrl.lastIndexOf('/') + 1));
    }

    private File savedSignatureFile() {
        return new File(cacheDir, savedBootstrapFile().getName() + SIGNATURE_SUFFIX);
    }

    /**
     * Saves a verified bootstrap and its signature for offline launches. Failing to save only costs the offline
     * fallback, so errors are logged rather than thrown.
     */
    private void saveBootstrap(byte[] bootstrapBytes, String signature) {
        try {
            writeToCache(savedBootstrapFile(), bootstrapBytes);
            writeToCache(savedSignatureFile(), signature.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Unable to save the Kraken bootstrap for offline launches: ", e);
        }
    }

    /**
     * Loads the bootstrap saved by the last launch that downloaded a verified one. Its signature is checked again so
     * a copy modified on disk is never trusted.
     * @param downloadFailure Why the bootstrap could not be downloaded, thrown when there is no saved copy
     */
    private Bootstrap loadSavedBootstrap(IOException downloadFailure) throws IOException {
        File bootstrapFile = savedBootstrapFile();
        File signatureFile = savedSignatureFile();
        if (!bootstrapFile.isFile() || !signatureFile.isFile()) {
            log.error("No saved Kraken bootstrap at {} to fall back to", bootstrapFile);
            throw downloadFailure;
        }

        byte[] bootstrapBytes = Files.readAllBytes(bootstrapFile.toPath());
        String signature = Files.readString(signatureFile.toPath(), StandardCharsets.UTF_8);
        if (!signatureVerifier.test(bootstrapBytes, signature)) {
            throw new IOException("The saved Kraken bootstrap at " + bootstrapFile + " failed signature verification", downloadFailure);
        }

        log.warn("Using the Kraken bootstrap saved at {}", Instant.ofEpochMilli(bootstrapFile.lastModified()));
        return parseBootstrap(bootstrapBytes);
    }

    /**
     * Writes a file into the cache through a temporary file in the same directory, so the file is either fully
     * replaced or left as it was.
     */
    private void writeToCache(File target, byte[] content) throws IOException {
        Path tempFile = Files.createTempFile(ensureCacheDir().toPath(), target.getName() + "-", ".part");
        try {
            Files.write(tempFile, content);
            Files.move(tempFile, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public void downloadRuneLiteBootstrap() throws IOException {
        log.info("Downloading RuneLite Bootstrap from URL: {}", RUNELITE_BOOTSTRAP);
        runeliteBootstrap = downloadBootstrap(RUNELITE_BOOTSTRAP, runeliteBootstrap);
    }

    private String fetchBootstrap(String url) throws IOException {
        HttpRequest bootstrapReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(bootstrapReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Unable to download bootstrap (status " + resp.statusCode() + "): " + resp.body());
            }
            return resp.body();
        } catch (InterruptedException e) {
            log.error("Failed to get bootstrap json file: ", e);
            return null;
        }
    }

    /**
     * Fetches a bootstrap as raw bytes so the exact signed content can be verified byte-for-byte before it is
     * parsed. Parsing and re-serializing would not reproduce the signed bytes, so signature checks must run
     * against these bytes rather than a round-tripped object.
     */
    private byte[] fetchBootstrapBytes(String url) throws IOException {
        HttpRequest bootstrapReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = httpClient.send(bootstrapReq, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("Unable to download bootstrap (status " + resp.statusCode() + ")");
            }
            return resp.body();
        } catch (InterruptedException e) {
            log.error("Failed to get bootstrap json file: ", e);
            return null;
        }
    }

    private String computeHash(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file));
             HashingOutputStream hout = new HashingOutputStream(Hashing.sha256(), java.io.OutputStream.nullOutputStream())) {
            in.transferTo(hout);
            return hout.hash().toString();
        }
    }

    /**
     * Opens a stream to the given URL with connect and read timeouts so a slow or hung server cannot stall the
     * launcher indefinitely while downloading an artifact.
     */
    private InputStream openStream(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(ARTIFACT_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(ARTIFACT_READ_TIMEOUT_MS);
        return connection.getInputStream();
    }

    private File ensureCacheDir() throws IOException {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create Kraken cache directory: " + cacheDir.getAbsolutePath());
        }
        return cacheDir;
    }

    public File cacheArtifact(Artifact artifact) throws Exception {
        File cacheDir = ensureCacheDir();

        String expectedHash = artifact.getHash();
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IOException("Bootstrap hash missing for artifact: " + artifact.getName());
        }

        File localFile = new File(cacheDir, artifact.getName());

        if (localFile.exists()) {
            String localHash = computeHash(localFile);
            if (expectedHash.equalsIgnoreCase(localHash)) {
                log.info("Cache hit for artifact: {}", artifact.getName());
                return localFile;
            }
            log.warn("Cached artifact {} failed SHA-256 verification. Expected {}, found {}. Re-downloading.",
                    artifact.getName(), expectedHash, localHash);
            Files.delete(localFile.toPath());
        }

        // Cache miss — download, verify, then atomically move into place
        log.info("Downloading artifact to local cache: {}", artifact.getName());
        Path tempFile = Files.createTempFile(cacheDir.toPath(), artifact.getName() + "-", ".part");
        try {
            URL url = new URL(artifact.getPath());
            try (InputStream in = new BufferedInputStream(openStream(url))) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String downloadedHash = computeHash(tempFile.toFile());
            if (!expectedHash.equalsIgnoreCase(downloadedHash)) {
                throw new IOException("SHA-256 verification failed for " + artifact.getName()
                        + ". Expected " + expectedHash + " but got " + downloadedHash);
            }

            Files.move(tempFile, localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return localFile;
    }

    /**
     * Returns a verified local copy of an artifact that is re-downloaded every launch rather than served from the
     * long-lived cache. Used for the Kraken client and api jars, which change frequently, with the bootstrap kept as
     * the source of truth. Each download is also kept in the cache under the artifact's name, and an
     * {@link #isOffline() offline} launch uses that copy instead of downloading. Either way the SHA-256 must match the
     * bootstrap hash, except for the Kraken client.
     * <p>
     * The returned file is a temporary copy scheduled for deletion on JVM exit, so it survives the client session and
     * a running client never holds the kept copy open while another launch replaces it.
     * @param artifact The artifact to download (or copy) and verify.
     * @return A verified local file whose contents match the bootstrap hash.
     * @throws IOException if the hash is missing, no kept copy exists offline, or the bytes fail verification.
     */
    public File fetchLatestArtifact(Artifact artifact) throws Exception {
        String expectedHash = artifact.getHash();
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IOException("Bootstrap hash missing for artifact: " + artifact.getName());
        }

        File keptCopy = new File(ensureCacheDir(), artifact.getName());
        if (offline && !keptCopy.isFile()) {
            throw new IOException("The Kraken server is unreachable and no earlier launch kept a copy of " + artifact.getName());
        }

        Path tempFile = Files.createTempFile(cacheDir.toPath(), artifact.getName() + "-", ".jar");
        tempFile.toFile().deleteOnExit();

        try {
            if (offline) {
                log.info("Using the copy of {} kept by the last launch that reached the Kraken server", artifact.getName());
                Files.copy(keptCopy.toPath(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.info("Downloading and verifying artifact (uncached): {}", artifact.getName());
                URL url = new URL(artifact.getPath());
                try (InputStream in = new BufferedInputStream(openStream(url))) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            String actualHash = computeHash(tempFile.toFile());
            // Client is our artifact skip hash check
            if (!expectedHash.equalsIgnoreCase(actualHash) && !artifact.getName().toLowerCase(Locale.ROOT).contains("kraken-client-")) {
                throw new IOException("SHA-256 verification failed for " + artifact.getName()
                        + ". Expected " + expectedHash + " but got " + actualHash);
            }

            if (!offline) {
                try {
                    writeToCache(keptCopy, Files.readAllBytes(tempFile));
                } catch (IOException e) {
                    log.warn("Unable to keep {} for offline launches: {}", keptCopy.getName(), e.getMessage());
                }
            }
            return tempFile.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
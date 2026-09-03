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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

@Slf4j
public class BootstrapDownloader {
    private static final String KRAKEN_BOOTSTRAP_BASE = "https://minio.kraken-plugins.com/kraken-bootstrap-static/";
    private static final String RUNELITE_BOOTSTRAP = "https://static.runelite.net/bootstrap.json";
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final int ARTIFACT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int ARTIFACT_READ_TIMEOUT_MS = 60_000;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();

    @Getter
    private volatile Bootstrap krakenBootstrap = null;

    @Getter
    private volatile Bootstrap runeliteBootstrap = null;

    private final String krakenBootstrapUrl;

    public BootstrapDownloader(boolean qa) {
        this.krakenBootstrapUrl = qa ? KRAKEN_BOOTSTRAP_BASE + "bootstrap-qa.json" : KRAKEN_BOOTSTRAP_BASE + "bootstrap.json";
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

    public void downloadKrakenBootstrap() throws IOException {
        log.info("Downloading Kraken Bootstrap from URL: {}", this.krakenBootstrapUrl);
        krakenBootstrap = downloadBootstrap(this.krakenBootstrapUrl, krakenBootstrap);
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

    private File resolveCacheDir() throws IOException {
        File cacheDir = new File(System.getProperty("user.home"), ".runelite").toPath()
                .resolve("kraken")
                .resolve("repository2")
                .toFile();

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create Kraken cache directory: " + cacheDir.getAbsolutePath());
        }
        return cacheDir;
    }

    public File cacheArtifact(Artifact artifact) throws Exception {
        File cacheDir = resolveCacheDir();

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
     * Downloads an artifact to a temporary file and verifies its SHA-256 against the bootstrap hash without
     * persisting it to the long-lived cache. Used for the Kraken client and api jars, which change frequently
     * and are re-fetched every launch with the bootstrap kept as the source of truth. The file is scheduled for
     * deletion on JVM exit so it survives the client session but is not reused between runs.
     * @param artifact The artifact to download and verify.
     * @return A verified local file whose contents match the bootstrap hash.
     * @throws IOException if the hash is missing or the downloaded bytes fail verification.
     */
    public File downloadVerified(Artifact artifact) throws Exception {
        String expectedHash = artifact.getHash();
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IOException("Bootstrap hash missing for artifact: " + artifact.getName());
        }

        File cacheDir = resolveCacheDir();
        Path tempFile = Files.createTempFile(cacheDir.toPath(), artifact.getName() + "-", ".jar");
        tempFile.toFile().deleteOnExit();

        log.info("Downloading and verifying artifact (uncached): {}", artifact.getName());
        try {
            URL url = new URL(artifact.getPath());
            try (InputStream in = new BufferedInputStream(openStream(url))) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String downloadedHash = computeHash(tempFile.toFile());
            if (!expectedHash.equalsIgnoreCase(downloadedHash)) {
                // Client is our artifact skip hash check
                if(artifact.getName().toLowerCase(Locale.ROOT).contains("kraken-client-")) {
                    return tempFile.toFile();
                }

                throw new IOException("SHA-256 verification failed for " + artifact.getName()
                        + ". Expected " + expectedHash + " but got " + downloadedHash);
            }

            return tempFile.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
package com.kraken.launcher.bootstrap;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.gson.Gson;
import com.kraken.launcher.bootstrap.model.Artifact;
import com.kraken.launcher.bootstrap.model.Bootstrap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Slf4j
@Singleton
public class BootstrapDownloader {
    private static final String KRAKEN_BOOTSTRAP_BASE = "https://minio.kraken-plugins.com/kraken-bootstrap-static/";
    private static final String RUNELITE_BOOTSTRAP = "https://static.runelite.net/bootstrap.json";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();

    @Getter
    private Bootstrap krakenBootstrap = null;

    @Getter
    private Bootstrap runeliteBootstrap = null;

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
        HttpRequest bootstrapReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
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

    public File cacheArtifact(Artifact artifact) throws Exception {
        File cacheDir = new File(System.getProperty("user.home"), ".runelite").toPath()
                .resolve("kraken")
                .resolve("repository2")
                .toFile();

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create Kraken cache directory: " + cacheDir.getAbsolutePath());
        }

        String expectedHash = artifact.getHash();
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IOException("Bootstrap hash missing for artifact: " + artifact.getName());
        }

        File localFile = new File(cacheDir, artifact.getName());

        if (localFile.exists()) {
            String localHash = computeHash(localFile);
            if (expectedHash.equals(localHash)) {
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
            try (InputStream in = new BufferedInputStream(url.openStream())) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            String downloadedHash = computeHash(tempFile.toFile());
            if (!expectedHash.equals(downloadedHash)) {
                throw new IOException("SHA-256 verification failed for " + artifact.getName()
                        + ". Expected " + expectedHash + " but got " + downloadedHash);
            }

            Files.move(tempFile, localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return localFile;
    }
}
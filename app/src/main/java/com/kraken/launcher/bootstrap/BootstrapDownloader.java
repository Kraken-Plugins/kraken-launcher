package com.kraken.launcher.bootstrap;

import com.google.gson.Gson;
import com.kraken.launcher.bootstrap.model.Bootstrap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Singleton
public class BootstrapDownloader {
    private static final String KRAKEN_BOOTSTRAP = "https://minio.kraken-plugins.com/kraken-bootstrap-static/bootstrap.json";
    private static final String RUNELITE_BOOTSTRAP = "https://static.runelite.net/bootstrap.json";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();

    @Getter
    private Bootstrap krakenBootstrap = null;

    @Getter
    private Bootstrap runeliteBootstrap = null;

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
        log.info("Downloading Kraken Bootstrap from URL: {}", KRAKEN_BOOTSTRAP);
        krakenBootstrap = downloadBootstrap(KRAKEN_BOOTSTRAP, krakenBootstrap);
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
}
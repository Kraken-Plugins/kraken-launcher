package net.runelite.client.plugins.kraken;

import com.google.gson.Gson;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.kraken.model.Artifact;
import net.runelite.client.plugins.kraken.model.Bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@PluginDescriptor(
        name = "Kraken Bootstrap",
        description = "Bootstraps the Kraken Client without changing RuneLite's stack trace",
        hidden = true
)
@Slf4j
public class KrakenBootstrapPlugin extends Plugin {

    @Inject
    private PluginManager pluginManager;

    private final Gson gson = new Gson();

    @Override
    protected void startUp() throws Exception {
        URL[] krakenUrls = getKrakenArtifactUrls();

        if (krakenUrls.length == 0) {
            log.error("No Kraken dependencies found via bootstrap. Cannot start client.");
            return;
        }

        // Create the isolated Kraken ClassLoader
        URLClassLoader krakenClassLoader = new URLClassLoader(krakenUrls, this.getClass().getClassLoader());
        Class<?> actualKrakenPluginClass = krakenClassLoader.loadClass("com.krakenclient.KrakenLoaderPlugin");
        List<Plugin> loadedPlugins = pluginManager.loadPlugins(Collections.singletonList(actualKrakenPluginClass), null);

        if (!loadedPlugins.isEmpty()) {
            Plugin krakenClient = loadedPlugins.get(0);
            pluginManager.setPluginEnabled(krakenClient, true);
            pluginManager.startPlugin(krakenClient);
            log.info("Kraken core successfully loaded and started.");
        }
    }

    private Bootstrap fetchBootstrap() throws IOException, InterruptedException {
        HttpRequest bootstrapReq = HttpRequest.newBuilder().uri(URI.create("https://minio.kraken-plugins.com/kraken-bootstrap-static/bootstrap.json"))
                .GET()
                .build();

        HttpResponse<String> resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(bootstrapReq, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IOException("Unable to download bootstrap (status " + resp.statusCode() + "): " + resp.body());
        }
        String body = resp.body();
        return gson.fromJson(body, Bootstrap.class);
    }

    /**
     * Retrieves and parses the dependency URLs passed from the Launcher.
     */
    private URL[] getKrakenArtifactUrls() {
        try {
            Bootstrap bootstrap = fetchBootstrap();

            if(bootstrap == null) {
                log.error("Failed to fetch Kraken bootstrap (null).");
                return new URL[]{};
            }

            List<URL> urls = new ArrayList<>();
            for(Artifact artifact : bootstrap.getArtifacts()) {
                urls.add(new URL(artifact.getPath()));
            }
            return urls.toArray(new URL[0]);
        } catch (Exception e) {
            log.error("Error fetching Kraken bootstrap file: ", e);
            return new URL[]{};
        }
    }
}
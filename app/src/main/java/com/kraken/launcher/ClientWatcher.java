package com.kraken.launcher;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.SplashScreen;

import javax.inject.Inject;
import javax.swing.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Waits for the RuneLite's splash screen to be closed. Once closed the client is started and the
 * Kraken loader plugin is initialized.
 */
@Slf4j
public class ClientWatcher {

    private static final long SPLASH_POLL_INTERVAL_MS = 2000;
    private static final long SPLASH_WAIT_TIMEOUT_MS = 120_000;

    private final PluginManager pluginManager;

    @Inject
    public ClientWatcher(PluginManager pluginManager) {
        this.pluginManager = pluginManager;

        if(pluginManager == null) {
            log.error("PluginManager instance is null. Cannot proceed to load Kraken loader plugin.");
        }
    }

    /**
     * Starts the ClientWatcher process, which waits for the RuneLite splash screen to close before
     * initializing and starting the Kraken loader plugin. This method ensures that plugin loading
     * and initialization happens on the Event Dispatch Thread (EDT) to prevent concurrency issues.
     * This method is called reflectively from Launcher.java
     *
     * <p>During the splash screen wait period, the thread sleeps for a fixed interval, bounded by an overall
     * timeout so a splash screen that never closes does not spin forever. Once the splash screen closes (or the
     * timeout elapses), the Kraken loader plugin is loaded, enabled, and started using the provided
     * {@link PluginManager}. If the plugin has already been auto-started by RuneLite, it is stopped and restarted
     * to prevent inconsistent states.
     *
     * @param krakenLoaderPlugin The {@link Class} object representing the Kraken loader plugin to be
     *                           loaded and started. This must not be {@code null}.
     */
    public void start(Class<?> krakenLoaderPlugin) {
        log.info("Starting Client Watcher...");
        Thread watcherThread = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SPLASH_WAIT_TIMEOUT_MS);
            while (SplashScreen.isOpen()) {
                if (System.nanoTime() >= deadline) {
                    log.warn("Splash screen still open after {}ms; proceeding to load the Kraken plugin anyway.", SPLASH_WAIT_TIMEOUT_MS);
                    break;
                }
                try {
                    Thread.sleep(SPLASH_POLL_INTERVAL_MS);
                } catch (InterruptedException ex) {
                    log.warn("Interrupted while waiting for the splash screen to close; aborting Kraken plugin load.");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            SwingUtilities.invokeLater(() -> {
                log.info("Initializing Kraken loader plugin (EDT Thread): {}", krakenLoaderPlugin.getName());
                try {
                    // It is critical that loadPlugins and startPlugin run on the EDT
                    // to avoid racing with RuneLite's Config/Profile manager.
                    List<Plugin> loadedPlugins = pluginManager.loadPlugins(Collections.singletonList(krakenLoaderPlugin), null);
                    if (loadedPlugins.isEmpty()) {
                        log.error("PluginManager failed to load Kraken plugin (returned empty list)");
                        return;
                    }

                    Plugin krakenClient = loadedPlugins.get(0);

                    // Check if RuneLite auto-started this plugin from config
                    if (pluginManager.isPluginActive(krakenClient)) {
                        log.warn("Kraken Loader was auto-started by RuneLite (Zombie state). Restarting...");
                        pluginManager.stopPlugin(krakenClient);
                    }

                    pluginManager.setPluginEnabled(krakenClient, true);
                    pluginManager.startPlugin(krakenClient);

                    log.info("Kraken plugin started successfully on EDT thread.");
                } catch (AssertionError ae) {
                    log.error("AssertionError during Kraken startup. User might have -ea enabled or Profile state is invalid.", ae);
                } catch (Exception ex) {
                    log.error("failed to start Kraken loader plugin", ex);
                }
            });
        }, "com.kraken.launcher.client-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }
}
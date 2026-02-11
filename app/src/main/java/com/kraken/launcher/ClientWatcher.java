package com.kraken.launcher;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.SplashScreen;

import javax.inject.Inject;
import javax.swing.*;
import java.util.Collections;
import java.util.List;

/**
 * Waits for the RuneLite's splash screen to be closed. Once closed the client is started and the
 * Kraken loader plugin is initialized.
 */
@Slf4j
public class ClientWatcher {

    private final EventBus eventBus;
    private final PluginManager pluginManager;

    @Inject
    public ClientWatcher(EventBus eventBus, PluginManager pluginManager) {
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;

        if(eventBus == null || pluginManager == null) {
            log.error("EventBus or PluginManager instance is null. Cannot proceed to load Kraken loader plugin.");
        }
    }

    public void start(Class<?> krakenLoaderPlugin) {
        eventBus.register(this);
        log.info("Starting Client Watcher...");
        new Thread(()-> {
            while(SplashScreen.isOpen()) {
                try {
                    Thread.sleep(2000);
                } catch(Exception ex) {
                    log.error("exception occurred while sleeping during splash screen: ", ex);
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
        }).start();
    }
}
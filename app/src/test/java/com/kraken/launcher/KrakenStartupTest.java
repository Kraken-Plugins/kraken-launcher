package com.kraken.launcher;

import org.junit.Test;

import static org.junit.Assert.assertSame;

public class KrakenStartupTest {

    /**
     * Uses --postinstall for the first call so no UI is shown. Kept as a single test because the guard is JVM-wide.
     */
    @Test
    public void runsOncePerJvm() {
        String[] postInstall = {"--postinstall"};
        assertSame(postInstall, KrakenStartup.beforeRuneLite(postInstall));

        String[] second = {"--qa", "--kraken-profile", "Foo"};
        assertSame(second, KrakenStartup.beforeRuneLite(second));
    }
}

package com.kraken.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KrakenAgentEntryTest {

    @Test
    public void parsesCurrentVersionStrings() {
        assertEquals(11, KrakenAgentEntry.featureVersion("11"));
        assertEquals(17, KrakenAgentEntry.featureVersion("17.0.19"));
        assertEquals(21, KrakenAgentEntry.featureVersion("21.0.11+10"));
        assertEquals(17, KrakenAgentEntry.featureVersion(" 17-ea "));
    }

    @Test
    public void parsesPreNineVersionStrings() {
        assertEquals(8, KrakenAgentEntry.featureVersion("1.8"));
        assertEquals(8, KrakenAgentEntry.featureVersion("1.8.0_292"));
    }

    @Test
    public void rejectsStringsThatAreNotVersions() {
        assertEquals(-1, KrakenAgentEntry.featureVersion(null));
        assertEquals(-1, KrakenAgentEntry.featureVersion(""));
        assertEquals(-1, KrakenAgentEntry.featureVersion("unknown"));
    }

    @Test
    public void theMessageNamesBothVersionsAndTheFix() {
        String message = KrakenAgentEntry.unsupportedJavaMessage(11);

        assertTrue(message.contains("Java " + KrakenAgentEntry.MINIMUM_JAVA_VERSION));
        assertTrue(message.contains("Java 11"));
        assertTrue(message.contains("https://runelite.net"));
    }
}

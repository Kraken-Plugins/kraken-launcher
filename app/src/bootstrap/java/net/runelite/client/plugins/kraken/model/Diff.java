package net.runelite.client.plugins.kraken.model;

import lombok.Data;

@Data
public class Diff {
    private String name;
    private String from;
    private String fromHash;
    private String hash;
    private String path;
    private int size;
}

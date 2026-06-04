package net.runelite.client.plugins.kraken.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Bootstrap {
    Artifact[] artifacts;
    String hash;
    String errorMessage;
    String hookHash;
}

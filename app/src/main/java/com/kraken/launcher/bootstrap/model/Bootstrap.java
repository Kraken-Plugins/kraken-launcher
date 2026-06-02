package com.kraken.launcher.bootstrap.model;

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

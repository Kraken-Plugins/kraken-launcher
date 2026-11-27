package com.kraken.launcher.util;

import lombok.Getter;

import java.io.IOException;

/**
 * Thrown when a Kraken host cannot be reached because of the player's DNS, so the caller can show
 * {@link DnsCheck.Result#message(String)} instead of the underlying network error.
 */
@Getter
public class DnsProblemException extends IOException {
    private final DnsCheck.Result result;

    /**
     * @param result The DNS check that found the problem
     * @param retry  What to do once it is fixed, such as "run the installer again"
     * @param cause  The connection failure that led to the check, or null when the check ran before connecting
     */
    public DnsProblemException(DnsCheck.Result result, String retry, Throwable cause) {
        super(result.message(retry), cause);
        this.result = result;
    }
}

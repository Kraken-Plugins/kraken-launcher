package com.kraken.launcher.util;

import org.junit.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DnsCheckTest {

    private static final String HOST = "seaweed.kraken-plugins.com";

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    /**
     * A resolver that answers from a fixed table and throws UnknownHostException for every other host.
     */
    private static DnsCheck.Resolver table(Map<String, String[]> answers) {
        return host -> {
            String[] literals = answers.get(host);
            if (literals == null) {
                throw new UnknownHostException(host);
            }
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = ip(literals[i]);
            }
            return addresses;
        };
    }

    private static DnsCheck.Result check(String... answers) {
        return DnsCheck.check(HOST, table(Map.of(HOST, answers)));
    }

    @Test
    public void cloudflareAnswerIsOk() {
        DnsCheck.Result result = check("104.21.31.22", "172.67.174.169");
        assertEquals(DnsCheck.Verdict.OK, result.getVerdict());
        assertFalse(result.isBlocked());
        assertFalse(result.isCertainlyBlocked());
    }

    @Test
    public void cloudflareIpv6AnswerIsOk() {
        assertEquals(DnsCheck.Verdict.OK, check("2606:4700:3031::6815:1f16").getVerdict());
    }

    @Test
    public void anyCloudflareAnswerIsOk() {
        assertEquals(DnsCheck.Verdict.OK, check("0.0.0.0", "104.21.31.22").getVerdict());
    }

    @Test
    public void zeroAddressIsSinkholed() {
        DnsCheck.Result result = check("0.0.0.0", "::");
        assertEquals(DnsCheck.Verdict.SINKHOLED, result.getVerdict());
        assertTrue(result.isCertainlyBlocked());
        assertEquals(2, result.getAddresses().size());
    }

    @Test
    public void loopbackIsSinkholed() {
        assertEquals(DnsCheck.Verdict.SINKHOLED, check("127.0.0.1").getVerdict());
        assertEquals(DnsCheck.Verdict.SINKHOLED, check("::1").getVerdict());
    }

    @Test
    public void blockPageIsRedirectedButNotCertain() {
        DnsCheck.Result result = check("51.15.69.11");
        assertEquals(DnsCheck.Verdict.REDIRECTED, result.getVerdict());
        assertTrue(result.isBlocked());
        assertFalse(result.isCertainlyBlocked());
        assertTrue(result.message("run the installer again").contains("DNS4EU"));
    }

    @Test
    public void proxyPlaceholderAndPrivateAddressesAreNeverCertain() {
        assertEquals(DnsCheck.Verdict.REDIRECTED, check("198.18.0.12").getVerdict());
        assertFalse(check("198.18.0.12").isCertainlyBlocked());
        assertFalse(check("192.168.1.1").isCertainlyBlocked());
        assertFalse(check("10.0.0.1").isCertainlyBlocked());
        assertFalse(check("100.64.0.1").isCertainlyBlocked());
    }

    @Test
    public void unresolvedHostWhileOthersResolveIsNotFound() {
        DnsCheck.Result result = DnsCheck.check(HOST, table(Map.of("github.com", new String[]{"140.82.114.4"})));
        assertEquals(DnsCheck.Verdict.NOT_FOUND, result.getVerdict());
        assertTrue(result.isCertainlyBlocked());
    }

    @Test
    public void secondControlHostIsTried() {
        DnsCheck.Result result = DnsCheck.check(HOST,
                table(Map.of("static.runelite.net", new String[]{"104.18.10.10"})));
        assertEquals(DnsCheck.Verdict.NOT_FOUND, result.getVerdict());
    }

    @Test
    public void nothingResolvingIsNoDns() {
        DnsCheck.Result result = DnsCheck.check(HOST, table(Map.of()));
        assertEquals(DnsCheck.Verdict.NO_DNS, result.getVerdict());
        assertFalse(result.isBlocked());
        assertEquals("No internet connection", result.title());
        assertTrue(result.message("run the installer again").contains("connected to the internet"));
    }

    @Test
    public void cloudflareRangeBoundaries() throws UnknownHostException {
        assertTrue(DnsCheck.isCloudflare(ip("104.16.0.0")));
        assertTrue(DnsCheck.isCloudflare(ip("104.23.255.255")));
        assertTrue(DnsCheck.isCloudflare(ip("172.64.0.0")));
        assertTrue(DnsCheck.isCloudflare(ip("172.71.255.255")));
        assertFalse(DnsCheck.isCloudflare(ip("172.63.255.255")));
        assertFalse(DnsCheck.isCloudflare(ip("172.72.0.0")));
        assertTrue(DnsCheck.isCloudflare(ip("198.41.128.1")));
        assertFalse(DnsCheck.isCloudflare(ip("198.41.127.255")));
        assertTrue(DnsCheck.isCloudflare(ip("173.245.63.255")));
        assertFalse(DnsCheck.isCloudflare(ip("173.245.64.0")));
        assertTrue(DnsCheck.isCloudflare(ip("131.0.75.255")));
        assertFalse(DnsCheck.isCloudflare(ip("131.0.76.0")));
        assertTrue(DnsCheck.isCloudflare(ip("141.101.127.255")));
        assertFalse(DnsCheck.isCloudflare(ip("141.101.128.0")));
        assertTrue(DnsCheck.isCloudflare(ip("2a06:98c7:ffff::1")));
        assertFalse(DnsCheck.isCloudflare(ip("2a06:98c8::1")));
    }

    @Test
    public void ipv4MappedCloudflareAnswerIsOk() {
        assertEquals(DnsCheck.Verdict.OK, check("::ffff:104.21.31.22").getVerdict());
    }

    @Test
    public void wholeZeroNetworkIsSinkholed() throws UnknownHostException {
        assertTrue(DnsCheck.isSinkhole(ip("0.1.2.3")));
        assertFalse(DnsCheck.isSinkhole(ip("1.0.0.1")));
    }

    @Test
    public void notFoundAndUnknownBlockPageMessages() {
        String notFound = DnsCheck.check(HOST, table(Map.of("github.com", new String[]{"140.82.114.4"})))
                .message("start RuneLite again");
        assertTrue(notFound.contains("did not return an address"));
        assertTrue(notFound.contains("then start RuneLite again:"));

        String redirected = check("203.0.113.7").message("run the installer again");
        assertTrue(redirected.contains("203.0.113.7, which is not a Kraken"));
    }

    @Test
    public void blockedMessageNamesTheHostAndTheFix() {
        String message = check("0.0.0.0").message("run the installer again");
        assertTrue(message.contains(HOST));
        assertTrue(message.contains("0.0.0.0"));
        assertTrue(message.contains("8.8.8.8"));
        assertTrue(message.contains("then run the installer again:"));
    }

    @Test
    public void onlyConnectionFailuresAreDiagnosed() {
        assertTrue(DnsCheck.isConnectionFailure(new UnknownHostException(HOST)));
        assertTrue(DnsCheck.isConnectionFailure(new IOException("wrapped", new ConnectException("refused"))));
        assertTrue(DnsCheck.isConnectionFailure(new SocketTimeoutException("connect timed out")));
        assertTrue(DnsCheck.isConnectionFailure(new SSLHandshakeException("No subject alternative DNS name")));
        assertFalse(DnsCheck.isConnectionFailure(new FileNotFoundException("missing checksum")));
        assertFalse(DnsCheck.isConnectionFailure(new IOException("failed SHA-256 verification")));
        assertFalse(DnsCheck.isConnectionFailure(null));
    }

    @Test
    public void httpClientUnresolvedHostIsAConnectionFailure() {
        ConnectException fromHttpClient = new ConnectException();
        fromHttpClient.initCause(new UnresolvedAddressException());
        assertTrue(DnsCheck.isConnectionFailure(new IOException("bootstrap", fromHttpClient)));
        assertTrue(DnsCheck.isConnectionFailure(new HttpConnectTimeoutException("HTTP connect timed out")));
    }

    @Test
    public void failuresPartWayThroughADownloadAreNotConnectionFailures() {
        assertFalse(DnsCheck.isConnectionFailure(new SocketException("Connection reset")));
        assertFalse(DnsCheck.isConnectionFailure(new SocketTimeoutException("Read timed out")));
        assertFalse(DnsCheck.isConnectionFailure(new HttpTimeoutException("request timed out")));
        assertTrue(DnsCheck.isConnectionFailure(new SocketTimeoutException("Connect timed out")));
    }
}

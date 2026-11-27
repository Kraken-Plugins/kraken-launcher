package com.kraken.launcher.util;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tells whether the system DNS resolver is keeping this machine from reaching a Kraken host. Kraken's hosts are
 * proxied by Cloudflare, so a correct answer is always a Cloudflare address. Security DNS filters that flag the domain
 * answer instead with 0.0.0.0, with the address of their own block page, or with "no such host", and some ISPs run
 * these filters on the resolvers they hand out. {@link Result#message(String)} explains the block to the player and
 * how to get around it.
 * <p>
 * Only {@link Verdict#SINKHOLED} and {@link Verdict#NOT_FOUND} are certain enough to stop before connecting. Any other
 * address outside Cloudflare is only treated as a block once a connection has failed, because VPNs and proxy tools
 * that answer every lookup with a placeholder address (such as 198.18.0.0/15) still connect, and because the range
 * list has to be updated if Kraken's hosting ever leaves Cloudflare.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DnsCheck {

    /**
     * Hosts on other networks. When Kraken's host does not resolve but one of these does, DNS works and is refusing
     * Kraken's host in particular.
     */
    static final List<String> CONTROL_HOSTS = List.of("github.com", "static.runelite.net");

    /**
     * Cloudflare's published ranges, from https://www.cloudflare.com/ips-v4 and https://www.cloudflare.com/ips-v6
     * (checked 2026-11-27).
     */
    private static final List<Cidr> CLOUDFLARE_RANGES = Arrays.stream(new String[]{
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
            "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
            "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
            "2a06:98c0::/29", "2c0f:f248::/32"
    }).map(Cidr::parse).collect(Collectors.toUnmodifiableList());

    /**
     * Block page addresses seen from filtering resolvers, named in the message so the player knows which filter it is.
     */
    private static final Map<String, String> KNOWN_BLOCK_PAGES = Map.of("51.15.69.11", "DNS4EU");

    public enum Verdict {
        /** At least one answer is a Cloudflare address, so DNS is not the problem. */
        OK,
        /** Every answer is unspecified or loopback (0.0.0.0, ::, 127.0.0.1), the usual block response of DNS filters. */
        SINKHOLED,
        /** No answer is a Cloudflare address, which is usually a filter's block page. */
        REDIRECTED,
        /** The host does not resolve while a control host does, so DNS is refusing the host in particular. */
        NOT_FOUND,
        /** Neither the host nor any control host resolves, so this machine has no working DNS or no internet. */
        NO_DNS
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Resolves {@code host} with the system resolver and classifies the answer. Lookups go through the JVM's address
     * cache, so a check right after a failed connection sees the same answer the connection used.
     */
    public static Result check(String host) {
        Result result = check(host, InetAddress::getAllByName);
        log.info("DNS check for {}: {} {}", host, result.getVerdict(), result.getAddresses());
        return result;
    }

    static Result check(String host, Resolver resolver) {
        InetAddress[] answers;
        try {
            answers = resolver.resolve(host);
        } catch (UnknownHostException e) {
            for (String control : CONTROL_HOSTS) {
                try {
                    resolver.resolve(control);
                    return new Result(host, Verdict.NOT_FOUND, List.of());
                } catch (UnknownHostException ignored) {
                    // try the next control host
                }
            }
            return new Result(host, Verdict.NO_DNS, List.of());
        }

        List<InetAddress> addresses = List.of(answers);
        if (addresses.isEmpty()) {
            return new Result(host, Verdict.NOT_FOUND, addresses);
        }
        if (addresses.stream().anyMatch(DnsCheck::isCloudflare)) {
            return new Result(host, Verdict.OK, addresses);
        }
        if (addresses.stream().allMatch(DnsCheck::isSinkhole)) {
            return new Result(host, Verdict.SINKHOLED, addresses);
        }
        return new Result(host, Verdict.REDIRECTED, addresses);
    }

    /**
     * True when {@code failure}, or one of its causes, is a failure to establish a connection: an unresolved host, a
     * refused, unroutable or timed out connect, or a failed TLS handshake. A connection dropped part way through a
     * download, a read timeout, and an error from a server that answered (such as a missing file) are not, since a
     * DNS block never gets that far.
     */
    public static boolean isConnectionFailure(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException
                    || t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof HttpConnectTimeoutException || t instanceof SSLHandshakeException
                    || (t instanceof SocketTimeoutException && isConnectTimeout(t))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Socket connect timeouts read "Connect timed out" and read timeouts "Read timed out"; both are
     * SocketTimeoutException.
     */
    private static boolean isConnectTimeout(Throwable timeout) {
        return String.valueOf(timeout.getMessage()).toLowerCase(Locale.ROOT).contains("connect");
    }

    static boolean isCloudflare(InetAddress address) {
        return CLOUDFLARE_RANGES.stream().anyMatch(range -> range.contains(address));
    }

    /**
     * True for 0.0.0.0/8, :: and loopback addresses, which no real server for a public host resolves to.
     */
    static boolean isSinkhole(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 0;
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Result {
        private final String host;
        private final Verdict verdict;
        private final List<InetAddress> addresses;

        /** True when the answer can only come from a DNS block, so there is no point trying to connect. */
        public boolean isCertainlyBlocked() {
            return verdict == Verdict.SINKHOLED || verdict == Verdict.NOT_FOUND;
        }

        /** True when DNS is the likely reason a connection to the host failed. */
        public boolean isBlocked() {
            return isCertainlyBlocked() || verdict == Verdict.REDIRECTED;
        }

        public String title() {
            return verdict == Verdict.NO_DNS ? "No internet connection" : "Kraken is blocked by your DNS";
        }

        /**
         * The explanation shown to the player, with explicit line breaks because option pane dialogs do not wrap.
         * @param retry What to do once it is fixed, such as "run the installer again"
         */
        public String message(String retry) {
            if (verdict == Verdict.NO_DNS) {
                return "Kraken could not look up " + host + " or any other site.\n\n"
                        + "Check that this computer is connected to the internet, then " + retry + ".";
            }
            if (verdict == Verdict.OK) {
                return "Kraken could not connect to " + host + ", but your DNS answered correctly.";
            }

            StringBuilder message = new StringBuilder("Kraken can't reach ").append(host)
                    .append(" because your DNS server is blocking it.\n\n");
            String answer = addresses.stream().map(InetAddress::getHostAddress).collect(Collectors.joining(", "));
            switch (verdict) {
                case SINKHOLED:
                    message.append("Your DNS server answered ").append(answer)
                            .append(", which is how filtering DNS services block a site.\n")
                            .append("Examples are ISP security filters, Cloudflare for Families (1.1.1.2 and\n")
                            .append("1.1.1.3), Control D, and ad blockers such as Pi-hole.\n\n");
                    break;
                case REDIRECTED:
                    String blockPage = addresses.stream().map(a -> KNOWN_BLOCK_PAGES.get(a.getHostAddress()))
                            .filter(name -> name != null).findFirst().orElse(null);
                    if (blockPage != null) {
                        message.append("Your DNS server sent it to ").append(answer).append(", the ").append(blockPage)
                                .append(" block page.\n\n");
                    } else {
                        message.append("Your DNS server answered ").append(answer).append(", which is not a Kraken\n")
                                .append("server. It is usually the block page of a web filter run by your ISP,\n")
                                .append("your DNS provider or your network.\n\n");
                    }
                    break;
                default:
                    message.append("Your DNS server did not return an address for it, although other sites\n")
                            .append("resolve. Filtering DNS services and ISPs often block a site this way.\n\n");
                    break;
            }

            String settings = Utils.IS_MAC
                    ? "System Settings > Network > your connection > Details > DNS"
                    : "Settings > Network & internet > your connection > DNS server assignment";
            return message.append("This is a block by your ISP or DNS provider, not a Kraken outage. To fix it,\n")
                    .append("do one of these, then ").append(retry).append(":\n\n")
                    .append("  1. Change your DNS server to Google (8.8.8.8 and 8.8.4.4) or Quad9 (9.9.9.9)\n")
                    .append("     in ").append(settings).append(",\n")
                    .append("     or in your router's settings.\n")
                    .append("  2. Turn off the web filter or \"safe browsing\" feature in your ISP or DNS account.")
                    .toString();
        }
    }

    /**
     * An address range in CIDR notation. Only literal addresses are parsed, so building one never queries DNS.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Cidr {
        private final byte[] network;
        private final int prefixLength;

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            try {
                byte[] network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
                return new Cidr(network, Integer.parseInt(cidr.substring(slash + 1)));
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Invalid CIDR range: " + cidr, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}

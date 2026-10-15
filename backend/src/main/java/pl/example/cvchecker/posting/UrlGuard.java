package pl.example.cvchecker.posting;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * SSRF guard for link postings (docs/API.md §4.1). It accepts only absolute {@code http}/{@code https}
 * URLs without embedded credentials, and resolves every hostname to addresses that are publicly
 * routable — blocking loopback, private, link-local, unique-local, multicast, unspecified, CGNAT and
 * reserved ranges (including the cloud-metadata address {@code 169.254.169.254}).
 *
 * <p>The resolution method is the one OkHttp uses as its {@code Dns}, so the validated address list
 * is also the one actually connected to: there is no second, unchecked lookup to rebind between
 * validation and connection. Redirects and the browser's sub-requests go through the same path, so
 * every hop is re-validated.
 */
@Component
public class UrlGuard {

    /** Longer values are rejected as URL_INVALID; the contract does not configure this bound. */
    public static final int MAX_URL_LENGTH = 2048;

    private final Function<String, InetAddress[]> resolver;

    public UrlGuard() {
        this(UrlGuard::systemResolve);
    }

    /** Test seam: supply a deterministic hostname resolver. */
    UrlGuard(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    /**
     * Validates the shape of a user-supplied link and returns its normalised {@link URI}.
     *
     * @throws PostingException {@code URL_INVALID} when the URL is not a usable absolute http(s) URL
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalid("The posting link is empty.");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw invalid("The posting link is longer than " + MAX_URL_LENGTH + " characters.");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw invalid("The posting link is not a valid URL.");
        }
        if (!uri.isAbsolute()) {
            throw invalid("The posting link must be an absolute URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("Only http:// and https:// links are supported.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("The posting link has no host.");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("The posting link must not contain credentials.");
        }
        if (uri.getPort() > 65535) {
            throw invalid("The posting link has an invalid port.");
        }
        return uri;
    }

    /**
     * Resolves and validates a hostname, returning the addresses to connect to. This is exactly what
     * OkHttp's {@code Dns} needs, so a validated lookup is never followed by an unchecked one.
     *
     * @throws PostingException  {@code URL_BLOCKED} when any resolved address is not publicly routable
     * @throws UnknownHostException when the hostname does not resolve (the caller maps this to FETCH_FAILED)
     */
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        InetAddress[] addresses = resolver.apply(hostname);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(hostname);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new PostingException(PostingException.ErrorCode.URL_BLOCKED,
                        "The posting link points to a non-public address and was blocked.");
            }
        }
        return List.of(addresses);
    }

    /** True when the address is not publicly routable (SSRF-relevant). */
    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a0 = bytes[0] & 0xff;
            int a1 = bytes[1] & 0xff;
            // 100.64.0.0/10 carrier-grade NAT (cloud metadata sits here too)
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return true;
            // 0.0.0.0/8 "this network"
            if (a0 == 0) return true;
            // 240.0.0.0/4 reserved (includes the broadcast address)
            if ((a0 & 0xf0) == 0xf0) return true;
            // 127.0.0.0/8 is covered by isLoopbackAddress, 10/8, 172.16/12, 192.168/16 by isSiteLocalAddress
        } else if (bytes.length == 16) {
            // fc00::/7 unique local addresses (Java's isSiteLocalAddress only covers fec0::/10)
            if ((bytes[0] & 0xfe) == 0xfc) return true;
        }
        return false;
    }

    private static PostingException invalid(String message) {
        return new PostingException(PostingException.ErrorCode.URL_INVALID, message);
    }

    private static InetAddress[] systemResolve(String hostname) {
        try {
            return InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}

package pl.example.cvchecker.posting;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guard for link postings (docs/API.md §4.1/§5): scheme and credential checks on the URL, and
 * address-class checks on every resolved address (with DNS-rebinding protection via a single lookup).
 */
class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard();

    @Test
    void acceptsPublicHttpAndHttpsUrls() {
        assertThat(guard.validate("https://example.com/jobs/123").getHost()).isEqualTo("example.com");
        assertThat(guard.validate("http://example.com").getScheme()).isEqualTo("http");
    }

    @Test
    void normalisesWhitespace() {
        assertThat(guard.validate("  https://example.com/job  ")).isEqualTo(URI.create("https://example.com/job"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> guard.validate("ftp://example.com/x"))
                .isInstanceOf(UrlGuardException.class)
                .extracting(e -> ((UrlGuardException) e).code())
                .isEqualTo(UrlGuardException.ErrorCode.URL_INVALID);
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd")).isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate("javascript:alert(1)")).isInstanceOf(UrlGuardException.class);
    }

    @Test
    void rejectsCredentialsRelativeAndBlankUrls() {
        assertThatThrownBy(() -> guard.validate("https://user:secret@example.com/job"))
                .isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate("/jobs/123")).isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate("")).isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate("   ")).isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate(null)).isInstanceOf(UrlGuardException.class);
    }

    @Test
    void rejectsOverlongUrlsAndMissingHost() {
        assertThatThrownBy(() -> guard.validate("https://example.com/" + "a".repeat(UrlGuard.MAX_URL_LENGTH)))
                .isInstanceOf(UrlGuardException.class);
        assertThatThrownBy(() -> guard.validate("https://")).isInstanceOf(UrlGuardException.class);
    }

    @Test
    void blocksLoopbackPrivateLinkLocalAndMetadataAddresses() throws Exception {
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("::1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("fe80::1"))).isTrue();
    }

    @Test
    void blocksUnspecifiedMulticastCgnatReservedAndUniqueLocalAddresses() throws Exception {
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("0.0.0.0"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("224.0.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("240.0.0.1"))).isTrue();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("fc00::1"))).isTrue();
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("1.1.1.1"))).isFalse();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(UrlGuard.isBlocked(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void lookupBlocksLoopbackHost() {
        assertThatThrownBy(() -> guard.lookup("localhost"))
                .isInstanceOf(UrlGuardException.class)
                .extracting(e -> ((UrlGuardException) e).code())
                .isEqualTo(UrlGuardException.ErrorCode.URL_BLOCKED);
    }

    @Test
    void lookupBlocksWhenAnyResolvedAddressIsPrivate() {
        UrlGuard rebinding = new UrlGuard(host -> new InetAddress[] { addr("8.8.8.8"), addr("127.0.0.1") });

        assertThatThrownBy(() -> rebinding.lookup("rebind.example"))
                .isInstanceOf(UrlGuardException.class)
                .extracting(e -> ((UrlGuardException) e).code())
                .isEqualTo(UrlGuardException.ErrorCode.URL_BLOCKED);
    }

    @Test
    void lookupReturnsPublicAddressesWithoutASecondLookup() throws UnknownHostException {
        UrlGuard pinned = new UrlGuard(host -> new InetAddress[] { addr("93.184.216.34") });

        assertThat(pinned.lookup("example.com")).containsExactly(addr("93.184.216.34"));
    }

    @Test
    void lookupUnknownHostThrowsUnknownHostException() {
        UrlGuard empty = new UrlGuard(host -> null);

        assertThatThrownBy(() -> empty.lookup("does-not-exist.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void lookupRejectsEmptyResolution() {
        UrlGuard empty = new UrlGuard(host -> new InetAddress[0]);

        assertThatThrownBy(() -> empty.lookup("nope.example")).isInstanceOf(UnknownHostException.class);
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}

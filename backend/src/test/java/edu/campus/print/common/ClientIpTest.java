package edu.campus.print.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Limits per address only hold if a caller cannot choose the address they are counted under. */
class ClientIpTest {

    @Test
    void aCallerStraightFromTheInternetIsTheConnectionWhateverTheHeaderSays() {
        assertThat(ClientIp.resolve("203.0.113.5", null)).isEqualTo("203.0.113.5");
        assertThat(ClientIp.resolve("203.0.113.5", "1.2.3.4")).isEqualTo("203.0.113.5");
        assertThat(ClientIp.resolve("203.0.113.5", "10.0.0.1, 8.8.8.8")).isEqualTo("203.0.113.5");
    }

    @Test
    void behindOurProxyTheAddressTheProxyAddedCountsNotWhatTheCallerWrote() {
        // The host's proxy (a private address) connects to us and added the visitor's address at the end.
        assertThat(ClientIp.resolve("10.20.0.7", "198.51.100.7")).isEqualTo("198.51.100.7");
        // A script writes its own "addresses" in front: ignored, however many.
        assertThat(ClientIp.resolve("10.20.0.7", "1.2.3.4, 198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIp.resolve("10.20.0.7", "9.9.9.9, 8.8.8.8, 198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIp.resolve("10.20.0.7", "not an address, 198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void cloudflareAndOtherProxiesOfOursAreSkipped() {
        // visitor -> Cloudflare -> the host's proxy -> us (how Render is set up)
        assertThat(ClientIp.resolve("10.20.0.7", "198.51.100.7, 172.70.44.12")).isEqualTo("198.51.100.7");
        assertThat(ClientIp.resolve("10.20.0.7", "6.6.6.6, 198.51.100.7, 162.158.90.3, 10.20.0.3")).isEqualTo("198.51.100.7");
        assertThat(ClientIp.resolve("127.0.0.1", "2001:db8::17, 2606:4700:10::6816:1")).isEqualTo("2001:db8:0:0:0:0:0:17");
        // 172.72.x is not Cloudflare (their range ends at 172.71): it is a visitor.
        assertThat(ClientIp.resolve("10.20.0.7", "198.51.100.7, 172.72.0.9")).isEqualTo("172.72.0.9");
    }

    @Test
    void portsAreDroppedAndNonsenseNeverBecomesAnAddress() {
        assertThat(ClientIp.resolve("10.20.0.7", "198.51.100.7:51234")).isEqualTo("198.51.100.7");
        assertThat(ClientIp.resolve("10.20.0.7", "[2001:db8::17]:443")).isEqualTo("2001:db8:0:0:0:0:0:17");
        // Something that is not an address at the end: nothing in the header can be believed.
        assertThat(ClientIp.resolve("10.20.0.7", "198.51.100.7, evil.example.com")).isEqualTo("10.20.0.7");
        assertThat(ClientIp.resolve("10.20.0.7", "")).isEqualTo("10.20.0.7");
        assertThat(ClientIp.resolve("10.20.0.7", "10.0.0.1, 192.168.1.1")).isEqualTo("10.20.0.7");
        assertThat(ClientIp.resolve(null, null)).isEqualTo("unknown");
    }

    @Test
    void withoutAHeaderTheConnectionIsAllThereIs() {
        assertThat(ClientIp.resolve("127.0.0.1", null)).isEqualTo("127.0.0.1");
        assertThat(ClientIp.resolve("192.168.1.20", null)).isEqualTo("192.168.1.20");
    }
}

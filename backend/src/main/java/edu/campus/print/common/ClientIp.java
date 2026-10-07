package edu.campus.print.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.net.InetAddress;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The network address a request really came from, for limits per address
 * (wrong counter passwords, wrong alert tokens, orders created).
 *
 * request.getRemoteAddr() cannot be used for that. Online, the server sits
 * behind the host's proxy, and Spring then reports the FIRST address of the
 * X-Forwarded-For header, which is whatever the caller wrote there: a script
 * could give itself a new address for every try and no limit would ever hold.
 *
 * So this reads the real connection. If the caller reached us straight from
 * the internet, that is the address, and headers prove nothing. If the
 * connection comes from a proxy next to us (a private address), the proxies
 * each added the address they saw to the END of X-Forwarded-For: walking back
 * from the end, past our own proxies and Cloudflare's, the first address left
 * is the one the outermost proxy really saw. Whatever the caller wrote in
 * front of it is ignored.
 */
public final class ClientIp {

    private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Cloudflare's published ranges (cloudflare.com/ips); Render and many other hosts sit behind it. */
    private static final List<Cidr> CLOUDFLARE = List.of(
            cidr("173.245.48.0/20"), cidr("103.21.244.0/22"), cidr("103.22.200.0/22"), cidr("103.31.4.0/22"),
            cidr("141.101.64.0/18"), cidr("108.162.192.0/18"), cidr("190.93.240.0/20"), cidr("188.114.96.0/20"),
            cidr("197.234.240.0/22"), cidr("198.41.128.0/17"), cidr("162.158.0.0/15"), cidr("104.16.0.0/13"),
            cidr("104.24.0.0/14"), cidr("172.64.0.0/13"), cidr("131.0.72.0/22"),
            cidr("2400:cb00::/32"), cidr("2606:4700::/32"), cidr("2803:f800::/32"), cidr("2405:b500::/32"),
            cidr("2405:8100::/32"), cidr("2a06:98c0::/29"), cidr("2c0f:f248::/32"));

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        HttpServletRequest raw = request;
        while (raw instanceof HttpServletRequestWrapper w && w.getRequest() instanceof HttpServletRequest inner) {
            raw = inner;
        }
        return resolve(raw.getRemoteAddr(), raw.getHeader("X-Forwarded-For"));
    }

    /** peer: who is connected to us. forwardedFor: the X-Forwarded-For header as it arrived, or null. */
    static String resolve(String peer, String forwardedFor) {
        String direct = peer == null ? "unknown" : peer;
        InetAddress p = parse(direct);
        if (p == null || !isInternal(p) || forwardedFor == null) return direct;
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = clean(hops[i]);
            InetAddress a = hop == null ? null : parse(hop);
            if (a == null) break;                         // not an address: nothing before it can be believed
            if (isInternal(a) || in(CLOUDFLARE, a)) continue;
            return a.getHostAddress();
        }
        return direct;
    }

    /** "203.0.113.7:51234" and "[2001:db8::1]:443" without the port. */
    private static String clean(String hop) {
        String h = hop.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end < 0) return null;
            h = h.substring(1, end);
        } else if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}")) {
            h = h.substring(0, h.indexOf(':'));
        }
        return LITERAL.matcher(h).matches() ? h : null;
    }

    private static InetAddress parse(String literal) {
        // Only written-out addresses, never a name: nothing a caller sends may start a DNS lookup.
        if (literal == null || !LITERAL.matcher(literal).matches()) return null;
        if (!IPV4.matcher(literal).matches() && literal.indexOf(':') < 0) return null;
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            return null;
        }
    }

    /** This computer, a private network, or a carrier's internal range: a proxy of ours, never a visitor. */
    private static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;         // 100.64.0.0/10
        return (b[0] & 0xFE) == 0xFC;                                                  // fc00::/7
    }

    private record Cidr(byte[] net, int bits) {}

    private static Cidr cidr(String text) {
        try {
            int slash = text.indexOf('/');
            return new Cidr(InetAddress.getByName(text.substring(0, slash)).getAddress(),
                    Integer.parseInt(text.substring(slash + 1)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean in(List<Cidr> ranges, InetAddress a) {
        byte[] b = a.getAddress();
        for (Cidr c : ranges) {
            if (c.net.length != b.length) continue;
            int full = c.bits / 8, rest = c.bits % 8;
            boolean same = true;
            for (int i = 0; i < full && same; i++) same = b[i] == c.net[i];
            if (same && rest > 0) {
                int mask = 0xFF << (8 - rest);
                same = (b[full] & mask) == (c.net[full] & mask);
            }
            if (same) return true;
        }
        return false;
    }
}

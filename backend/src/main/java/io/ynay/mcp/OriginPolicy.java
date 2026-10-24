package io.ynay.mcp;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * DNS-rebinding protection for {@code /mcp}: decides whether a request's {@code Origin} header
 * is acceptable. Allowed are
 * <ul>
 *   <li>no {@code Origin} at all (native MCP clients, server-to-server calls),</li>
 *   <li>the origin of the configured public URL ({@code YNAY_PUBLIC_URL}),</li>
 *   <li>explicitly configured extra origins ({@code YNAY_MCP_ALLOWED_ORIGINS}, comma separated),</li>
 *   <li>same-origin requests to a loopback host (local development).</li>
 * </ul>
 * Same-origin is <em>not</em> accepted for arbitrary hosts: in a DNS rebinding attack the
 * attacker's page is same-origin with the forged {@code Host}, so that check alone protects nothing.
 */
public final class OriginPolicy {

    private final Set<String> allowed = new LinkedHashSet<>();

    public OriginPolicy(String publicUrl, String extraOrigins) {
        String publicOrigin = normalize(publicUrl);
        if (publicOrigin != null) {
            allowed.add(publicOrigin);
        }
        if (extraOrigins != null) {
            Arrays.stream(extraOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(OriginPolicy::normalize)
                    .filter(o -> o != null)
                    .forEach(allowed::add);
        }
    }

    /**
     * @param origin        the {@code Origin} request header, may be {@code null}
     * @param requestOrigin origin the request was addressed to ({@code scheme://host:port})
     */
    public boolean isAllowed(String origin, String requestOrigin) {
        if (origin == null) {
            return true;
        }
        String normalized = normalize(origin);
        if (normalized == null) {
            return false; // includes the opaque origin "null"
        }
        if (allowed.contains(normalized)) {
            return true;
        }
        String self = normalize(requestOrigin);
        return normalized.equals(self) && isLoopback(URI.create(self).getHost());
    }

    Set<String> allowedOrigins() {
        return Set.copyOf(allowed);
    }

    /** {@code scheme://host[:port]} in lower case without default port, or {@code null} if unparsable. */
    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("[::1]");
    }
}

package edu.campus.print.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase settings. The service key is read from the environment only; it must
 * never appear in a properties file that is committed, shipped to a device, or
 * served to a browser.
 */
@ConfigurationProperties(prefix = "campus.supabase")
public record SupabaseProperties(
        String url,
        String serviceKey,
        String bucket,
        int uploadUrlTtlSeconds,
        int downloadUrlTtlSeconds
) {
}

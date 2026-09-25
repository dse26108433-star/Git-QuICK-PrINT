package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "campus.agent")
public record AgentProperties(
        String tokenSecret,
        long tokenTtlSeconds,
        int leaseSeconds,
        int offlineAfterSeconds
) {
    public Duration offlineAfter() {
        return Duration.ofSeconds(offlineAfterSeconds);
    }
}

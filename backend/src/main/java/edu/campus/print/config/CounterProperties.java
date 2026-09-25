package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Password for the Xerox center staff screen (counter.html). */
@ConfigurationProperties(prefix = "campus.counter")
public record CounterProperties(String password) {

    public boolean isConfigured() {
        return password != null && password.trim().length() >= 8;
    }
}

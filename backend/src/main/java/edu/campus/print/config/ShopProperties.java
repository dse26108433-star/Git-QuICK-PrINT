package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for what a student may send. Prices live in the database (counter screen). */
@ConfigurationProperties(prefix = "campus.shop")
public record ShopProperties(
        long maxFileSizeBytes,
        int maxPages,          // most pages printed per copy
        int maxFilePages,      // most pages a PDF may have (the student then chooses pages)
        int maxCopies
) {
}

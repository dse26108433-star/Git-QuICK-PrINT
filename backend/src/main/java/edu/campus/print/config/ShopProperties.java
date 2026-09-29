package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for what a student may send. Prices live in the database (counter screen). */
@ConfigurationProperties(prefix = "campus.shop")
public record ShopProperties(
        long maxFileSizeBytes,
        int maxPages,          // most pages printed per copy of one document
        int maxFilePages,      // most pages a PDF may have (the student then chooses pages)
        int maxCopies,
        int maxDocuments,      // most documents in one order
        int maxParallelChecks  // uploaded files checked at the same time (each is read into memory)
) {
    public ShopProperties {
        if (maxDocuments <= 0) maxDocuments = 25;
        if (maxParallelChecks <= 0) maxParallelChecks = 3;
    }
}

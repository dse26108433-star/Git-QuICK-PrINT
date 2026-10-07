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
        int maxParallelChecks, // uploaded files checked at the same time (each is read into memory)
        int checkMemoryMb,     // megabytes of files being checked at the same time, all together
        long shopCacheMillis   // how long the shop's details are kept before they are read again (0: never kept)
) {
    public ShopProperties {
        if (maxDocuments <= 0) maxDocuments = 25;
        if (maxParallelChecks <= 0) maxParallelChecks = 4;
        if (checkMemoryMb <= 0) checkMemoryMb = 64;
        if (shopCacheMillis < 0) shopCacheMillis = 0;
    }
}

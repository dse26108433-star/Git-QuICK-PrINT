package edu.campus.print.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the files are kept (see FileStorage).
 *
 * mode             "supabase" (a cloud server) or "local" (a server on the Xerox center's PC)
 * dir              local: the folder the files are kept in
 * publicUrl        local: the server's address as phones reach it, e.g. https://print.mycollege.in.
 *                  Normally empty: the address each request came in on is used.
 * chunkBytes       local: phones send a file in pieces of this size, so a dropped connection loses one piece
 * uploadTtlSeconds local: how long an upload link works (a big file on a slow connection takes a while)
 * minFreeMb        local: uploads are refused while the disk has less free space than this
 * maxUploads       local: pieces being received at the same moment; more are told to try again in a moment
 */
@ConfigurationProperties(prefix = "campus.storage")
public record StorageProperties(
        String mode,
        String dir,
        String publicUrl,
        Integer chunkBytes,
        Integer uploadTtlSeconds,
        Integer minFreeMb,
        Integer maxUploads
) {
    public boolean local() {
        return "local".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public int chunkBytesOr(int fallback) {
        return chunkBytes == null || chunkBytes <= 0 ? fallback : chunkBytes;
    }

    public int uploadTtlSecondsOr(int fallback) {
        return uploadTtlSeconds == null || uploadTtlSeconds <= 0 ? fallback : uploadTtlSeconds;
    }

    public int minFreeMbOr(int fallback) {
        return minFreeMb == null || minFreeMb < 0 ? fallback : minFreeMb;
    }

    public int maxUploadsOr(int fallback) {
        return maxUploads == null || maxUploads <= 0 ? fallback : maxUploads;
    }
}

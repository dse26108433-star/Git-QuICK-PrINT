package edu.campus.print.storage;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where the students' files are kept until they are printed.
 *
 * Two kinds, chosen with STORAGE_MODE:
 *
 *   supabase  (the default) a private Supabase Storage bucket: for a server
 *             that runs in the cloud. See SupabaseStorage.
 *   local     a folder on the server's own disk: for a server that runs on
 *             the Xerox center's PC, next to the printers. Files then travel
 *             once (phone -> PC) instead of three times (phone -> storage ->
 *             server -> PC), and no cloud storage limit applies. See
 *             LocalFileStorage.
 *
 * Either way nothing is public: a phone gets a short-lived link for one file
 * to send, and the Xerox PC a short-lived link for one file to fetch.
 */
public interface FileStorage {

    record SignedUpload(String url, String token, String path) {
    }

    /** What the server learned by reading the start of an uploaded object. */
    record Probe(long totalSize, byte[] prefix) {
        public boolean isComplete() {
            return prefix.length >= totalSize;
        }
    }

    /** An upload link for exactly this object path. A failed or cancelled upload can be tried again on it. */
    SignedUpload createSignedUpload(String objectPath);

    /** A short-lived read link: for the Xerox PC holding the document, or for the student's own device. */
    String createSignedDownload(String objectPath);

    /**
     * Reads the first bytes of an uploaded object and learns its full size.
     * Empty: the object does not exist (the upload never arrived, or is not complete).
     */
    Optional<Probe> probe(String objectPath, int maxBytes);

    /** Removes the object (and anything half-uploaded for it). Never fails loudly. */
    void delete(String objectPath);

    /**
     * How a device should send a file to the link from createSignedUpload:
     * 0 = the whole file in one PUT; otherwise in pieces of this many bytes
     * (PUT with Content-Range), so a connection that drops loses one piece
     * and the upload carries on from there.
     */
    default int uploadChunkBytes() {
        return 0;
    }

    /**
     * Clean-up of files nobody needs any more that were not deleted the usual
     * way (a crash at the wrong moment). stillNeeded is asked with an order's
     * id; files of orders it answers false for are removed. Returns how many
     * files went.
     */
    default int removeAbandoned(Predicate<String> stillNeeded) {
        return 0;
    }
}

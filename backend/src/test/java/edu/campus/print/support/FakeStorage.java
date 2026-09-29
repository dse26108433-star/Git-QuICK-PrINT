package edu.campus.print.support;

import edu.campus.print.storage.SupabaseProperties;
import edu.campus.print.storage.SupabaseStorage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Storage in memory: an "upload" is put(); nothing goes over the network. */
public class FakeStorage extends SupabaseStorage {

    public static final String UPLOAD = "https://storage.test/upload/";
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    public FakeStorage() {
        super(new SupabaseProperties("https://storage.test", "test-key", "print-documents", 300, 300));
    }

    /** What the student's browser does with the upload link. */
    public void upload(String uploadUrl, byte[] bytes) {
        objects.put(uploadUrl.substring(UPLOAD.length()), bytes);
    }

    public boolean has(String path) {
        return objects.containsKey(path);
    }

    public int count() {
        return objects.size();
    }

    @Override
    public SignedUpload createSignedUpload(String objectPath) {
        return new SignedUpload(UPLOAD + objectPath, "token", objectPath);
    }

    @Override
    public String createSignedDownload(String objectPath) {
        return "https://storage.test/download/" + objectPath;
    }

    @Override
    public Optional<Probe> probe(String objectPath, int maxBytes) {
        byte[] b = objects.get(objectPath);
        if (b == null) return Optional.empty();
        byte[] cut = b.length > maxBytes ? java.util.Arrays.copyOf(b, maxBytes) : b;
        return Optional.of(new Probe(b.length, cut));
    }

    @Override
    public void delete(String objectPath) {
        objects.remove(objectPath);
    }
}

package edu.campus.print.domain;

/**
 * The kinds of file a student can add. PDF, PNG and JPEG are printed as they
 * are. A Word file (DOCX) is first turned into a PDF by the Xerox center's
 * computer (see orders/WordFiles): it is only ever DOCX while that happens.
 */
public enum FileType {
    PDF("application/pdf", ".pdf"),
    PNG("image/png", ".png"),
    JPEG("image/jpeg", ".jpg"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx");

    private final String mimeType;
    private final String extension;

    FileType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() { return mimeType; }
    public String extension() { return extension; }

    /** Works out the real type from the first bytes, whatever the file is called. */
    public static FileType detect(byte[] head) {
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return PNG;
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        // A .docx is a ZIP file. Whether this ZIP really is a Word document is found out by looking inside
        // (storage/DocxInspector), which needs the whole file.
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return DOCX;
        }
        // PDF readers accept up to 1 KB of junk before the header; so do we.
        int limit = Math.min(head.length - 5, 1024);
        for (int i = 0; i <= limit; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F' && head[i + 4] == '-') {
                return PDF;
            }
        }
        return null;
    }
}

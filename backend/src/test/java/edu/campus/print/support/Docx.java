package edu.campus.print.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds small Word files (.docx) for tests: a plain one, and plain ones with
 * exactly one thing added (a macro part, a linked picture, a field...).
 */
public final class Docx {

    public static final String MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private final Map<String, byte[]> parts = new LinkedHashMap<>();
    private final Map<String, Integer> blanks = new LinkedHashMap<>();
    private String mainType = MAIN;
    private String comment;

    public Docx() {
        part("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + rel("rId1", "officeDocument", "word/document.xml", false) + "</Relationships>");
        body("<w:p><w:r><w:t>Hello from a test.</w:t></w:r></w:p>");
    }

    public static Docx plain() {
        return new Docx();
    }

    /** One paragraph per page, with a page break between them: Word makes this many pages of it. */
    public static Docx pages(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append("<w:p><w:r><w:t>Page ").append(i).append(" of a test document.</w:t></w:r>");
            if (i < n) b.append("<w:r><w:br w:type=\"page\"/></w:r>");
            b.append("</w:p>");
        }
        return new Docx().body(b.toString());
    }

    public Docx body(String inner) {
        return part("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>" + inner
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>");
    }

    public Docx part(String name, String content) {
        return part(name, content.getBytes(StandardCharsets.UTF_8));
    }

    public Docx part(String name, byte[] content) {
        parts.put(name, content);
        return this;
    }

    /** A part of this many megabytes of spaces (it packs to almost nothing): never held in memory here. */
    public Docx blank(String name, int megabytes) {
        blanks.put(name, megabytes);
        return this;
    }

    /** The links of the document itself (word/_rels/document.xml.rels). */
    public Docx links(String... relationships) {
        return part("word/_rels/document.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + String.join("", relationships) + "</Relationships>");
    }

    public Docx mainType(String contentType) {
        this.mainType = contentType;
        return this;
    }

    public Docx zipComment(String c) {
        this.comment = c;
        return this;
    }

    public static String rel(String id, String kind, String target, boolean external) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + REL + kind + "\" Target=\"" + target + "\""
                + (external ? " TargetMode=\"External\"" : "") + "/>";
    }

    /** A field as Word writes it: begin, the instructions (in as many pieces as given), separate, a result, end. */
    public static String field(String... instructions) {
        StringBuilder b = new StringBuilder("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>");
        for (String i : instructions) b.append(i.startsWith("<") ? i : "<w:r><w:instrText xml:space=\"preserve\">" + i + "</w:instrText></w:r>");
        return b.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r>")
                .append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>").toString();
    }

    /** A field inside another field's instructions (no paragraph around it). */
    public static String inner(String instructions, String result) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">" + instructions
                + "</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>" + result
                + "</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    public byte[] bytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                if (comment != null) zip.setComment(comment);
                put(zip, "[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"" + mainType + "\"/></Types>")
                        .getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> p : parts.entrySet()) put(zip, p.getKey(), p.getValue());
                byte[] megabyte = new byte[1024 * 1024];
                java.util.Arrays.fill(megabyte, (byte) ' ');
                for (Map.Entry<String, Integer> b : blanks.entrySet()) {
                    zip.putNextEntry(new ZipEntry(b.getKey()));
                    for (int i = 0; i < b.getValue(); i++) zip.write(megabyte);
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}

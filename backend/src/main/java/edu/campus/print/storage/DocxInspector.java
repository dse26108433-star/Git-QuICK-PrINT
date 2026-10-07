package edu.campus.print.storage;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Looks inside a Word file (.docx) BEFORE the Xerox center's computer opens
 * it in Microsoft Word to turn it into pages.
 *
 * Anyone on the internet can send a file, and Word on the Xerox PC opens it
 * with nobody watching. So only a plain document gets that far: text, tables,
 * pictures, charts, headers, page numbers, a table of contents. A file is
 * refused (with the advice to save it as PDF) when it carries anything that
 * can run, fetch or prompt:
 *
 *   - macros, ActiveX controls, toolbar and keyboard customisations
 *   - embedded objects of the old kind (OLE: the equation editor, "packages")
 *   - pieces of other documents merged in when it opens (altChunk, subdocuments, frames)
 *   - anything loaded from outside the file: linked pictures, a template on a
 *     server, a mail-merge data source (plain hyperlinks are fine: nothing
 *     follows them)
 *   - fields that read other files or run commands (DDE, INCLUDETEXT...), and
 *     fields whose name is put together by another field
 *   - embedded fonts, EPS pictures, web add-ins
 *
 * The file is read strictly, the way Word reads it: through the directory at
 * the end of the ZIP, with every part's size and checksum checked, no part
 * twice, nothing encrypted. A file that could be read in two ways is refused.
 *
 * Old Word files (.doc) cannot be looked into like this, so they are not
 * accepted at all: the student is told to save as .docx or PDF.
 */
public final class DocxInspector {

    /** code null: fine. Otherwise why the file is refused, in words for the student. */
    public record Verdict(String code, String message) {
        public boolean ok() {
            return code == null;
        }
    }

    public static final String ACTIVE = "WORD_ACTIVE_CONTENT";
    public static final String NOT_WORD = "NOT_SUPPORTED";
    public static final String DAMAGED = "UNREADABLE";
    public static final String TOO_LARGE = "WORD_TOO_LARGE";
    public static final String PASSWORD = "PASSWORD_PROTECTED";
    public static final String OLD_WORD = "OLD_WORD_FILE";

    /** What every refusal ends with: the way that always works. */
    public static final String SAVE_AS_PDF = " In Word, choose File \u2192 Save As \u2192 PDF, and add the PDF.";
    public static final String KINDS = "Only PDF, Word (.docx), PNG and JPG files can be printed.";

    private static final Verdict FINE = new Verdict(null, null);
    private static final Verdict IS_DAMAGED = new Verdict(DAMAGED,
            "This Word file cannot be read as it is. Open it in Word and save it again, or save it as PDF, then add that.");
    private static final Verdict HAS_ACTIVE = new Verdict(ACTIVE,
            "This Word file has something in it that cannot be prepared automatically (for example an embedded object, "
                    + "a link to another file, or a macro)." + SAVE_AS_PDF);
    private static final Verdict IS_TOO_LARGE = new Verdict(TOO_LARGE,
            "This Word file is too large to prepare." + SAVE_AS_PDF);
    private static final Verdict IS_LOCKED = new Verdict(PASSWORD,
            "This file is locked with a password. Save a copy without the password and try again.");
    private static final Verdict OTHER_FILE = new Verdict(NOT_WORD, KINDS);
    private static final Verdict OTHER_OFFICE = new Verdict(NOT_WORD, KINDS + " Save this file as PDF and add that.");

    private static final int MAX_PARTS = 4000;
    private static final long MAX_UNPACKED = 200L * 1024 * 1024;       // all parts together
    private static final int MAX_XML_PART = 32 * 1024 * 1024;          // one XML part, unpacked (held in memory)

    private static final String MAIN_DOCUMENT =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    /** Kinds of link between parts (the end of the relationship type) that a plain document does not have. */
    private static final Set<String> FORBIDDEN_LINKS = Set.of(
            "oleobject", "control", "vbaproject", "wordvbadata", "afchunk", "subdocument", "frame",
            "mailmergesource", "mailmergeheadersource", "recipientdata", "font", "keymapcustomizations",
            "attachedtoolbars", "webextension", "webextensiontaskpanes", "externallink", "externallinkpath");

    /** What an embedded "package" may be: a chart's own table, or another document shown as an icon. */
    private static final Set<String> PACKAGES = Set.of("xlsx", "docx", "pptx", "sldx");

    /** The pictures Word shows without help from anything else. */
    private static final Set<String> PICTURES = Set.of("png", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp", "dib",
            "tif", "tiff", "emf", "wmf", "emz", "wmz", "svg", "ico", "wdp");

    /** Fields that read other files, run something, or stop and ask. */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("DDE", "DDEAUTO", "INCLUDETEXT", "INCLUDEPICTURE",
            "INCLUDE", "IMPORT", "LINK", "RD", "DATABASE", "ASK", "FILLIN", "EMBED", "CONTROL", "PRINT");

    /** Stands for "another field is here" inside a field's instructions. */
    private static final char NESTED = '\uFFFF';

    private DocxInspector() {
    }

    /** True for the first bytes of any ZIP file (which is what a .docx is). */
    public static boolean looksLikeZip(byte[] b) {
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    /** True for the first bytes of an old Office file (.doc, .xls, .ppt) or a password-protected new one. */
    public static boolean looksLikeOldOffice(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11
                && (b[3] & 0xFF) == 0xE0 && (b[4] & 0xFF) == 0xA1 && (b[5] & 0xFF) == 0xB1
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0xE1;
    }

    /** What to tell the student about an old Office file: it is locked with a password, or it is an old .doc. */
    public static Verdict oldOffice(byte[] b) {
        if (contains(b, "EncryptedPackage".getBytes(StandardCharsets.UTF_16LE))) return IS_LOCKED;
        if (contains(b, "WordDocument".getBytes(StandardCharsets.UTF_16LE))) {
            return new Verdict(OLD_WORD, "This is an older kind of Word file (.doc). Open it in Word, choose File \u2192 Save As "
                    + "\u2192 Word Document (.docx) or PDF, and add that.");
        }
        return OTHER_OFFICE;
    }

    /** Checks a file that starts like a ZIP. */
    public static Verdict inspect(byte[] bytes) {
        try {
            check(bytes);
            return FINE;
        } catch (Refused r) {
            return r.verdict;
        } catch (RuntimeException | XMLStreamException e) {
            return IS_DAMAGED;                 // anything unexpected: not a file Word should be given
        }
    }

    // ------------------------------------------------------------------ the package

    private static void check(byte[] bytes) throws XMLStreamException {
        Zip zip = new Zip(bytes);
        Zip.Part types = zip.part("[content_types].xml");
        Zip.Part rootLinks = zip.part("_rels/.rels");
        if (types == null || rootLinks == null) throw new Refused(OTHER_FILE);     // a ZIP, but no Office file

        // which part is the document, and is it a plain Word document?
        String main = null;
        for (Link l : links(zip.bytes(rootLinks), "")) {
            if (l.kind.equals("officedocument") && !l.external) main = l.target;
        }
        if (main == null || zip.part(main) == null) throw new Refused(IS_DAMAGED);
        String type = contentType(zip.bytes(types), main);
        if (type == null) throw new Refused(IS_DAMAGED);
        if (!type.equalsIgnoreCase(MAIN_DOCUMENT)) {
            String t = type.toLowerCase(Locale.ROOT);
            if (t.contains("macroenabled")) throw new Refused(HAS_ACTIVE);
            if (t.contains("wordprocessingml") || t.contains("ms-word")) {
                throw new Refused(new Verdict(NOT_WORD, "This is a Word template, not a document. Open it in Word, save it as a "
                        + "Word Document (.docx) or PDF, and add that."));
            }
            throw new Refused(OTHER_OFFICE);                   // Excel, PowerPoint...
        }

        for (Zip.Part p : zip.parts) {
            String name = p.name;
            String ext = extension(name);
            // parts that only exist for things a plain document does not have
            if (name.contains("vbaproject") || name.contains("/activex/") || name.startsWith("word/fonts/")
                    || (name.contains("/embeddings/") && !PACKAGES.contains(ext))
                    || (name.contains("/media/") && !PICTURES.contains(ext))) {
                throw new Refused(HAS_ACTIVE);
            }
            if (ext.equals("rels")) {
                for (Link l : links(zip.bytes(p), folderOf(name))) checkLink(l);
            } else if (ext.equals("xml")) {
                checkFields(zip.bytes(p));
            } else {
                zip.verify(p);                                 // pictures and the like: only that they are whole
            }
        }
    }

    private static void checkLink(Link l) {
        if (FORBIDDEN_LINKS.contains(l.kind)) throw new Refused(HAS_ACTIVE);
        if (l.external) {
            if (l.kind.equals("hyperlink")) return;                       // nothing follows a hyperlink by itself
            // The template the document was made from: Word looks for it when it opens the file. One on the
            // writer's own disk is simply not found on the Xerox PC; one on a server would be fetched.
            if (l.kind.equals("attachedtemplate") && localFile(l.rawTarget)) return;
            throw new Refused(HAS_ACTIVE);
        }
        if (l.kind.equals("package") && !PACKAGES.contains(extension(l.target))) throw new Refused(HAS_ACTIVE);
        // a picture is a picture wherever in the file it lies
        if ((l.kind.equals("image") || l.kind.equals("hdphoto")) && !PICTURES.contains(extension(l.target))) {
            throw new Refused(HAS_ACTIVE);
        }
    }

    /**
     * A file on the computer's own disk, never one on a server:
     * "file:///C:\Users\x\Templates\Report.dotx", "C:\...", "file:///Users/x/..." (a Mac) or just a file name.
     */
    static boolean localFile(String target) {
        String t = unescape(target.trim());
        String lower = t.toLowerCase(Locale.ROOT);
        String rest = lower.startsWith("file:///") ? t.substring(8)
                : lower.startsWith("file://localhost/") ? t.substring(17) : null;
        if (rest != null) {
            // "file:////server/share" and "file:///\\server\share" are servers
            return !rest.isEmpty() && rest.charAt(0) != '/' && rest.charAt(0) != '\\';
        }
        if (t.matches("[A-Za-z]:[\\\\/][^\\\\/].*")) return true;         // a drive letter, then a folder or file
        return t.matches("[^\\\\/:?#]+");                                 // a bare file name
    }

    /** "%5C" is "\": written out, so a server's name cannot hide behind it. */
    private static String unescape(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                out.append((char) (Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16)));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ fields

    /**
     * Every field of one part: { NAME instructions }. The name is the first
     * word of the instructions. A field inside the instructions of another is
     * worked out first by Word, so a name could be put together from pieces;
     * a field whose name is not written out plainly is refused.
     */
    private static void checkFields(byte[] xml) throws XMLStreamException {
        XMLStreamReader r = reader(xml);
        Deque<StringBuilder> open = new ArrayDeque<>();     // the instructions of each field we are inside, innermost first
        Deque<Boolean> writing = new ArrayDeque<>();        // ...and whether its instructions are still being written
        boolean inInstr = false;
        try {
            while (r.hasNext()) {
                int e = step(r);
                if (e == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("fldChar")) {
                        String kind = attribute(r, "fldCharType");
                        if ("begin".equals(kind)) {
                            if (!open.isEmpty() && Boolean.TRUE.equals(writing.peek())) open.peek().append(NESTED);
                            open.push(new StringBuilder());
                            writing.push(Boolean.TRUE);
                        } else if ("separate".equals(kind) && !open.isEmpty()) {
                            if (Boolean.TRUE.equals(writing.peek())) checkField(open.peek().toString());
                            writing.pop();
                            writing.push(Boolean.FALSE);
                        } else if ("end".equals(kind) && !open.isEmpty()) {
                            if (Boolean.TRUE.equals(writing.peek())) checkField(open.peek().toString());
                            open.pop();
                            writing.pop();
                        }
                    } else if (n.equals("fldSimple")) {
                        String instr = attribute(r, "instr");
                        if (instr != null) checkField(instr);
                    } else if (n.equals("instrText")) {
                        inInstr = true;
                    }
                } else if (e == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("instrText")) inInstr = false;
                } else if ((e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) && inInstr) {
                    if (!open.isEmpty() && Boolean.TRUE.equals(writing.peek())) open.peek().append(r.getText());
                    else checkField(r.getText());              // instructions outside a field: judged as one
                }
            }
        } finally {
            r.close();
        }
    }

    static void checkField(String instructions) {
        int i = 0;
        int n = instructions.length();
        while (i < n && blank(instructions.charAt(i))) i++;
        int start = i;
        while (i < n && !blank(instructions.charAt(i)) && instructions.charAt(i) != '\\' && instructions.charAt(i) != '"') i++;
        String name = instructions.substring(start, i);
        // the name is made by another field (in part or whole): nobody can say here what it will be
        if (name.indexOf(NESTED) >= 0) throw new Refused(HAS_ACTIVE);
        if (FORBIDDEN_FIELDS.contains(name.toUpperCase(Locale.ROOT))) throw new Refused(HAS_ACTIVE);
    }

    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c < 0x20;
    }

    // ------------------------------------------------------------------ small XML readers

    /** A link from one part to another part, or to something outside the file. */
    private record Link(String kind, String target, String rawTarget, boolean external) {}

    /** The links listed in one .rels part. folder: where the part they belong to lives ("word/"). */
    private static List<Link> links(byte[] rels, String folder) throws XMLStreamException {
        List<Link> out = new ArrayList<>();
        XMLStreamReader r = reader(rels);
        try {
            while (r.hasNext()) {
                if (step(r) != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Relationship")) continue;
                String type = attribute(r, "Type");
                String target = attribute(r, "Target");
                String mode = attribute(r, "TargetMode");
                if (type == null || target == null) throw new Refused(IS_DAMAGED);
                String kind = type.substring(type.lastIndexOf('/') + 1).trim().toLowerCase(Locale.ROOT);
                boolean external = mode != null && mode.trim().equalsIgnoreCase("External");
                out.add(new Link(kind, external ? target : resolve(folder, target), target, external));
            }
        } finally {
            r.close();
        }
        return out;
    }

    /** "word/_rels/document.xml.rels" belongs to a part in "word/". */
    private static String folderOf(String relsName) {
        int i = relsName.lastIndexOf("_rels/");
        return i <= 0 ? "" : relsName.substring(0, i);
    }

    /** A part's name from where a link points: "media/image1.png" seen from "word/" is "word/media/image1.png". */
    private static String resolve(String folder, String target) {
        String t = target;
        int cut = t.indexOf('#');
        if (cut >= 0) t = t.substring(0, cut);
        t = t.replace('\\', '/');
        Deque<String> path = new ArrayDeque<>();
        if (!t.startsWith("/")) for (String s : folder.split("/")) if (!s.isEmpty()) path.addLast(s);
        for (String s : t.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (path.isEmpty()) throw new Refused(IS_DAMAGED);
                path.removeLast();
            } else {
                path.addLast(s);
            }
        }
        return String.join("/", path).toLowerCase(Locale.ROOT);
    }

    /** What kind of part this is, from [Content_Types].xml: its own entry, or the one for its file ending. */
    private static String contentType(byte[] types, String part) throws XMLStreamException {
        String byName = null;
        String byEnding = null;
        String ending = extension(part);
        XMLStreamReader r = reader(types);
        try {
            while (r.hasNext()) {
                if (step(r) != XMLStreamConstants.START_ELEMENT) continue;
                if (r.getLocalName().equals("Override")) {
                    String name = attribute(r, "PartName");
                    if (name != null && name.replaceFirst("^/", "").equalsIgnoreCase(part)) byName = attribute(r, "ContentType");
                } else if (r.getLocalName().equals("Default")) {
                    String ext = attribute(r, "Extension");
                    if (ext != null && ext.equalsIgnoreCase(ending)) byEnding = attribute(r, "ContentType");
                }
            }
        } finally {
            r.close();
        }
        return byName != null ? byName : byEnding;
    }

    private static String attribute(XMLStreamReader r, String localName) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(localName)) return r.getAttributeValue(i);
        }
        return null;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // nothing from outside the file, and no entity tricks
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /** The next piece of the XML. A document type declaration or a home-made entity has no place in a Word file. */
    private static int step(XMLStreamReader r) throws XMLStreamException {
        int e = r.next();
        if (e == XMLStreamConstants.DTD || e == XMLStreamConstants.ENTITY_REFERENCE
                || e == XMLStreamConstants.ENTITY_DECLARATION || e == XMLStreamConstants.NOTATION_DECLARATION) {
            throw new Refused(IS_DAMAGED);
        }
        return e;
    }

    private static String extension(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static final class Refused extends RuntimeException {
        final Verdict verdict;

        Refused(Verdict v) {
            super(v.code(), null, false, false);
            this.verdict = v;
        }
    }

    // ------------------------------------------------------------------ the ZIP, read strictly

    /**
     * Reads a ZIP the one way that leaves no doubt: the directory is the last
     * thing in the file, every name is plain and appears once, nothing is
     * encrypted, each part's header agrees with the directory, and each part
     * unpacks to exactly the size and checksum the directory says. (A file
     * made to look different to two programs fails one of these.) Parts are
     * unpacked one at a time, so a small file that unpacks to a huge one
     * cannot fill the memory.
     */
    private static final class Zip {

        static final class Part {
            final String name;         // lower-case
            final int method;
            final long crc;
            final int data;            // where the packed bytes start
            final int packed;
            final int size;

            Part(String name, int method, long crc, int data, int packed, int size) {
                this.name = name;
                this.method = method;
                this.crc = crc;
                this.data = data;
                this.packed = packed;
                this.size = size;
            }
        }

        final byte[] b;
        final List<Part> parts = new ArrayList<>();

        Zip(byte[] bytes) {
            this.b = bytes;
            int end = b.length - 22;                         // the end record, with no comment after it
            if (end < 0 || u32(end) != 0x06054b50L || u16(end + 20) != 0) throw new Refused(IS_DAMAGED);
            if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)) throw new Refused(IS_DAMAGED);
            long count = u16(end + 10);
            long dirSize = u32(end + 12);
            long dirAt = u32(end + 16);
            long dirEnd = end;
            // The end record in its "huge file" form, which some programs write for small files too:
            // it must sit right before the short one and say the same.
            if (end >= 76 && u32(end - 20) == 0x07064b50L) {
                long rec = u64(end - 12);
                if (rec != end - 76 || u32((int) rec) != 0x06064b50L || u64((int) rec + 4) != 44
                        || u64((int) rec + 24) != u64((int) rec + 32)) {
                    throw new Refused(IS_DAMAGED);
                }
                long count64 = u64((int) rec + 32);
                long size64 = u64((int) rec + 40);
                long at64 = u64((int) rec + 48);
                if ((count != 0xFFFF && count != count64) || (dirSize != 0xFFFFFFFFL && dirSize != size64)
                        || (dirAt != 0xFFFFFFFFL && dirAt != at64)) {
                    throw new Refused(IS_DAMAGED);
                }
                count = count64;
                dirSize = size64;
                dirAt = at64;
                dirEnd = rec;
            } else if (count == 0xFFFF || dirSize == 0xFFFFFFFFL || dirAt == 0xFFFFFFFFL) {
                throw new Refused(IS_DAMAGED);
            }
            if (dirAt + dirSize != dirEnd) throw new Refused(IS_DAMAGED);
            if (count > MAX_PARTS) throw new Refused(IS_TOO_LARGE);

            Set<String> seen = new HashSet<>();
            long total = 0;
            int p = (int) dirAt;
            for (int i = 0; i < count; i++) {
                if (p + 46 > dirEnd || u32(p) != 0x02014b50L) throw new Refused(IS_DAMAGED);
                int flags = u16(p + 8);
                int method = u16(p + 10);
                long crc = u32(p + 16);
                long packed = u32(p + 20);
                long size = u32(p + 24);
                int nameLen = u16(p + 28);
                int extraLen = u16(p + 30);
                int commentLen = u16(p + 32);
                long at = u32(p + 42);
                if ((long) p + 46 + nameLen + extraLen + commentLen > dirEnd) throw new Refused(IS_DAMAGED);
                String name = name(p + 46, nameLen);
                if (size == 0xFFFFFFFFL || packed == 0xFFFFFFFFL || at == 0xFFFFFFFFL) {
                    // the real numbers are in the "huge file" note of this entry, in this order
                    int x = p + 46 + nameLen;
                    int xEnd = x + extraLen;
                    boolean found = false;
                    while (x + 4 <= xEnd && !found) {
                        int id = u16(x);
                        int len = u16(x + 2);
                        int v = x + 4;
                        if (v + len > xEnd) throw new Refused(IS_DAMAGED);
                        if (id == 0x0001) {
                            int q = v;
                            if (size == 0xFFFFFFFFL) { size = u64(q); q += 8; }
                            if (packed == 0xFFFFFFFFL) { packed = u64(q); q += 8; }
                            if (at == 0xFFFFFFFFL) { at = u64(q); q += 8; }
                            if (q > v + len) throw new Refused(IS_DAMAGED);
                            found = true;
                        }
                        x = v + len;
                    }
                    if (!found) throw new Refused(IS_DAMAGED);
                }
                p += 46 + nameLen + extraLen + commentLen;

                if ((flags & 0x41) != 0) throw new Refused(IS_LOCKED);       // encrypted
                if (method != 0 && method != 8) throw new Refused(IS_DAMAGED);
                if (!seen.add(name)) throw new Refused(IS_DAMAGED);          // the same part twice
                if (name.endsWith("/")) {                                    // a folder entry: must be empty
                    if (size != 0) throw new Refused(IS_DAMAGED);
                    continue;
                }

                // the part's own header must say the same as the directory
                if (at + 30 > dirAt || u32((int) at) != 0x04034b50L) throw new Refused(IS_DAMAGED);
                int h = (int) at;
                int localNameLen = u16(h + 26);
                int localExtraLen = u16(h + 28);
                if (u16(h + 8) != method || ((u16(h + 6) ^ flags) & 0x41) != 0 || localNameLen != nameLen
                        || !name(h + 30, nameLen).equals(name)) {
                    throw new Refused(IS_DAMAGED);
                }
                long data = at + 30 + localNameLen + localExtraLen;
                if (data + packed > dirAt) throw new Refused(IS_DAMAGED);

                total += size;
                if (total > MAX_UNPACKED) throw new Refused(IS_TOO_LARGE);
                parts.add(new Part(name, method, crc, (int) data, (int) packed, (int) size));
            }
            if (p != dirEnd) throw new Refused(IS_DAMAGED);
        }

        Part part(String name) {
            for (Part p : parts) if (p.name.equals(name)) return p;
            return null;
        }

        /** The part unpacked (an XML part: it is held in memory, so it may not be huge). */
        byte[] bytes(Part p) {
            if (p.size > MAX_XML_PART) throw new Refused(IS_TOO_LARGE);
            byte[] out = new byte[p.size];
            unpack(p, out);
            return out;
        }

        /** Unpacks the part only to see that it is whole. */
        void verify(Part p) {
            unpack(p, null);
        }

        private void unpack(Part p, byte[] out) {
            CRC32 sum = new CRC32();
            if (p.method == 0) {
                if (p.packed != p.size) throw new Refused(IS_DAMAGED);
                sum.update(b, p.data, p.packed);
                if (sum.getValue() != p.crc) throw new Refused(IS_DAMAGED);
                if (out != null) System.arraycopy(b, p.data, out, 0, p.size);
                return;
            }
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(b, p.data, p.packed);
                byte[] chunk = new byte[64 * 1024];
                int done = 0;
                while (!inf.finished()) {
                    int n = inf.inflate(chunk);
                    if (n == 0) {
                        if (inf.finished()) break;
                        throw new Refused(IS_DAMAGED);         // wants more input, or a dictionary: not a plain part
                    }
                    if ((long) done + n > p.size) throw new Refused(IS_DAMAGED);      // bigger than the directory says
                    sum.update(chunk, 0, n);
                    if (out != null) System.arraycopy(chunk, 0, out, done, n);
                    done += n;
                }
                if (done != p.size || sum.getValue() != p.crc || inf.getRemaining() != 0) throw new Refused(IS_DAMAGED);
            } catch (DataFormatException e) {
                throw new Refused(IS_DAMAGED);
            } finally {
                inf.end();
            }
        }

        /** A part's name: plain letters, digits and the usual signs, no tricks with folders. Lower-cased. */
        private String name(int at, int len) {
            if (len == 0 || len > 512 || at < 0 || at + len > b.length) throw new Refused(IS_DAMAGED);
            StringBuilder s = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                int c = b[at + i] & 0xFF;
                if (c < 0x20 || c > 0x7E || c == '\\' || c == ':') throw new Refused(IS_DAMAGED);
                s.append((char) c);
            }
            String name = s.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith("/") || name.contains("//") || name.equals("..") || name.startsWith("../")
                    || name.contains("/../") || name.endsWith("/..")) {
                throw new Refused(IS_DAMAGED);
            }
            return name;
        }

        private int u16(int at) {
            if (at < 0 || at + 2 > b.length) throw new Refused(IS_DAMAGED);
            return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
        }

        private long u32(int at) {
            if (at < 0 || at + 4 > b.length) throw new Refused(IS_DAMAGED);
            return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
        }

        /** An 8-byte number; anything a .docx of sensible size cannot have is refused. */
        private long u64(int at) {
            long low = u32(at);
            long high = u32(at + 4);
            if (high != 0 || low > Integer.MAX_VALUE) throw new Refused(IS_TOO_LARGE);
            return low;
        }
    }
}

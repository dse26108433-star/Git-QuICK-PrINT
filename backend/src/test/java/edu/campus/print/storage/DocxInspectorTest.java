package edu.campus.print.storage;

import edu.campus.print.support.Docx;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static edu.campus.print.support.Docx.field;
import static edu.campus.print.support.Docx.inner;
import static edu.campus.print.support.Docx.rel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the server lets through to Microsoft Word on the Xerox PC: plain
 * documents, and nothing that can run, fetch or prompt.
 */
class DocxInspectorTest {

    private static DocxInspector.Verdict look(Docx d) {
        return DocxInspector.inspect(d.bytes());
    }

    private static void fine(Docx d) {
        DocxInspector.Verdict v = look(d);
        assertThat(v.ok()).as(v.code() + " " + v.message()).isTrue();
    }

    private static void active(Docx d) {
        assertThat(look(d).code()).isEqualTo(DocxInspector.ACTIVE);
    }

    // ------------------------------------------------------------------ plain documents pass

    @Test
    void aPlainDocumentIsFine() {
        fine(Docx.plain());
        fine(Docx.pages(12));
        // the signs XML writes in its own way are ordinary text
        fine(Docx.plain().body("<w:p><w:r><w:t>Tom &amp; Jerry &lt;3 &#8211; &quot;quoted&quot; &apos;x&apos; &#x928;</w:t></w:r></w:p>"));
    }

    @Test
    void aDocumentWrittenByMicrosoftWordItselfIsFine() throws Exception {
        byte[] real = getClass().getResourceAsStream("/word/made-by-word.docx").readAllBytes();
        assertThat(DocxInspector.looksLikeZip(real)).isTrue();
        DocxInspector.Verdict v = DocxInspector.inspect(real);
        assertThat(v.ok()).as(v.code() + " " + v.message()).isTrue();
    }

    @Test
    void whatOrdinaryDocumentsHaveIsFine() {
        // a picture, a hyperlink, a chart with its own table, page numbers, a table of contents, a citation add-in's note
        fine(Docx.plain()
                .links(rel("rId1", "image", "media/image1.png", false),
                        rel("rId2", "hyperlink", "https://example.org/a?b=1", true),
                        rel("rId3", "chart", "charts/chart1.xml", false),
                        rel("rId4", "attachedTemplate", "file:///C:\\Users\\asha\\AppData\\Roaming\\Microsoft\\Templates\\Report%20(2).dotx", true))
                .part("word/media/image1.png", new byte[]{1, 2, 3})
                .part("word/charts/chart1.xml", "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"/>")
                .part("word/charts/_rels/chart1.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + rel("rId1", "package", "../embeddings/Microsoft_Excel_Sheet1.xlsx", false) + "</Relationships>")
                .part("word/embeddings/Microsoft_Excel_Sheet1.xlsx", new byte[]{4, 5, 6})
                .body(field(" PAGE ") + field(" TOC \\o \"1-3\" \\h ") + field(" NUMPAGES ")
                        + field(" ADDIN ZOTERO_ITEM CSL_CITATION {\"x\":1} ")
                        // a field inside another one, after the name: { IF { PAGE } = 1 "first" "other" }
                        + field(" IF ", inner(" PAGE ", "1"), " = 1 \"first\" \"other\" ")
                        + "<w:p><w:fldSimple w:instr=\" DATE \\@ &quot;d MMMM yyyy&quot; \"><w:r><w:t>1 May</w:t></w:r></w:fldSimple></w:p>"));
        // a template on the writer's own disk, in the ways it is written
        for (String t : new String[]{"Normal.dotm", "C:\\Templates\\a.dotx", "file:///D:/x/y.dotm", "file:///Users/asha/Library/x.dotm",
                "file://localhost/Users/asha/x.dotm"}) {
            fine(Docx.plain().links(rel("rId1", "attachedTemplate", t, true)));
        }
    }

    // ------------------------------------------------------------------ things that run

    @Test
    void macrosAndControlsAreRefused() {
        active(Docx.plain().mainType("application/vnd.ms-word.document.macroEnabled.main+xml"));
        active(Docx.plain().part("word/vbaProject.bin", new byte[]{1}));
        active(Docx.plain().links(rel("rId1", "vbaProject", "vbaProject.bin", false)));
        active(Docx.plain().part("word/activeX/activeX1.xml", "<x/>"));
        active(Docx.plain().links(rel("rId1", "control", "activeX/activeX1.xml", false)));
        active(Docx.plain().links(rel("rId1", "attachedToolbars", "attachedToolbars.bin", false)));
        active(Docx.plain().links(rel("rId1", "keyMapCustomizations", "customizations.xml", false)));
    }

    @Test
    void embeddedObjectsOfTheOldKindAreRefused() {
        active(Docx.plain().links(rel("rId1", "oleObject", "embeddings/oleObject1.bin", false)));
        active(Docx.plain().part("word/embeddings/oleObject1.bin", new byte[]{1}));
        active(Docx.plain().links(rel("rId1", "package", "embeddings/thing.exe", false)));
        active(Docx.plain().links(rel("rId1", "oleObject", "file:///C:\\x.xls", true)));
    }

    @Test
    void piecesMergedInWhenTheFileOpensAreRefused() {
        active(Docx.plain().links(rel("rId1", "aFChunk", "afchunk.rtf", false)));
        active(Docx.plain().links(rel("rId1", "subDocument", "other.docx", true)));
        active(Docx.plain().links(rel("rId1", "frame", "http://example.org/", true)));
        active(Docx.plain().part("word/_rels/settings.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + rel("rId1", "mailMergeSource", "file:///\\\\server\\share\\names.xlsx", true) + "</Relationships>"));
    }

    // ------------------------------------------------------------------ things that fetch

    @Test
    void anythingLoadedFromOutsideTheFileIsRefused() {
        active(Docx.plain().links(rel("rId1", "image", "http://tracker.example/pixel.png", true)));
        // a template on a server, in every way one can be written
        for (String t : new String[]{"http://evil.example/t.dotm", "https://evil.example/t.dotm", "\\\\evil\\share\\t.dotm",
                "file://evil/share/t.dotm", "file:///\\\\evil\\share\\t.dotm", "file:////evil/share/t.dotm",
                "file:///%5C%5Cevil%5Cshare%5Ct.dotm", "//evil/share/t.dotm", "ftp://evil/t.dotm", ""}) {
            active(Docx.plain().part("word/_rels/settings.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + rel("rId1", "attachedTemplate", t, true) + "</Relationships>"));
        }
    }

    @Test
    void fieldsThatReadOtherFilesOrRunCommandsAreRefused() {
        active(Docx.plain().body(field(" DDEAUTO excel \"Book1.xls\" r1c1 ")));
        active(Docx.plain().body(field("dde excel \"x\" ")));
        // the name split over several runs, as an attacker would write it
        active(Docx.plain().body(field(" DD", "EAU", "TO c:\\\\x ")));
        active(Docx.plain().body(field(" INCLUDEPICTURE \"http://tracker.example/p.png\" \\d ")));
        active(Docx.plain().body(field(" INCLUDETEXT \"\\\\\\\\server\\\\share\\\\a.docx\" ")));
        active(Docx.plain().body(field(" LINK Excel.Sheet.8 \"C:\\\\a.xls\" ")));
        active(Docx.plain().body(field(" FILLIN \"Your name?\" ")));
        active(Docx.plain().body("<w:p><w:fldSimple w:instr=\" IncludePicture &quot;http://x/y.png&quot; \"/></w:p>"));
        // in a header, a footnote or anywhere else: every part is read
        active(Docx.plain().part("word/header1.xml", "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + field(" DDE x y ") + "</w:hdr>"));
        // a non-breaking space before the name is still a space
        active(Docx.plain().body(field("\u00A0DDEAUTO x y ")));
    }

    @Test
    void aFieldWhoseNameIsMadeByAnotherFieldIsRefused() {
        // { { QUOTE 68 68 69 } x y }: Word works out the inner field first, and the outer one becomes DDE
        active(Docx.plain().body(field(inner(" QUOTE 68 68 69 ", "DDE"), " x y ")));
        active(Docx.plain().body(field(" ", inner(" QUOTE 68 68 69 ", "DDE"), " x y ")));
        // only part of the name made that way
        active(Docx.plain().body(field("DD", inner(" QUOTE 69 ", "E"), " x y ")));
    }

    @Test
    void fontsOddPicturesAndAddInsAreRefused() {
        active(Docx.plain().part("word/fonts/font1.odttf", new byte[]{1}));
        active(Docx.plain().links(rel("rId1", "font", "fonts/font1.odttf", false)));
        active(Docx.plain().part("word/media/image1.eps", new byte[]{1}));
        active(Docx.plain().links(rel("rId1", "image", "../picture.eps", false)).part("picture.eps", new byte[]{1}));
        active(Docx.plain().links(rel("rId1", "webExtension", "webextensions/webextension1.xml", false)));
    }

    // ------------------------------------------------------------------ other files, broken files, tricks

    @Test
    void otherKindsOfFileAreToldWhatIsAccepted() throws Exception {
        // a ZIP that is no Office file
        ByteArrayOutputStream plainZip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(plainZip)) {
            z.putNextEntry(new ZipEntry("notes.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        DocxInspector.Verdict v = DocxInspector.inspect(plainZip.toByteArray());
        assertThat(v.code()).isEqualTo(DocxInspector.NOT_WORD);
        assertThat(v.message()).isEqualTo("Only PDF, Word (.docx), PNG and JPG files can be printed.");

        // an Excel sheet
        v = look(Docx.plain().mainType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"));
        assertThat(v.code()).isEqualTo(DocxInspector.NOT_WORD);
        assertThat(v.message()).contains("Save this file as PDF");
        // a Word template
        v = look(Docx.plain().mainType("application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml"));
        assertThat(v.message()).startsWith("This is a Word template");

        // an old .doc, and a new file locked with a password (both start the same way)
        byte[] head = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        byte[] oldDoc = join(head, new byte[600], "WordDocument".getBytes(StandardCharsets.UTF_16LE));
        assertThat(DocxInspector.looksLikeOldOffice(oldDoc)).isTrue();
        assertThat(DocxInspector.oldOffice(oldDoc).code()).isEqualTo(DocxInspector.OLD_WORD);
        assertThat(DocxInspector.oldOffice(oldDoc).message()).contains(".doc").contains("Save As");
        byte[] locked = join(head, new byte[600], "EncryptedPackage".getBytes(StandardCharsets.UTF_16LE));
        assertThat(DocxInspector.oldOffice(locked).code()).isEqualTo(DocxInspector.PASSWORD);
        assertThat(DocxInspector.oldOffice(join(head, new byte[600])).code()).isEqualTo(DocxInspector.NOT_WORD);
    }

    @Test
    void aFileThatCouldBeReadInTwoWaysIsRefused() {
        byte[] good = Docx.plain().bytes();
        assertThat(DocxInspector.inspect(good).ok()).isTrue();

        // something after the ZIP's directory (a comment, or a second file glued on)
        assertThat(look(Docx.plain().zipComment("hello")).code()).isEqualTo(DocxInspector.DAMAGED);
        assertThat(DocxInspector.inspect(join(good, new byte[]{0})).code()).isEqualTo(DocxInspector.DAMAGED);
        // the same part twice (names differ only in capitals, which Word does not tell apart)
        assertThat(look(Docx.plain().part("word/Document.xml", "<x/>")).code()).isEqualTo(DocxInspector.DAMAGED);
        // a part whose bytes are not what the directory says (one byte changed)
        byte[] bad = good.clone();
        int at = indexOf(bad, "word/document.xml".getBytes(StandardCharsets.US_ASCII)) + "word/document.xml".length() + 4;
        bad[at] ^= 0x55;
        assertThat(DocxInspector.inspect(bad).code()).isEqualTo(DocxInspector.DAMAGED);
        // cut short
        assertThat(DocxInspector.inspect(java.util.Arrays.copyOf(good, good.length - 9)).code()).isEqualTo(DocxInspector.DAMAGED);
        // a part marked as encrypted, in the directory and in its own header
        byte[] locked = good.clone();
        for (int i = 0; i + 8 < locked.length; i++) {
            boolean local = locked[i] == 'P' && locked[i + 1] == 'K' && locked[i + 2] == 3 && locked[i + 3] == 4;
            boolean central = locked[i] == 'P' && locked[i + 1] == 'K' && locked[i + 2] == 1 && locked[i + 3] == 2;
            if (local) locked[i + 6] |= 1;
            if (central) locked[i + 8] |= 1;
        }
        assertThat(DocxInspector.inspect(locked).code()).isEqualTo(DocxInspector.PASSWORD);
    }

    @Test
    void xmlTricksAreRefused() {
        // a document type declaration (the door to "entities" that read files or blow up in memory)
        assertThat(look(Docx.plain().part("word/document.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE w [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>"))
                .code()).isEqualTo(DocxInspector.DAMAGED);
        // not XML at all
        assertThat(look(Docx.plain().part("word/document.xml", "this is not xml")).code()).isEqualTo(DocxInspector.DAMAGED);
        // no document part
        assertThat(look(Docx.plain().part("_rels/.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>")).code())
                .isEqualTo(DocxInspector.DAMAGED);
    }

    @Test
    void aSmallFileThatUnpacksToAHugeOneIsRefused() {
        // 250 MB of spaces pack into a few hundred KB
        byte[] bomb = Docx.plain().blank("word/media/image1.png", 250).bytes();
        assertThat(bomb.length).isLessThan(2 * 1024 * 1024);
        assertThat(DocxInspector.inspect(bomb).code()).isEqualTo(DocxInspector.TOO_LARGE);
        // an XML part too big to hold in memory
        assertThat(look(Docx.plain().blank("word/header9.xml", 40)).code()).isEqualTo(DocxInspector.TOO_LARGE);
        // big, but within what a long document with many pictures has: read through, part by part
        fine(Docx.plain().blank("word/media/image1.png", 60).blank("word/media/image2.png", 60));
    }

    @Test
    void localFilesAreToldFromServers() {
        assertThat(DocxInspector.localFile("file:///C:\\Users\\a\\b.dotx")).isTrue();
        assertThat(DocxInspector.localFile("D:/t/x.dotm")).isTrue();
        assertThat(DocxInspector.localFile("Normal.dotm")).isTrue();
        assertThat(DocxInspector.localFile("\\\\server\\share\\x.dotm")).isFalse();
        assertThat(DocxInspector.localFile("file://server/x.dotm")).isFalse();
        assertThat(DocxInspector.localFile("file:///%5c%5cserver%5cx.dotm")).isFalse();
        assertThat(DocxInspector.localFile("http://x/y.dotm")).isFalse();
        assertThat(DocxInspector.localFile("C:\\\\server\\x")).isFalse();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new IllegalStateException("not found");
    }
}

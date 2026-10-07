package edu.campus.agent.word;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real thing: the Microsoft Word installed on this PC turns Word files
 * into PDFs, hidden, and goes away again. Skipped on PCs without Word.
 */
@EnabledOnOs(OS.WINDOWS)
class WordEngineTest {

    @TempDir
    Path dir;

    private WordEngine engine;

    @BeforeEach
    void start() {
        // a folder with a space and letters that are not English, like a Windows user's own folder can have
        engine = new WordEngine(dir.resolve("Xerox केंद्र").resolve("word"));
        WordEngine.State s = engine.check();
        Assumptions.assumeTrue(s.ready(), "Microsoft Word is not usable on this PC: " + s.note());
    }

    @AfterEach
    void stop() {
        if (engine != null) engine.close();
    }

    private static long wordProcesses() {
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.toUpperCase().endsWith("WINWORD.EXE")).orElse(false)).count();
    }

    @Test
    void theTestSaysWhichWordThisIs() {
        assertThat(engine.state().ready()).isTrue();
        assertThat(engine.state().note()).startsWith("Microsoft Word 20");
    }

    @Test
    void aDocumentWrittenInWordComesOutAsItsPages() throws Exception {
        Path in = engine.dir().resolve("Physics assignment.docx");
        Files.write(in, getClass().getResourceAsStream("/word/made-by-word.docx").readAllBytes());
        Path out = engine.dir().resolve("pages.pdf");
        WordEngine.Result r = engine.convert(in, out, Duration.ofSeconds(60));
        assertThat(r.ok()).as(r.code() + " " + r.message()).isTrue();
        assertThat(r.pages()).isEqualTo(3);
        assertThat(WordEngine.looksLikePdf(out)).isTrue();
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(3);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Physics assignment").contains("Second page.").contains("Third page.").contains("R2 C3");
        }
        // the file given to Word is untouched (it is opened read-only)
        assertThat(Files.readAllBytes(in)).isEqualTo(getClass().getResourceAsStream("/word/made-by-word.docx").readAllBytes());
    }

    @Test
    void manyFilesInARowUseTheSameHiddenWord() throws Exception {
        long word = engine.hiddenWord();
        assertThat(word).isPositive();
        long started = System.nanoTime();
        for (int i = 1; i <= 12; i++) {
            Path in = engine.dir().resolve("f" + i + ".docx");
            Files.write(in, WordEngine.testDocument());
            Path out = engine.dir().resolve("f" + i + ".pdf");
            WordEngine.Result r = engine.convert(in, out, Duration.ofSeconds(60));
            assertThat(r.ok()).as("file " + i + ": " + r.code() + " " + r.message()).isTrue();
            assertThat(r.pages()).isEqualTo(1);
            assertThat(WordEngine.looksLikePdf(out)).isTrue();
        }
        long each = (System.nanoTime() - started) / 12 / 1_000_000;
        System.out.println("Word: " + each + " ms per small file");
        assertThat(engine.hiddenWord()).isEqualTo(word);          // no new Word for each file
        assertThat(each).isLessThan(5_000);
    }

    @Test
    void aFileWordCannotOpenIsAProblemOfThatFileOnly() throws Exception {
        Path bad = engine.dir().resolve("broken.docx");
        Files.write(bad, "PK\u0003\u0004 this is no Word file at all".getBytes(StandardCharsets.ISO_8859_1));
        WordEngine.Result r = engine.convert(bad, engine.dir().resolve("broken.pdf"), Duration.ofSeconds(60));
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isIn("FAILED", "PASSWORD");
        assertThat(Files.exists(engine.dir().resolve("broken.pdf"))).isFalse();

        WordEngine.Result missing = engine.convert(engine.dir().resolve("not-there.docx"), engine.dir().resolve("x.pdf"),
                Duration.ofSeconds(60));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.code()).isEqualTo("FAILED");

        // the next good file is turned as if nothing had happened
        Path good = engine.dir().resolve("good.docx");
        Files.write(good, WordEngine.testDocument());
        assertThat(engine.convert(good, engine.dir().resolve("good.pdf"), Duration.ofSeconds(60)).ok()).isTrue();
    }

    @Test
    void aWordThatTakesTooLongIsEndedAndTheNextFileGetsANewOne() throws Exception {
        long before = engine.hiddenWord();
        Path in = engine.dir().resolve("slow.docx");
        Files.write(in, WordEngine.testDocument());
        WordEngine.Result r = engine.convert(in, engine.dir().resolve("slow.pdf"), Duration.ofMillis(1));
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo("TOO_SLOW");
        assertThat(engine.hiddenWord()).isZero();                  // that Word is gone
        assertThat(ProcessHandle.of(before).map(ProcessHandle::isAlive).orElse(false)).isFalse();

        WordEngine.Result again = engine.convert(in, engine.dir().resolve("slow.pdf"), Duration.ofSeconds(60));
        assertThat(again.ok()).as(again.code() + " " + again.message()).isTrue();
        assertThat(engine.hiddenWord()).isPositive().isNotEqualTo(before);
    }

    @Test
    void closingTheStationClosesTheHiddenWord() throws Exception {
        long word = engine.hiddenWord();
        assertThat(ProcessHandle.of(word).map(ProcessHandle::isAlive).orElse(false)).isTrue();
        long others = wordProcesses() - 1;
        engine.close();
        engine = null;
        long until = System.currentTimeMillis() + 15_000;
        while (ProcessHandle.of(word).map(ProcessHandle::isAlive).orElse(false) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertThat(ProcessHandle.of(word).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(wordProcesses()).isEqualTo(others);             // and no other Word was touched
    }
}

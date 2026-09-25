package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Backup way of printing, for the rare file PDFBox draws badly (unusual fonts,
 * very large scans). SumatraPDF is a small free viewer with a silent print
 * mode; it prints PDFs and pictures through the same Canon driver.
 *
 * To use it: install SumatraPDF, then set printStrategy: external in agent.yml.
 */
public class ExternalToolPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExternalToolPrintStrategy.class);

    private final Path tool;
    private final int timeoutSeconds;

    public ExternalToolPrintStrategy(Path tool, int timeoutSeconds) {
        this.tool = tool;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void print(PDDocument doc, Path original, Settings s) throws Exception {
        if (!Files.isRegularFile(tool)) {
            throw new IllegalStateException("SumatraPDF not found at " + tool + " (externalToolPath in agent.yml)");
        }
        // SumatraPDF needs a file: save the prepared document (with the pickup
        // code on the first page) as a short-lived copy next to the download.
        Path folder = original.toAbsolutePath().getParent();
        Path copy = Files.createTempFile(folder, "print-", ".pdf");
        try {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);   // the copy only lives until it is printed
            }
            doc.save(copy.toFile());

            String settings = (s.color() ? "color" : "monochrome") + ",fit," + Math.max(1, s.copies()) + "x";
            List<String> cmd = List.of(tool.toString(), "-print-to", s.windowsPrinterName(),
                    "-print-settings", settings, "-silent", "-exit-when-done", copy.toString());
            log.info("Order {}: {}", s.pickupCode(), String.join(" ", cmd));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("SumatraPDF did not finish within " + timeoutSeconds + " s");
            }
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0) {
                throw new IllegalStateException("SumatraPDF failed (exit " + p.exitValue() + "): " + output);
            }
        } finally {
            Files.deleteIfExists(copy);
        }
    }
}

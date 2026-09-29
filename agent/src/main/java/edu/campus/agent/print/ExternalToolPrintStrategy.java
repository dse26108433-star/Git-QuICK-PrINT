package edu.campus.agent.print;

import edu.campus.agent.net.Messages.JobSettings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Backup way of printing, for the rare file PDFBox draws badly (unusual fonts,
 * very large scans). SumatraPDF is a small free viewer with a silent print
 * mode; it prints through the same driver.
 *
 * It gets the same prepared sheets (already the paper's size and laid out) and
 * prints them at 100 % ("noscale"), with the same paper, sides, colour and copies.
 *
 * To use it: install SumatraPDF, then set printStrategy: external in agent.yml.
 */
public class ExternalToolPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExternalToolPrintStrategy.class);

    /** SumatraPDF's names for the paper sizes it knows. */
    private static final Map<String, String> PAPER = Map.of(
            "A3", "A3", "A4", "A4", "A5", "A5", "A6", "A6",
            "LETTER", "letter", "LEGAL", "legal", "TABLOID", "tabloid", "STATEMENT", "statement");

    private final Path tool;
    private final int timeoutSeconds;

    public ExternalToolPrintStrategy(Path tool, int timeoutSeconds) {
        this.tool = tool;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void print(PDDocument sheets, Path original, PrintJob job) throws Exception {
        if (!Files.isRegularFile(tool)) {
            throw new IllegalStateException("SumatraPDF not found at " + tool + " (externalToolPath in agent.yml)");
        }
        String paper = PAPER.get(job.paper().id());
        if (paper == null) {
            throw new IllegalStateException("SumatraPDF cannot choose " + job.paper().id()
                    + " paper; use printStrategy: pdfbox for this printer");
        }
        // SumatraPDF needs a file: save the prepared sheets as a short-lived copy next to the download.
        Path folder = original.toAbsolutePath().getParent();
        Path copy = Files.createTempFile(folder, "print-", ".pdf");
        try {
            if (sheets.isEncrypted()) {
                sheets.setAllSecurityToBeRemoved(true);   // the copy only lives until it is printed
            }
            sheets.save(copy.toFile());

            JobSettings s = job.settings();
            List<String> parts = new ArrayList<>();
            parts.add(s.color() ? "color" : "monochrome");
            parts.add("noscale");
            parts.add("paper=" + paper);
            parts.add(switch (s.duplex() == null ? "" : s.duplex()) {
                case "LONG_EDGE" -> "duplexlong";
                case "SHORT_EDGE" -> "duplexshort";
                default -> "simplex";
            });
            parts.add(job.copies() + "x");
            List<String> cmd = List.of(tool.toString(), "-print-to", job.windowsPrinterName(),
                    "-print-settings", String.join(",", parts), "-silent", "-exit-when-done", copy.toString());
            log.info("Order {}: {}", job.pickupCode(), String.join(" ", cmd));

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

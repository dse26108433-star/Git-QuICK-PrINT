package edu.campus.agent.core;

import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.net.BackendClient;
import edu.campus.agent.net.Messages.ClaimedJob;
import edu.campus.agent.net.Messages.PrinterConfig;
import edu.campus.agent.print.PrinterStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paid document must never go to a printer that cannot print now, never be
 * reported as printed when that is not known, and never be printed twice.
 */
class ReliabilityTest {

    @Test
    void printersThatCannotPrintNowTakeNoNewDocuments() {
        assertThat(PrinterStatus.problem("Normal", false)).isNull();
        assertThat(PrinterStatus.problem("Printing", false)).isNull();
        assertThat(PrinterStatus.problem("Busy, TonerLow", false)).isNull();
        assertThat(PrinterStatus.problem("", false)).isNull();
        assertThat(PrinterStatus.problem("Offline", false)).isEqualTo("Printer is offline");
        assertThat(PrinterStatus.problem("PaperOut, Error", false)).isEqualTo("Out of paper");
        assertThat(PrinterStatus.problem("PaperJam", false)).isEqualTo("Paper jam");
        assertThat(PrinterStatus.problem("Paused", false)).isEqualTo("Paused in Windows");
        assertThat(PrinterStatus.problem("DoorOpen, UserIntervention", false)).isEqualTo("A door is open");
        assertThat(PrinterStatus.problem("Normal", true)).isEqualTo("Set to \"Use printer offline\" in Windows");
    }

    /**
     * The PC is usually on before the printer. In the first seconds after the Station starts nobody
     * has asked Windows how the printer is: until that first answer it takes no document, and when
     * the answer is "cannot print now" it still takes none.
     */
    @Test
    void aPrinterNobodyHasAskedWindowsAboutYetTakesNoDocument() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        BackendClient backend = new BackendClient(new AgentConfig()) {
            @Override
            public Optional<ClaimedJob> claim(String printerId) {
                asked.incrementAndGet();
                return Optional.empty();
            }
        };
        PrinterHealth health = new PrinterHealth();
        health.setPresent("p1", true);              // installed in Windows; how it is, nobody knows yet
        assertThat(health.looked("p1")).isFalse();
        PrinterWorker worker = new PrinterWorker(new PrinterConfig("p1", "Printer 1", "Any printer", false, true, true, "", false),
                backend, null, health, Duration.ofMillis(50));
        worker.start();
        try {
            Thread.sleep(1500);
            assertThat(asked.get()).as("asked for a document before Windows said how the printer is").isZero();

            health.setNotReady("p1", "Printer is offline");      // the first answer: it cannot print now
            assertThat(health.looked("p1")).isTrue();
            Thread.sleep(1500);
            assertThat(asked.get()).as("asked for a document for a printer that is offline").isZero();

            health.setNotReady("p1", null);                      // switched on: ready
            for (int i = 0; i < 200 && asked.get() == 0; i++) Thread.sleep(50);
            assertThat(asked.get()).as("a ready printer asks for documents").isPositive();
        } finally {
            worker.stop();
        }

        // a PC where Windows cannot be asked at all prints as before
        PrinterHealth blind = new PrinterHealth();
        blind.cannotLook("p2");
        assertThat(blind.looked("p2")).isTrue();
        assertThat(blind.notReady("p2")).isNull();
    }

    @Test
    void theJournalRemembersWhereASentDocumentWaits(@TempDir Path dir) throws Exception {
        Journal j = new Journal(dir);
        j.recordSent("doc-1", "token-1", "K7M4X");
        assertThat(j.get("doc-1").queueName()).isNull();
        j.recordQueued(j.get("doc-1"), "Canon iR2625", "CampusPrint-K7M4X-doc-1");
        Journal.Entry e = new Journal(dir).get("doc-1");               // as read after a restart
        assertThat(e.printer()).isEqualTo("Canon iR2625");
        assertThat(e.queueName()).isEqualTo("CampusPrint-K7M4X-doc-1");
        assertThat(e.claimToken()).isEqualTo("token-1");
        assertThat(e.outcomeStatus()).isNull();                        // not known yet: watched again, not reported
        j.recordOutcome(e, "COMPLETED", "", "printed");
        Journal.Entry done = new Journal(dir).get("doc-1");
        assertThat(done.outcomeStatus()).isEqualTo("COMPLETED");
        assertThat(done.queueName()).isEqualTo("CampusPrint-K7M4X-doc-1");
        assertThat(j.has("doc-1")).isTrue();                           // never printed again while the entry exists
        j.clear("doc-1");
        assertThat(j.has("doc-1")).isFalse();
    }

    /** On this PC's test printer: paused in Windows = not ready; resumed = ready again. */
    @Test
    void aPausedWindowsPrinterIsSeenAsNotReady() throws Exception {
        String printer = "CampusPrint Test BW";
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Map<String, String> now = PrinterStatus.read(List.of(printer));
        Assumptions.assumeTrue(now.containsKey(printer), "test printer not installed");
        assertThat(now.get(printer)).isNull();
        String cim = "Get-CimInstance Win32_Printer | Where-Object Name -eq '" + printer + "' | Invoke-CimMethod -MethodName ";
        Assumptions.assumeTrue(ps(cim + "Pause | Out-Null"), "not allowed to pause the test printer");
        try {
            assertThat(PrinterStatus.read(List.of(printer)).get(printer)).isEqualTo("Paused in Windows");
        } finally {
            ps(cim + "Resume | Out-Null");
        }
        assertThat(PrinterStatus.read(List.of(printer)).get(printer)).isNull();
    }

    private static boolean ps(String command) throws Exception {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference='Stop'; " + command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.contains("Access denied");
    }
}

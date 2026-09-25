package edu.campus.agent.print;

import javax.print.PrintService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * STEP 1 of START-HERE.md. Proves the Xerox PC can print, before any server,
 * database or payment exists.
 *
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --list
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625"
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625" notes.pdf --copies 2
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon C3530" photo.jpg --color
 *
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625" book.pdf --pages 333-390
 *
 * Options: --copies N   --color   --pages 5,10-20   --cover (also print a cover sheet)
 */
public final class PrinterSmokeTest {

    private PrinterSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--list")) {
            System.out.println("Printers Windows has installed (copy a name EXACTLY):");
            for (PrintService s : PrinterDiscovery.all()) {
                System.out.println("  \"" + s.getName() + "\"" + (PrinterDiscovery.reportsColor(s) ? "   (colour)" : ""));
            }
            if (PrinterDiscovery.all().isEmpty()) {
                System.out.println("  (none) - install the Canon driver first, then try again.");
            }
            return;
        }

        String printer = args[0];
        Path file = null;
        int copies = 1;
        boolean color = false;
        boolean cover = false;
        String pages = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--copies" -> copies = Integer.parseInt(args[++i]);
                case "--color", "--colour" -> color = true;
                case "--cover" -> cover = true;
                case "--pages" -> pages = args[++i];
                case "--no-cover" -> cover = false;      // the old default; kept so old notes still work
                default -> file = Path.of(args[i]);
            }
        }

        PrintService service = PrinterDiscovery.require(printer);
        System.out.println("Found printer: " + service.getName());

        boolean temporary = file == null;
        if (temporary) {
            file = Files.createTempFile("campusprint-test", ".pdf");
            TestPage.write(file);
        }
        String name = file.getFileName().toString().toLowerCase();
        String type = name.endsWith(".png") ? "PNG" : (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? "JPEG" : "PDF";

        PrintStrategy.Settings s = new PrintStrategy.Settings(UUID.randomUUID().toString(), service.getName(), type,
                color, copies, "TEST1", file.getFileName().toString(), pages, true);
        String queueName = new PrintEngine(new PdfBoxPrintStrategy(), true, cover ? 1 : 0).print(file, s);
        System.out.println("Sent to Windows as \"" + queueName + "\". Waiting for it to leave the queue...");

        SpoolerMonitor.Result r = new SpoolerMonitor(true, java.time.Duration.ofSeconds(2))
                .awaitCompletion(service.getName(), queueName,
                        attention -> { if (attention != null) System.out.println("  Printer says: " + attention); });
        System.out.println("Result: " + r.outcome() + " - " + r.detail());
        System.out.println();
        System.out.println("Now CHECK THE PAPER:");
        System.out.println("  - the label  Pickup TEST1  in the bottom-right corner of the first page");
        System.out.println("    (fully readable, not cut off by the edge of the paper)");
        if (cover) System.out.println("  - a cover sheet with the big code TEST1");
        if (pages != null) System.out.println("  - only pages " + pages);
        System.out.println("  - " + copies + (copies == 1 ? " copy" : " copies") + ", " + (color ? "in COLOUR" : "in black & white"));
        if (temporary) Files.deleteIfExists(file);
    }
}

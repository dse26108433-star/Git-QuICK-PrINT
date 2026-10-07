package edu.campus.agent.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.agent.net.Messages.ImageInfo;
import edu.campus.agent.net.Messages.JobSettings;
import edu.campus.agent.net.Messages.Paper;

import javax.imageio.ImageIO;
import javax.print.PrintService;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * STEP 1 of START-HERE.md. Proves the Xerox PC can print, before any server,
 * database or payment exists, and lets staff check every setting on the real
 * printers.
 *
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --list
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --features "Canon iR2625"
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625"
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625" notes.pdf --copies 2 --duplex long
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon C3530" photo.jpg --color --scale fill
 *   java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625" book.pdf --pages 333-390 --nup 2
 *
 * Options: --copies N  --color  --pages 5,10-20  --paper A3  --duplex long|short
 *          --nup 1|2|4|6|9|16  --scale fit|fill|actual|<percent>  --orientation auto|portrait|landscape
 *          --margin <mm>  --rotate 90|180|270  --staple top-left|dual-left|...  --punch left|top|...
 *          --uncollated  --cover (also print a cover sheet)
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
                System.out.println("  (none) - install the printer's driver first, then try again.");
            }
            return;
        }
        if (args[0].equals("--features")) {
            List<String> names = args.length > 1 ? List.of(args[1])
                    : PrinterDiscovery.all().stream().map(PrintService::getName).toList();
            var found = CapabilityDiscovery.discover(names);
            ObjectMapper json = new ObjectMapper();
            found.forEach((n, d) -> {
                try {
                    System.out.println("=== " + n);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(d.capabilities()));
                } catch (Exception e) {
                    System.out.println(e);
                }
            });
            return;
        }

        String printer = args[0];
        Path file = null;
        int copies = 1, nup = 1, margin = 5, rotation = 0, percent = 100;
        boolean color = false, cover = false, collate = true;
        String pages = null, paper = "A4", duplex = "ONE_SIDED", scaling = "FIT", orientation = "AUTO";
        String staple = null, punch = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--copies" -> copies = Integer.parseInt(args[++i]);
                case "--color", "--colour" -> color = true;
                case "--cover" -> cover = true;
                case "--pages" -> pages = args[++i];
                case "--paper" -> paper = args[++i].toUpperCase(Locale.ROOT);
                case "--duplex" -> duplex = args[++i].toLowerCase(Locale.ROOT).startsWith("s") ? "SHORT_EDGE" : "LONG_EDGE";
                case "--nup" -> nup = Integer.parseInt(args[++i]);
                case "--margin" -> margin = Integer.parseInt(args[++i]);
                case "--rotate" -> rotation = Integer.parseInt(args[++i]);
                case "--orientation" -> orientation = args[++i].toUpperCase(Locale.ROOT);
                case "--staple" -> staple = args[++i].toUpperCase(Locale.ROOT).replace('-', '_');
                case "--punch" -> punch = args[++i].toUpperCase(Locale.ROOT);
                case "--uncollated" -> collate = false;
                case "--scale" -> {
                    String v = args[++i].toUpperCase(Locale.ROOT);
                    if (v.matches("\\d+")) {
                        scaling = "CUSTOM";
                        percent = Integer.parseInt(v);
                    } else {
                        scaling = v;
                    }
                }
                case "--no-cover" -> cover = false;      // the old default; kept so old notes still work
                default -> file = Path.of(args[i]);
            }
        }

        PrintService service = PrinterDiscovery.require(printer);
        System.out.println("Found printer: " + service.getName());
        String paperId = paper;
        PaperSizes.Size size = PaperSizes.ALL.stream().filter(s -> s.id().equals(paperId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown paper " + paperId));

        boolean temporary = file == null;
        if (temporary) {
            file = Files.createTempFile("campusprint-test", ".pdf");
            TestPage.write(file, "ONE_SIDED".equals(duplex) ? 1 : 2);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = name.endsWith(".png") ? "PNG" : (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? "JPEG" : "PDF";
        ImageInfo image = null;
        if (!"PDF".equals(type)) {
            BufferedImage img = ImageIO.read(file.toFile());
            image = new ImageInfo(img.getWidth(), img.getHeight(), 96, ImageOrientation.exifOrientation(file));
        }

        JobSettings settings = new JobSettings(copies, color, pages, duplex, size.id(), orientation, scaling, percent,
                nup, margin, rotation, true, collate, staple, punch, null, null, "STANDARD");
        PrintJob job = new PrintJob(UUID.randomUUID().toString(), service.getName(), type, "TEST1",
                file.getFileName().toString(), 1, 1, settings, new Paper(size.id(), size.widthMm(), size.heightMm()),
                image, true);
        Path work = Files.createTempDirectory("campusprint-smoke");
        String queueName = new PrintEngine(new PdfBoxPrintStrategy(), true, cover ? 1 : 0, new PrintTicket(work),
                new CapabilityCache()).print(file, job);
        System.out.println("Sent to Windows as \"" + queueName + "\". Waiting for it to leave the queue...");

        SpoolerMonitor.Result r = new SpoolerMonitor(true, java.time.Duration.ofSeconds(2))
                .awaitCompletion(service.getName(), queueName,
                        attention -> { if (attention != null) System.out.println("  Printer says: " + attention); });
        System.out.println("Result: " + r.outcome() + " - " + r.detail());
        System.out.println();
        System.out.println("Now CHECK THE PAPER:");
        System.out.println("  - the label  Order TEST1  in the bottom-right corner of the first page");
        System.out.println("    (fully readable, not cut off by the edge of the paper)");
        if (cover) System.out.println("  - a cover sheet with the big code TEST1");
        if (pages != null) System.out.println("  - only pages " + pages);
        System.out.println("  - " + size.id() + " paper, " + ("ONE_SIDED".equals(duplex) ? "one-sided" : "two-sided")
                + (nup > 1 ? ", " + nup + " pages per sheet" : ""));
        System.out.println("  - " + copies + (copies == 1 ? " copy" : " copies") + ", "
                + (color ? "in COLOUR" : "in black & white"));
        if (temporary) Files.deleteIfExists(file);
    }
}

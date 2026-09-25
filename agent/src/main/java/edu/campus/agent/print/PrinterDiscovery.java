package edu.campus.agent.print;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.ColorSupported;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finds the Canons among the printers Windows has installed on this PC. The
 * program never talks to a printer directly: Windows and the Canon driver do.
 */
public final class PrinterDiscovery {

    private PrinterDiscovery() {
    }

    public static List<PrintService> all() {
        return Arrays.asList(PrintServiceLookup.lookupPrintServices(null, null));
    }

    /**
     * Exact name first, then the same name ignoring upper/lower case, then a
     * partial match but only if exactly one printer matches (never guess
     * between two).
     */
    public static Optional<PrintService> find(String windowsPrinterName) {
        if (windowsPrinterName == null || windowsPrinterName.isBlank()) {
            return Optional.empty();
        }
        String wanted = windowsPrinterName.trim();
        List<PrintService> services = all();
        for (PrintService s : services) {
            if (s.getName().equals(wanted)) return Optional.of(s);
        }
        for (PrintService s : services) {
            if (s.getName().equalsIgnoreCase(wanted)) return Optional.of(s);
        }
        List<PrintService> partial = services.stream()
                .filter(s -> s.getName().toLowerCase().contains(wanted.toLowerCase()))
                .toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    public static PrintService require(String windowsPrinterName) {
        return find(windowsPrinterName).orElseThrow(() -> new IllegalStateException(
                "Windows printer not found: \"" + windowsPrinterName + "\". Installed printers: "
                        + all().stream().map(PrintService::getName).toList()));
    }

    public static boolean reportsColor(PrintService s) {
        ColorSupported c = s.getAttribute(ColorSupported.class);
        return c != null && c == ColorSupported.SUPPORTED;
    }
}

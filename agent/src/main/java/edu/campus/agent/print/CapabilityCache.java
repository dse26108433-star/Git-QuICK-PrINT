package edu.campus.agent.print;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last thing each printer said it can do (found by CapabilityDiscovery),
 * kept for printing: which paper types and quality modes to ask for by name.
 */
public class CapabilityCache {

    private final Map<String, CapabilityDiscovery.Discovered> byPrinter = new ConcurrentHashMap<>();

    public Optional<CapabilityDiscovery.Discovered> get(String windowsPrinterName) {
        return Optional.ofNullable(byPrinter.get(windowsPrinterName));
    }

    public void put(String windowsPrinterName, CapabilityDiscovery.Discovered d) {
        byPrinter.put(windowsPrinterName, d);
    }

    /** Finds out now (a few seconds) for the printers not known yet. */
    public void ensure(List<String> windowsPrinterNames) {
        List<String> missing = windowsPrinterNames.stream().filter(n -> !byPrinter.containsKey(n)).toList();
        if (!missing.isEmpty()) CapabilityDiscovery.discover(missing).forEach(this::put);
    }
}

package edu.campus.agent.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this PC currently knows about each printer, for the counter screen:
 * is it installed in Windows, and does it need a person (paper out, jam...)?
 */
public class PrinterHealth {

    private final Map<String, Boolean> present = new ConcurrentHashMap<>();
    private final Map<String, String> attention = new ConcurrentHashMap<>();
    private final Map<String, String> notReady = new ConcurrentHashMap<>();
    private final Set<String> looked = ConcurrentHashMap.newKeySet();

    public void setPresent(String printerId, boolean isPresent) {
        present.put(printerId, isPresent);
    }

    public boolean isPresent(String printerId) {
        return present.getOrDefault(printerId, false);
    }

    /** null clears it. */
    public void setAttention(String printerId, String detail) {
        if (detail == null || detail.isBlank()) attention.remove(printerId);
        else attention.put(printerId, detail);
    }

    public String attention(String printerId) {
        return attention.get(printerId);
    }

    /** Windows says the printer cannot print now (offline, paper out...); null = ready. */
    public void setNotReady(String printerId, String why) {
        if (why == null || why.isBlank()) notReady.remove(printerId);
        else notReady.put(printerId, why);
        looked.add(printerId);
    }

    /** Why the printer takes no new documents now, or null. */
    public String notReady(String printerId) {
        return notReady.get(printerId);
    }

    /**
     * True once Windows has been asked how this printer is (or it is known that it cannot be asked).
     * Until then nobody knows whether it can print: a printer that is switched off when the Station
     * starts must not get a paid document into its queue in those first seconds.
     */
    public boolean looked(String printerId) {
        return looked.contains(printerId);
    }

    /** Windows cannot be asked about printers on this PC, or the check is switched off: the printer counts as ready. */
    public void cannotLook(String printerId) {
        looked.add(printerId);
    }
}

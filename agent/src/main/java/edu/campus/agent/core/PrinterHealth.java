package edu.campus.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this PC currently knows about each printer, for the counter screen:
 * is it installed in Windows, and does it need a person (paper out, jam...)?
 */
public class PrinterHealth {

    private final Map<String, Boolean> present = new ConcurrentHashMap<>();
    private final Map<String, String> attention = new ConcurrentHashMap<>();
    private final Map<String, String> notReady = new ConcurrentHashMap<>();

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
    }

    /** Why the printer takes no new documents now, or null. */
    public String notReady(String printerId) {
        return notReady.get(printerId);
    }
}

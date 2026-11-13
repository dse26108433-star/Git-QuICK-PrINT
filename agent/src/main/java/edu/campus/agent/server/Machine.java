package edu.campus.agent.server;

import java.lang.management.ManagementFactory;

/** What this PC has, so the server takes a fair share of it and no more. */
final class Machine {

    private Machine() {
    }

    /** The PC's memory in megabytes (8192 when it cannot be read). */
    static long memoryMb() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean m) {
                long bytes = m.getTotalMemorySize();
                if (bytes > 0) return bytes / (1024 * 1024);
            }
        } catch (RuntimeException | LinkageError e) {
            // not readable on this Java: assume an ordinary PC
        }
        return 8192;
    }
}

package edu.campus.print.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On the Xerox center's own PC the server is started by XeoGo Station, which
 * passes its process number (XEOGO_PARENT_PID). When the Station is gone
 * (closed, updated, crashed), this server must not stay behind on its own:
 * it would keep the port, and an updated Station would talk to an old
 * server. So it looks every few seconds whether the Station is still there,
 * and ends itself cleanly when it is not.
 *
 * In the cloud nothing sets XEOGO_PARENT_PID, and nothing happens here.
 */
@Component
public class ParentWatch {

    private static final Logger log = LoggerFactory.getLogger(ParentWatch.class);

    private final long parent;
    private final ConfigurableApplicationContext context;

    public ParentWatch(@Value("${XEOGO_PARENT_PID:0}") long parent, ConfigurableApplicationContext context) {
        this.parent = parent;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void watch() {
        if (parent <= 0) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                boolean there = ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false);
                if (!there) {
                    log.warn("XeoGo Station (process {}) is gone: this server ends too", parent);
                    try {
                        context.close();
                    } finally {
                        System.exit(0);
                    }
                    return;
                }
            }
        }, "station-watch");
        t.setDaemon(true);
        t.start();
        log.info("Started by XeoGo Station (process {}): ends when the Station does", parent);
    }
}

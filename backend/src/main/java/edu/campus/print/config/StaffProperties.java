package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * Free printing for college staff. The number of free pages and whether
 * colour is allowed live in the database (the Xerox center changes them on
 * its own screen); here is only what belongs to the server.
 *
 * @param timeZone     the month's free pages start again at midnight on the 1st, in this time zone
 * @param sessionDays  how long a device stays signed in without being used
 */
@ConfigurationProperties(prefix = "campus.staff")
public record StaffProperties(String timeZone, int sessionDays) {

    public StaffProperties {
        if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Kolkata";
        if (sessionDays <= 0) sessionDays = 90;
    }

    /** A time zone that cannot be read never stops the server: India's is used. */
    public ZoneId zone() {
        try {
            return ZoneId.of(timeZone.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}

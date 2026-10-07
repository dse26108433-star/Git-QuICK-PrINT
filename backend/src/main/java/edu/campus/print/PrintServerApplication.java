package edu.campus.print;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * XeoGo - the Xerox center print service.
 *
 * One Spring Boot application with clear internal modules:
 *
 *   orders/    what the student's phone or browser uses (no login)
 *   payment/   Razorpay (or demo mode while testing)
 *   counter/   the Xerox center staff screen
 *   agentapi/  the only thing the Xerox center PC talks to
 *   storage/   private Supabase Storage for the uploaded files
 *   schedule/  recovery, payment checks and clean-up
 *
 * There are no user accounts, so Spring's default login user is switched off.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class PrintServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrintServerApplication.class, args);
    }
}

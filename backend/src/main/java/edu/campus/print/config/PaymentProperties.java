package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mode = "demo"      no real money; a "Pay (demo)" button marks the order paid.
 *                    Only for testing on your own computer.
 * mode = "razorpay"  real payments (Razorpay test keys first, live keys later).
 */
@ConfigurationProperties(prefix = "campus.payment")
public record PaymentProperties(
        String mode,
        String razorpayKeyId,
        String razorpayKeySecret
) {
    public boolean isRazorpay() {
        return "razorpay".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}

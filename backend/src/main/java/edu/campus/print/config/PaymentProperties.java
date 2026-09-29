package edu.campus.print.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mode = "demo"      no real money; a "Pay (demo)" button marks the order paid.
 *                    Only for testing on your own computer.
 * mode = "upi"       CampusPay: students pay the Xerox center's own UPI ID with
 *                    any UPI app; bank messages or staff confirm each payment.
 *                    upiId: the UPI ID, or the text of the shop's UPI QR code.
 *                    upiAlertToken: the secret the phone that forwards bank SMS sends.
 * mode = "razorpay"  payments through Razorpay (test keys first, live keys later).
 */
@ConfigurationProperties(prefix = "campus.payment")
public record PaymentProperties(
        String mode,
        String razorpayKeyId,
        String razorpayKeySecret,
        String upiId,
        String upiName,
        String upiMerchantCode,
        String upiAlertToken,
        Integer upiMatchMinutes
) {
    public boolean isRazorpay() {
        return "razorpay".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public boolean isUpi() {
        return "upi".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    /** Bank messages can confirm payments by themselves (a long enough secret is set). */
    public boolean upiAlertsOn() {
        return upiAlertToken != null && upiAlertToken.trim().length() >= 24;
    }

    /** How long after opening the payment screen a bank message is matched by its amount alone. */
    public int matchMinutes() {
        return upiMatchMinutes == null || upiMatchMinutes < 5 || upiMatchMinutes > 24 * 60 ? 60 : upiMatchMinutes;
    }
}

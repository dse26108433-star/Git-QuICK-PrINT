package edu.campus.print.orders;

import edu.campus.print.domain.StaffAccount;

/**
 * Who is asking about an order.
 *
 *   key    the order's private key (X-Order-Key): a student's device, which
 *          made the order and is the only one that holds it
 *   staff  the college staff member who is signed in (X-Staff-Session), or null
 *
 * An ordinary order answers to its key. A staff order answers to its staff
 * ID alone, on any device that is signed in with it, and to nobody once that
 * ID is switched off or gets a new password.
 */
public record Caller(String key, StaffAccount staff) {

    public static Caller ofKey(String key) {
        return new Caller(key, null);
    }
}

package edu.campus.print.staff;

import edu.campus.print.domain.StaffAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The staff website and the staff app (college staff who print for free).
 *
 *   POST /staff/login   {"username", "password"}  -> a sign-in token + who they are and their free pages
 *   GET  /staff/me      X-Staff-Session           -> the same, fresh (and a new token when the old one ages)
 *   GET  /staff/orders  X-Staff-Session           -> what this ID sent to print lately, on every device
 *
 * Orders themselves use the student API (/orders/...) with the X-Staff-Session
 * header: an order made that way belongs to the staff ID, costs nothing, and
 * is sent to the printers with POST /orders/{id}/staff-print.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    public static final String SESSION = "X-Staff-Session";

    private final StaffService staff;

    public StaffController(StaffService staff) {
        this.staff = staff;
    }

    public record LoginRequest(@Size(max = 100) String username, @Size(max = 100) String password) {}

    /** token: only when the device should replace the one it has. */
    public record Me(StaffService.StaffView staff, String token, Long expiresInSeconds) {}

    @PostMapping("/login")
    public StaffService.Login login(@Valid @RequestBody(required = false) LoginRequest req) {
        return staff.login(req == null ? null : req.username(), req == null ? null : req.password());
    }

    @GetMapping("/me")
    public Me me(@RequestHeader(value = SESSION, required = false) String token) {
        StaffAccount a = staff.session(token);
        String fresh = staff.renewed(token, a);
        return new Me(staff.view(a), fresh, fresh == null ? null : staff.sessionSeconds());
    }

    @GetMapping("/orders")
    public List<StaffService.StaffOrder> orders(@RequestHeader(value = SESSION, required = false) String token) {
        return staff.orders(staff.session(token));
    }
}

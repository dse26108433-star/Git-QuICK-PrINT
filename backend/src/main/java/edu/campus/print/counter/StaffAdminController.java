package edu.campus.print.counter;

import edu.campus.print.staff.StaffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * College staff IDs, on the Xerox center's screen (the Station app, or
 * web/counter.html). Behind the counter sign-in like everything under
 * /api/v1/counter.
 *
 *   GET    /counter/staff                 the IDs, their free pages used this month, and the shop's numbers
 *   POST   /counter/staff                 a new ID: name (+ username, + its own number of pages) -> the password, once
 *   POST   /counter/staff/{id}/password   a new password for an ID -> the password, once
 *   PUT    /counter/staff/{id}            name, its own number of pages, switched on / off
 *   DELETE /counter/staff/{id}            take the ID off the list
 *   PUT    /counter/staff/settings        the usual free pages per month, and whether colour is free too
 */
@RestController
@RequestMapping("/api/v1/counter/staff")
public class StaffAdminController {

    private final StaffService staff;

    public StaffAdminController(StaffService staff) {
        this.staff = staff;
    }

    public record NewStaff(@Size(max = 100) String name, @Size(max = 100) String username, Integer monthlyPages) {}

    /** usualPages = true: back to the shop's usual number of free pages. */
    public record StaffChange(@Size(max = 100) String name, Integer monthlyPages, Boolean usualPages, Boolean active) {}

    public record StaffSettings(Integer monthlyPages, Boolean colorAllowed) {}

    @GetMapping
    public StaffService.Overview list() {
        return staff.overview();
    }

    @PostMapping
    public StaffService.NewPassword create(@Valid @RequestBody(required = false) NewStaff f) {
        return staff.create(f == null ? null : f.name(), f == null ? null : f.username(),
                f == null ? null : f.monthlyPages());
    }

    @PostMapping("/{id}/password")
    public StaffService.NewPassword newPassword(@PathVariable UUID id) {
        return staff.resetPassword(id);
    }

    @PutMapping("/{id}")
    public StaffService.AccountView change(@PathVariable UUID id, @Valid @RequestBody StaffChange f) {
        return staff.update(id, f.name(), f.monthlyPages(), f.usualPages(), f.active());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable UUID id) {
        staff.remove(id);
        return Map.of("ok", true);
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestBody StaffSettings f) {
        staff.saveSettings(f.monthlyPages(), f.colorAllowed());
        return Map.of("ok", true);
    }
}

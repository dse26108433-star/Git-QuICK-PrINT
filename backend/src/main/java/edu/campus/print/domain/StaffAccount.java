package edu.campus.print.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A college staff member who prints for free (table "staff_accounts"): a
 * username and a password, made at the Xerox center, and nothing else. Each
 * ID has a number of free pages per month (the shop's usual number, or its
 * own).
 *
 * Only the hash of the password is kept. The counters for wrong passwords
 * are written by StaffAccountRepository in single SQL statements, never
 * through this object.
 */
@Entity
@Table(name = "staff_accounts")
@DynamicUpdate
public class StaffAccount implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column(nullable = false) private String username;
    @Column(nullable = false) private String name;
    @Column(name = "password_hash", nullable = false)   private String passwordHash;
    @Column(name = "password_set_at", nullable = false) private Instant passwordSetAt;
    /** Free pages per month for this ID; null = the shop's usual number. */
    @Column(name = "monthly_pages") private Integer monthlyPages;
    @Column(nullable = false)       private boolean active = true;
    @Column(name = "removed_at")    private Instant removedAt;

    @Column(name = "failed_logins", insertable = false, updatable = false) private int failedLogins;
    @Column(name = "locked_until", insertable = false, updatable = false)  private Instant lockedUntil;
    @Column(name = "last_login_at", insertable = false, updatable = false) private Instant lastLoginAt;
    @Column(name = "created_at", insertable = false, updatable = false)    private Instant createdAt;

    public static StaffAccount create(String username, String name, String passwordHash, Integer monthlyPages) {
        StaffAccount a = new StaffAccount();
        a.id = UUID.randomUUID();
        a.isNew = true;
        a.username = username;
        a.name = name;
        a.monthlyPages = monthlyPages;
        a.setPassword(passwordHash);
        return a;
    }

    @Override public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    /** A new password: every device signed in with the old one is signed out (see StaffSessions). */
    public void setPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        // Whole milliseconds, so the stamp reads back from the database exactly as it was written.
        this.passwordSetAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** May sign in and print. */
    public boolean isUsable() { return active && removedAt == null; }

    @Override public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getPasswordSetAt() { return passwordSetAt; }
    public Integer getMonthlyPages() { return monthlyPages; }
    public void setMonthlyPages(Integer v) { this.monthlyPages = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant v) { this.removedAt = v; }
    public int getFailedLogins() { return failedLogins; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}

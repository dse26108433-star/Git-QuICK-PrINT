package edu.campus.print.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The Xerox center PC. Enrolled with enroll_agent() in seed.sql. */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private UUID id;

    @Column(nullable = false) private String name;
    @Column(name = "secret_hash", nullable = false) private String secretHash;
    @Column(nullable = false) private boolean revoked;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "agent_version") private String agentVersion;
    @Column(name = "host_name") private String hostName;

    public boolean isOnline(Duration within) {
        return !revoked && lastSeenAt != null && lastSeenAt.isAfter(Instant.now().minus(within));
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSecretHash() { return secretHash; }
    public boolean isRevoked() { return revoked; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getAgentVersion() { return agentVersion; }
    public String getHostName() { return hostName; }
}

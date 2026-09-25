package edu.campus.print.security;

import java.util.UUID;

/** The authenticated Xerox center PC. */
public record AgentPrincipal(UUID agentId) {
}

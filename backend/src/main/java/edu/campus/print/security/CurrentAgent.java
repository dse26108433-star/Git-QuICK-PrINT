package edu.campus.print.security;

import edu.campus.print.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** The Xerox center PC making the current request. */
public final class CurrentAgent {

    private CurrentAgent() {
    }

    public static UUID id() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AgentPrincipal p) {
            return p.agentId();
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_AN_AGENT", "Agent authentication required.");
    }
}

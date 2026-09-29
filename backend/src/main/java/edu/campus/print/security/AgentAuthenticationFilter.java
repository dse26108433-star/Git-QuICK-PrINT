package edu.campus.print.security;

import edu.campus.print.domain.Agent;
import edu.campus.print.repo.AgentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks the PC's bearer token, and on every request that the PC is still
 * enrolled and not revoked, so revoking takes effect immediately.
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    private final AgentTokenService tokens;
    private final AgentRepository agents;

    public AgentAuthenticationFilter(AgentTokenService tokens, AgentRepository agents) {
        this.tokens = tokens;
        this.agents = agents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            UUID agentId = tokens.verify(header.substring(7).trim());
            if (agentId != null) {
                Optional<Agent> agent = agents.findById(agentId);
                if (agent.isPresent() && !agent.get().isRevoked()) {
                    var auth = new UsernamePasswordAuthenticationToken(new AgentPrincipal(agentId), null,
                            List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/agent/v1/token".equals(pathOf(request));
    }

    /** The path without the context path, the same in a real server and in tests. */
    private static String pathOf(jakarta.servlet.http.HttpServletRequest r) {
        String uri = r.getRequestURI();
        String ctx = r.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}

package edu.campus.print.repo;

import edu.campus.print.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByRevokedFalse();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update agents set last_seen_at = now(), agent_version = :version, host_name = :host
             where id = :id
            """, nativeQuery = true)
    int touch(@Param("id") UUID id, @Param("version") String version, @Param("host") String host);
}

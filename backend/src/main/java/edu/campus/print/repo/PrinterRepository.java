package edu.campus.print.repo;

import edu.campus.print.domain.Printer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {

    List<Printer> findAllByOrderByName();

    List<Printer> findByEnabledTrueOrderByName();

    /** Printers this PC drives: its own, plus any not tied to a particular PC. */
    @Query("select p from Printer p where p.agentId is null or p.agentId = :agentId order by p.name")
    List<Printer> findForAgent(@Param("agentId") UUID agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update printers set status = :status, status_detail = nullif(:detail, ''), status_at = now()
             where id = :id and (agent_id is null or agent_id = :agentId)
            """, nativeQuery = true)
    int reportStatus(@Param("id") UUID id, @Param("agentId") UUID agentId,
                     @Param("status") String status, @Param("detail") String detail);
}

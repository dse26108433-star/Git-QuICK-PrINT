package edu.campus.print.repo;

import edu.campus.print.domain.StaffAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** College staff IDs (free printing). Removed IDs stay in the table for their orders, but are never found here. */
public interface StaffAccountRepository extends JpaRepository<StaffAccount, UUID> {

    @Query(value = "select * from staff_accounts where lower(username) = lower(:username) and removed_at is null",
            nativeQuery = true)
    Optional<StaffAccount> findLive(@Param("username") String username);

    @Query(value = "select * from staff_accounts where removed_at is null order by lower(name), lower(username)",
            nativeQuery = true)
    List<StaffAccount> findAllLive();

    @Query(value = "select count(*) from staff_accounts where removed_at is null", nativeQuery = true)
    long countLive();

    /**
     * A wrong password. After :max in a row the ID has to wait :minutes; it
     * stays one wrong password away from waiting again until someone signs in.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            update staff_accounts
               set failed_logins = failed_logins + 1,
                   locked_until  = case when failed_logins + 1 >= :max
                                        then now() + make_interval(mins => :minutes) else locked_until end
             where id = :id
            """, nativeQuery = true)
    int wrongPassword(@Param("id") UUID id, @Param("max") int max, @Param("minutes") int minutes);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update staff_accounts set failed_logins = 0, locked_until = null, last_login_at = now() where id = :id",
            nativeQuery = true)
    int signedIn(@Param("id") UUID id);

    /** The free pages this ID used since :from (staff_pages_used in db/setup.sql). */
    @Query(value = "select staff_pages_used(:id, :from)", nativeQuery = true)
    int pagesUsed(@Param("id") UUID id, @Param("from") Instant from);
}

// @find: processed events repository, event dedupe, delete old processed events, cleanup, ProcessedEvents
// @what: Spring Data repository for ProcessedEvent rows with a cleanup query.
// @flow: Used by event listeners and a cleanup job.
// @find: processed events repository, event dedupe, delete old processed events, cleanup, ProcessedEvents
// @what: Spring Data repository for ProcessedEvent rows with a cleanup query.
// @flow: Used by event listeners and a cleanup job.
package os.aiworkforce.orchestrator.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface ProcessedEvents extends JpaRepository<ProcessedEvent, String> {

    // @find: delete old processed events, cleanup job
    // @find: delete old processed events, cleanup job
    /**
     * Removes records older than the idempotency window, so the table does not grow forever.
     *
     * <p>Transactional here because its caller, the nightly sweep, holds no transaction of its own.
     */
    @Transactional
    @Modifying
    @Query("delete from ProcessedEvent e where e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}

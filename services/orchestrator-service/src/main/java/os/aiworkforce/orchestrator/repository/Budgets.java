// @find: budgets repository, find budget for workspace, create budget row if absent, insert default budget, Budgets
// @what: Spring Data repository for the per-workspace Budget row.
// @flow: Used by the budget service and the run engine cap checks.
// @find: budgets repository, find budget for workspace, create budget row if absent, insert default budget, Budgets
// @what: Spring Data repository for the per-workspace Budget row.
// @flow: Used by the budget service and the run engine cap checks.
package os.aiworkforce.orchestrator.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.Budget;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Budgets extends JpaRepository<Budget, UUID> {

    // @find: find budget for workspace
    // @find: find budget for workspace
    @Query("select b from Budget b where b.id = :orgId")
    Optional<Budget> findForOrg(@Param("orgId") UUID orgId);

    // @find: create default budget row if missing
    // @find: create default budget row if missing
    /**
     * Creates the workspace's row if it has none, and does nothing if it has.
     *
     * <p>An atomic upsert rather than "find, then insert": two admins saving a first cap at the
     * same moment would otherwise both insert, and the second would fail on the primary key. Every
     * other column takes its default - no caps, and stop at a cap.
     */
    @Modifying
    @Query(value = "insert into budgets (id) values (:orgId) on conflict (id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("orgId") UUID orgId);
}

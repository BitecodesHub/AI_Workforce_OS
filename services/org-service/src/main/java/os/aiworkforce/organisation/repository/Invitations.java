package os.aiworkforce.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.organisation.domain.Invitation;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Invitations extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<Invitation> findByOrgIdAndEmailIgnoreCaseAndStatus(UUID orgId, String email, String status);

    /** One invitation, only if it belongs to the workspace named in the path. */
    Optional<Invitation> findByIdAndOrgId(UUID id, UUID orgId);
}

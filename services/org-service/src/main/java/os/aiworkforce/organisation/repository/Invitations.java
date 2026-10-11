// @find: invitations repository, find invitation by token hash, pending invitations, list invitations, invitation lookup, Invitations JPA repository
// @what: Spring Data repository for workspace invitations.
// @flow: Used by InvitationService.
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

/**
 * Workspace invitations. Used by {@code InvitationService}; Spring Data generates the
 * implementation. Lookups by workspace keep one workspace's invitations apart from another's.
 */
public interface Invitations extends JpaRepository<Invitation, UUID> {

    /**
     * The invitation whose stored token hash matches. The raw token is never stored, so the caller
     * hashes the presented token first. Used when a person redeems an invitation, before any workspace is known.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /** All invitations of a workspace, newest first, for the administration list. */
    List<Invitation> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /**
     * The invitation for an address in a workspace with the given status, ignoring letter case. Used
     * to find an existing pending invitation so a second one is not created for the same person.
     */
    Optional<Invitation> findByOrgIdAndEmailIgnoreCaseAndStatus(UUID orgId, String email, String status);

    /** One invitation, only if it belongs to the workspace named in the path. */
    Optional<Invitation> findByIdAndOrgId(UUID id, UUID orgId);
}

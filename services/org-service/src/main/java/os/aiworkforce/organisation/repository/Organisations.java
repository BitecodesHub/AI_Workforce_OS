// @find: organisations repository, find workspace by slug, workspace lookup, find organisation by id, Organisations JPA repository
// @what: Spring Data repository for workspaces, with a case-insensitive slug lookup.
// @flow: Used by WorkspaceController, InternalWorkspaceController and DemoDataSeeder.
package os.aiworkforce.organisation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.organisation.domain.Organisation;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Organisations extends JpaRepository<Organisation, UUID> {

    // @find: find workspace by slug, unique slug check, case-insensitive
    @Query("select o from Organisation o where lower(o.slug) = lower(:slug)")
    Optional<Organisation> findBySlug(@Param("slug") String slug);
}

// @find: Spring Data repository for permission code rows.
// @what: permissions, permission catalogue, permission codes, Permissions repository
package os.aiworkforce.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.identity.domain.PermissionRecord;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

/**
 * Spring Data repository for {@link PermissionRecord} rows, keyed by permission code.
 *
 * <p>Used only by {@code PermissionSeeder}, which reads and writes the rows at startup. Only the
 * inherited finders are used. The console's permission list is served from the build's registry,
 * not from this table.
 */
public interface Permissions extends JpaRepository<PermissionRecord, String> {}

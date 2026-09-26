package os.aiworkforce.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.organisation.domain.Credential;
import os.aiworkforce.organisation.domain.Organisation;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Credentials extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByOrgIdAndRef(UUID orgId, String ref);

    List<Credential> findByOrgIdOrderByRef(UUID orgId);

    boolean existsByOrgIdAndRef(UUID orgId, String ref);
}

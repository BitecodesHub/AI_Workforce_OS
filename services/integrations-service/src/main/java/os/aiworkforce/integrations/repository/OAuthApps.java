package os.aiworkforce.integrations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.integrations.domain.OAuthApp;

public interface OAuthApps extends JpaRepository<OAuthApp, UUID> {

    List<OAuthApp> findByOrgId(UUID orgId);

    Optional<OAuthApp> findByOrgIdAndProvider(UUID orgId, String provider);
}

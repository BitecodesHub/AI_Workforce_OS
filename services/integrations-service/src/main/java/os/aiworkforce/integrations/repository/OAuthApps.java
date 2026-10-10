// @find: oauth apps repository, find oauth app by provider, database access
// @what: Spring Data repository for the saved OAuth apps.
// @flow: Used by OAuthService
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

// @find: oauth apps repository, find oauth app by provider, database access
// @what: Spring Data repository for the saved OAuth apps.
// @flow: Used by OAuthService
package os.aiworkforce.integrations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import os.aiworkforce.integrations.domain.OAuthApp;

/**
 * Spring Data repository for {@link OAuthApp}, the client registration a workspace saves per provider.
 *
 * <p>Used by {@code OAuthService} to save an app, to read it when a sign-in starts, completes or
 * refreshes, and to report which providers are configured. Derived queries only; no hand-written SQL.
 */
public interface OAuthApps extends JpaRepository<OAuthApp, UUID> {

    /**
     * Every app saved by one workspace, one per provider.
     *
     * @param orgId the workspace
     * @return the saved apps, empty when none has been registered
     */
    List<OAuthApp> findByOrgId(UUID orgId);

    /**
     * The app a workspace saved for one provider.
     *
     * @param orgId the workspace
     * @param provider the provider id, for example {@code google}, {@code microsoft} or {@code salesforce}
     * @return the app, or empty when the workspace has not registered one for that provider
     */
    Optional<OAuthApp> findByOrgIdAndProvider(UUID orgId, String provider);
}

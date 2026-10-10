// @find: oauth app entity, oauth_apps table, client id, client secret ref, provider settings, tenant, login domain, google, microsoft, salesforce, register oauth app, set up the app
// @what: Database entity for the OAuth app an administrator registers per provider (client id, secret reference, extra settings).
// @flow: Used by OAuthService.saveApp
package os.aiworkforce.integrations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * The OAuth app a workspace registered with one provider.
 *
 * <p>The client secret is held encrypted and is never returned by any endpoint. One app serves
 * every connector of its provider, so the Google app covers Gmail, Calendar, Drive and Sheets.
 */
@Entity
@Table(name = "oauth_apps")
public class OAuthApp extends OrgScopedEntity {

    @Column(nullable = false)
    private String provider;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "client_secret_ref", nullable = false, columnDefinition = "text")
    private String clientSecretRef;

    /** Non-secret settings as JSON: the Microsoft tenant, the Salesforce login domain. */
    @Column(nullable = false, columnDefinition = "text")
    private String settings = "{}";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecretRef() {
        return clientSecretRef;
    }

    public void setClientSecretRef(String clientSecretRef) {
        this.clientSecretRef = clientSecretRef;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }
}

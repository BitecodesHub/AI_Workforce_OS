// @find: oauth setup, register oauth app, set up the app step, client id client secret, google, microsoft, salesforce, scopes, app steps, tenant, login domain, gmail, calendar, drive, sheets, outlook, teams
// @what: Record telling an administrator how to register an OAuth app with a provider before anyone connects.
// @flow: Built from OAuthProviders by ConnectorCatalog.oauth
package os.aiworkforce.mcp.catalog;

import java.util.List;

/**
 * What an administrator needs to register an OAuth app with a provider, before anyone connects.
 *
 * @param provider the provider id, shared by the connectors that use one app: {@code google},
 *     {@code microsoft} or {@code salesforce}
 * @param providerLabel the provider as a person says it, for example "Google"
 * @param appFields extra boxes in the "Set up the app" step beyond client id and secret, for
 *     example the Microsoft tenant or the Salesforce login domain
 * @param scopes the permissions this connector asks the provider for, as the provider names them
 * @param appSteps plain sentences for registering the app, in order
 * @param appDocsUrl the provider's own page on registering an app
 */
public record OAuthSetup(
        String provider,
        String providerLabel,
        List<CredentialField> appFields,
        List<String> scopes,
        List<String> appSteps,
        String appDocsUrl) {

    public OAuthSetup {
        appFields = appFields == null ? List.of() : List.copyOf(appFields);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        appSteps = appSteps == null ? List.of() : List.copyOf(appSteps);
    }
}

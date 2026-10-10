// @find: connector info, connector description, connector category, auth type token oauth url none, live available, token label, setup steps, docs url, credential fields, oauth setup, connector card
// @what: Record describing one connector as the console shows it before and after connecting.
// @flow: Built in ConnectorCatalog; serialised by IntegrationController
package os.aiworkforce.mcp.catalog;

import java.util.List;
import java.util.Objects;

/**
 * What the console says about one connector, before and after it is connected.
 *
 * @param server the server name tools are qualified with, for example {@code github}
 * @param displayName the product name a person recognises
 * @param category one of communication, productivity, engineering, sales, support, files,
 *     finance, automation or voice
 * @param description one plain sentence on what agents can do with it
 * @param authType {@code token}, {@code oauth}, {@code url} or {@code none}
 * @param liveAvailable whether a real account can be connected today; when false the connector
 *     works with practice data only
 * @param tokenLabel what the administrator pastes, for example "Personal access token"
 * @param setupSteps plain sentences telling an administrator where to get it
 * @param docsUrl the provider's own page on creating the token, when there is one
 * @param credentialFields the boxes the connect dialog shows for a {@code token} connector; one
 *     secret box for a single token, several for a connector such as Jira that needs a site, an
 *     email and a token. Several are stored together as one encrypted JSON value.
 * @param oauth how to register the OAuth app, for an {@code oauth} connector; otherwise null
 */
public record ConnectorInfo(
        String server,
        String displayName,
        String category,
        String description,
        String authType,
        boolean liveAvailable,
        String tokenLabel,
        List<String> setupSteps,
        String docsUrl,
        List<CredentialField> credentialFields,
        OAuthSetup oauth) {

    /** A connector with no field list of its own: the dialog shows one secret box. */
    public ConnectorInfo(
            String server,
            String displayName,
            String category,
            String description,
            String authType,
            boolean liveAvailable,
            String tokenLabel,
            List<String> setupSteps,
            String docsUrl) {
        this(server, displayName, category, description, authType, liveAvailable, tokenLabel, setupSteps, docsUrl,
                List.of(), null);
    }

    public static final List<String> CATEGORIES = List.of(
            "communication", "productivity", "engineering", "sales", "support", "files", "finance", "automation", "voice");
    public static final List<String> AUTH_TYPES = List.of("token", "oauth", "url", "none");

    public ConnectorInfo {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(displayName, "displayName");
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Unknown connector category: " + category);
        }
        if (!AUTH_TYPES.contains(authType)) {
            throw new IllegalArgumentException("Unknown connector auth type: " + authType);
        }
        setupSteps = setupSteps == null ? List.of() : List.copyOf(setupSteps);
        credentialFields = credentialFields == null ? List.of() : List.copyOf(credentialFields);
    }
}

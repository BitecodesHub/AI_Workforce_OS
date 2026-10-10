// @find: credential field, connect dialog box, secret box, site email token, jira site, zendesk subdomain, masked input, add connector dialog, token field
// @what: Record for one input box in the connect dialog, secret or plain.
// @flow: Used by ConnectorCatalog entries such as Jira, Confluence, Zendesk and Zoom
package os.aiworkforce.mcp.catalog;

/**
 * One box in the connect dialog.
 *
 * @param key the name the value is sent and stored under, for example {@code site}
 * @param label what the person sees above the box
 * @param secret whether the value is masked while typed and never shown again
 * @param placeholder an example of the shape expected, or null
 * @param help one plain sentence on where to find the value, or null
 */
public record CredentialField(String key, String label, boolean secret, String placeholder, String help) {

    public static CredentialField plain(String key, String label, String placeholder, String help) {
        return new CredentialField(key, label, false, placeholder, help);
    }

    public static CredentialField secret(String key, String label, String help) {
        return new CredentialField(key, label, true, null, help);
    }
}

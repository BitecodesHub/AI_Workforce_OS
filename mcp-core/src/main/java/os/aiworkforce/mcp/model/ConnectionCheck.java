// @find: connection check result, test connection result, ok message account label, connection test, connected account, token accepted, connect dialog result
// @what: Record holding the outcome of checking a stored credential against its provider.
// @flow: Returned by McpServerAdapter.check and shown by IntegrationController test endpoint
package os.aiworkforce.mcp.model;

/**
 * What checking a stored credential against its provider found.
 *
 * <p>The message is written for the administrator who pasted the token, so it says what to do
 * next rather than what the provider's status code was. It never contains the credential.
 *
 * @param ok whether the provider accepted the credential
 * @param message one plain sentence describing the result
 * @param accountLabel which account the credential belongs to, when the provider says
 */
public record ConnectionCheck(boolean ok, String message, String accountLabel) {

    /**
     * Builds a successful result. The message names the account when the provider reported one.
     * Called by adapters from {@code check} and by the default in {@code McpServerAdapter}.
     */
    public static ConnectionCheck passed(String accountLabel) {
        return new ConnectionCheck(
                true, accountLabel == null ? "Connected." : "Connected as " + accountLabel + ".", accountLabel);
    }

    /** Builds a failed result carrying a plain sentence telling the administrator what to do next. */
    public static ConnectionCheck failed(String message) {
        return new ConnectionCheck(false, message, null);
    }
}

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

    public static ConnectionCheck passed(String accountLabel) {
        return new ConnectionCheck(
                true, accountLabel == null ? "Connected." : "Connected as " + accountLabel + ".", accountLabel);
    }

    public static ConnectionCheck failed(String message) {
        return new ConnectionCheck(false, message, null);
    }
}

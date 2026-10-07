package os.aiworkforce.mcp.oauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import os.aiworkforce.mcp.catalog.CredentialField;

/**
 * One OAuth 2.0 provider: where to send a person, where to trade the code, and what each connector
 * asks for. Pure data. The flow itself (state, PKCE, storage, refresh) lives in the integrations
 * service so that adapters never see a client secret or a refresh token.
 *
 * <p>URLs may contain {@code {tenant}} or {@code {loginDomain}}, filled from the workspace's saved
 * app settings after they have passed the checks in {@link os.aiworkforce.mcp.live.Hosts}.
 *
 * @param id {@code google}, {@code microsoft} or {@code salesforce}
 * @param label the provider as a person says it
 * @param authorizeUrl the consent screen
 * @param tokenUrl the code and refresh endpoint
 * @param profileUrl a call that names the signed-in account, answered with the new access token
 * @param appFields extra app settings beyond client id and secret
 * @param authorizeParams extra query parameters the consent request needs (for example offline access)
 * @param scopesByServer the provider scopes each connector asks for
 * @param uncheckedScopes scopes that are asked for but not reported back reliably, so they are never
 *     shown as "Permissions missing"
 * @param appSteps plain steps for registering the app
 * @param appDocsUrl the provider's own page on registering an app
 */
public record OAuthProvider(
        String id,
        String label,
        String authorizeUrl,
        String tokenUrl,
        String profileUrl,
        List<CredentialField> appFields,
        Map<String, String> authorizeParams,
        Map<String, List<String>> scopesByServer,
        Set<String> uncheckedScopes,
        List<String> appSteps,
        String appDocsUrl) {

    public OAuthProvider {
        appFields = List.copyOf(appFields);
        authorizeParams = Map.copyOf(authorizeParams);
        scopesByServer = Map.copyOf(scopesByServer);
        uncheckedScopes = Set.copyOf(uncheckedScopes);
        appSteps = List.copyOf(appSteps);
    }

    /** Every scope a connector asks for, including the ones that are not checked afterwards. */
    public List<String> scopesFor(String server) {
        return scopesByServer.getOrDefault(server, List.of());
    }

    /** The scopes whose absence from the grant means a permission is missing. */
    public List<String> checkedScopes(String server) {
        List<String> checked = new ArrayList<>();
        for (String scope : scopesFor(server)) {
            if (!uncheckedScopes.contains(scope)) {
                checked.add(scope);
            }
        }
        return checked;
    }

    /** Fills {tenant} and {loginDomain} from validated settings. */
    public static String fill(String template, Map<String, String> settings) {
        String result = template;
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** A copy with the endpoints replaced, for tests that stand in for the provider. */
    public OAuthProvider withEndpoints(String authorize, String token, String profile) {
        return new OAuthProvider(id, label, authorize, token, profile, appFields, authorizeParams, scopesByServer,
                uncheckedScopes, appSteps, appDocsUrl);
    }

    /** The settings, checked and normalised, or an IllegalArgumentException a person can act on. */
    public Map<String, String> validatedSettings(Map<String, String> given) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (CredentialField field : appFields) {
            String value = given == null ? null : given.get(field.key());
            if (value == null || value.isBlank()) {
                throw new SettingProblem(field.key(), "Enter the " + field.label().toLowerCase() + ".");
            }
            try {
                clean.put(
                        field.key(),
                        switch (field.key()) {
                            case "tenant" -> os.aiworkforce.mcp.live.Hosts.microsoftTenant(value);
                            case "loginDomain" -> os.aiworkforce.mcp.live.Hosts.salesforceLoginDomain(value);
                            default -> value.strip();
                        });
            } catch (SettingProblem e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new SettingProblem(field.key(), e.getMessage());
            }
        }
        return clean;
    }

    /** A setting that cannot be used, naming the setting's key so a form can show it beside that box. */
    public static final class SettingProblem extends IllegalArgumentException {

        private final String field;

        public SettingProblem(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}

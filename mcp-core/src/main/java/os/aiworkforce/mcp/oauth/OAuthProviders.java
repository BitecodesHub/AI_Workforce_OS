// @find: oauth providers, google, microsoft, salesforce, scopes per connector, gmail scopes, calendar scopes, drive scopes, sheets scopes, outlook scopes, teams scopes, consent screen permissions, offline access refresh token
// @what: Defines the three OAuth providers and exactly which permissions each connector asks for.
// @flow: Read by ConnectorCatalog and OAuthService
package os.aiworkforce.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import os.aiworkforce.mcp.catalog.CredentialField;

/**
 * The three OAuth providers, and the permissions each connector asks for.
 *
 * <p>The scope lists are the single place that says what a connector needs from its provider. They
 * line up with the tool definitions in the sandbox registry: a tool that sends mail needs a send
 * permission here, and a read-only agent never gets a consent screen that offers more than the
 * connector's own tools use.
 */
public final class OAuthProviders {

    public static final String GOOGLE = "google";
    public static final String MICROSOFT = "microsoft";
    public static final String SALESFORCE = "salesforce";

    private static final String G = "https://www.googleapis.com/auth/";

    private static final Map<String, OAuthProvider> PROVIDERS = new LinkedHashMap<>();
    private static final Map<String, String> PROVIDER_OF_SERVER = new LinkedHashMap<>();

    static {
        Map<String, List<String>> google = new LinkedHashMap<>();
        google.put("gmail", List.of("openid", "email", G + "gmail.readonly", G + "gmail.compose", G + "gmail.send"));
        google.put("calendar", List.of("openid", "email", G + "calendar.readonly", G + "calendar.events"));
        google.put("drive", List.of("openid", "email", G + "drive.readonly", G + "drive.file"));
        google.put("sheets", List.of("openid", "email", G + "spreadsheets", G + "drive.readonly"));
        register(
                new OAuthProvider(
                        GOOGLE,
                        "Google",
                        "https://accounts.google.com/o/oauth2/v2/auth",
                        "https://oauth2.googleapis.com/token",
                        "https://openidconnect.googleapis.com/v1/userinfo",
                        List.of(),
                        // Offline access and a forced consent screen are what make Google hand back a refresh token.
                        Map.of("access_type", "offline", "prompt", "consent", "include_granted_scopes", "false"),
                        google,
                        Set.of("openid", "email"),
                        List.of(
                                "Open console.cloud.google.com, create a project (or pick one), and turn on the API for each Google connector you want: Gmail API, Google Calendar API, Google Drive API and Google Sheets API.",
                                "Under APIs and Services, open OAuth consent screen. Choose Internal for a Google Workspace company, or External and add your own address as a test user.",
                                "Add the permissions listed below under Data Access (scopes).",
                                "Under Credentials, choose Create credentials, then OAuth client ID, and pick Web application.",
                                "Add the redirect address shown here under Authorised redirect URIs, then create the client.",
                                "Copy the client ID and client secret into the boxes here."),
                        "https://developers.google.com/identity/protocols/oauth2/web-server"));

        Map<String, List<String>> microsoft = new LinkedHashMap<>();
        microsoft.put("outlook", List.of("offline_access", "User.Read", "Mail.Read", "Mail.ReadWrite", "Mail.Send", "Calendars.Read"));
        microsoft.put(
                "teams",
                List.of("offline_access", "User.Read", "Team.ReadBasic.All", "Channel.ReadBasic.All",
                        "ChannelMessage.Read.All", "ChannelMessage.Send"));
        register(
                new OAuthProvider(
                        MICROSOFT,
                        "Microsoft",
                        "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
                        "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
                        "https://graph.microsoft.com/v1.0/me",
                        List.of(CredentialField.plain(
                                "tenant",
                                "Directory (tenant) ID",
                                "00000000-0000-0000-0000-000000000000 or acme.onmicrosoft.com",
                                "Shown on the app's Overview page in Microsoft Entra. Use common to allow any Microsoft account.")),
                        Map.of("response_mode", "query"),
                        microsoft,
                        Set.of("offline_access", "User.Read"),
                        List.of(
                                "Open entra.microsoft.com, go to Identity, then Applications, then App registrations, and choose New registration.",
                                "Name it AI Workforce OS. Under Redirect URI choose Web and paste the redirect address shown here.",
                                "After it is created, copy the Application (client) ID and the Directory (tenant) ID from the Overview page.",
                                "Under Certificates and secrets, create a new client secret and copy its Value straight away; Microsoft shows it once.",
                                "Under API permissions, add the Microsoft Graph delegated permissions listed below. Teams channel messages need an administrator to choose Grant admin consent.",
                                "Paste the client ID, the tenant ID and the secret into the boxes here."),
                        "https://learn.microsoft.com/entra/identity-platform/quickstart-register-app"));

        Map<String, List<String>> salesforce = new LinkedHashMap<>();
        salesforce.put("salesforce", List.of("api", "refresh_token"));
        register(
                new OAuthProvider(
                        SALESFORCE,
                        "Salesforce",
                        "https://{loginDomain}/services/oauth2/authorize",
                        "https://{loginDomain}/services/oauth2/token",
                        "https://{loginDomain}/services/oauth2/userinfo",
                        List.of(CredentialField.plain(
                                "loginDomain",
                                "Login domain",
                                "login.salesforce.com or acme.my.salesforce.com",
                                "Use login.salesforce.com for production, test.salesforce.com for a sandbox, or your My Domain.")),
                        Map.of(),
                        salesforce,
                        Set.of("refresh_token"),
                        List.of(
                                "In Salesforce, open Setup, search for App Manager, and choose New Connected App (or New External Client App).",
                                "Turn on Enable OAuth Settings and paste the redirect address shown here as the Callback URL.",
                                "Add the OAuth scopes listed below, and turn on Require Proof Key for Code Exchange (PKCE).",
                                "Save, wait a few minutes, then open Manage Consumer Details and copy the Consumer Key and Consumer Secret.",
                                "Paste them here as the client ID and client secret, with the login domain you use to sign in."),
                        "https://help.salesforce.com/s/articleView?id=sf.connected_app_create_api_integration.htm"));

        for (String server : List.of("gmail", "calendar", "drive", "sheets")) {
            PROVIDER_OF_SERVER.put(server, GOOGLE);
        }
        PROVIDER_OF_SERVER.put("outlook", MICROSOFT);
        PROVIDER_OF_SERVER.put("teams", MICROSOFT);
        PROVIDER_OF_SERVER.put("salesforce", SALESFORCE);
    }

    private OAuthProviders() {}

    private static void register(OAuthProvider provider) {
        PROVIDERS.put(provider.id(), provider);
    }

    public static Optional<OAuthProvider> byId(String id) {
        return Optional.ofNullable(PROVIDERS.get(id));
    }

    // @find: provider for a connector, which oauth provider does gmail use
    /** The provider a connector signs in through, or empty when it does not use OAuth. */
    public static Optional<OAuthProvider> forServer(String server) {
        return Optional.ofNullable(PROVIDER_OF_SERVER.get(server)).map(PROVIDERS::get);
    }

    public static Map<String, OAuthProvider> all() {
        return Map.copyOf(PROVIDERS);
    }
}

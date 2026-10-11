// @find: connector catalog, connectors list, integrations page, add connector dialog, available connectors, connector categories, setup steps, token label, credential fields, oauth connectors, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook, voice, twilio, phone numbers, web browser, phone calls, built in, live available, GET /api/integrations/connectors
// @what: The data behind the Integrations page: every connector, its category, plain description, how to connect it and whether a live adapter exists.
// @flow: Read by ConnectorService and IntegrationController; checked against registered servers by ConnectorCatalogTest
package os.aiworkforce.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import os.aiworkforce.mcp.oauth.OAuthProvider;
import os.aiworkforce.mcp.oauth.OAuthProviders;

/**
 * What the console says about every connector, defined as data beside the tool definitions.
 *
 * <p>Honest by construction: {@code liveAvailable} is true only for the connectors that have a
 * live adapter. Every connector except the built-in ones (Voice notes, Web browser, Phone calls) has
 * one; those have nothing to connect. A test keeps
 * this list and the registered servers in step.
 */
@Component
public class ConnectorCatalog {

    private final Map<String, ConnectorInfo> connectors = new LinkedHashMap<>();

    /**
     * Builds the whole catalog in three groups: token connectors, token connectors with several
     * fields in the connect dialog, and OAuth connectors. Runs once when the bean (or the static copy in
     * {@code ToolLabels}) is created. Side effects: none beyond filling the in-memory map. Throws
     * {@code IllegalStateException} if a server is listed twice, which fails startup rather than hiding
     * a duplicate.
     */
    public ConnectorCatalog() {
        // ---- Live with a token ---------------------------------------------------------------
        add(new ConnectorInfo(
                "github",
                "GitHub",
                "engineering",
                "Work with repositories, branches, issues, pull requests and files in GitHub.",
                "token",
                true,
                "Personal access token",
                List.of(
                        "In GitHub, open Settings, then Developer settings, then Personal access tokens, then Fine-grained tokens.",
                        "Choose Generate new token and pick the repositories agents may use.",
                        "Under Repository permissions, allow Issues, Pull requests and Contents (read and write).",
                        "To let agents create repositories, also allow Administration (read and write) for all repositories.",
                        "Generate the token, copy it, and paste it here. GitHub shows it only once."),
                "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens"));
        add(new ConnectorInfo(
                "slack",
                "Slack",
                "communication",
                "Read channels and post messages in Slack.",
                "token",
                true,
                "Bot token (starts with xoxb-)",
                List.of(
                        "Go to api.slack.com/apps and create an app for your Slack workspace.",
                        "Under OAuth and Permissions, add the bot scopes channels:read, channels:history and chat:write.",
                        "Install the app to your workspace, then copy the Bot User OAuth Token and paste it here.",
                        "In each channel agents should use, type /invite followed by the app's name."),
                "https://api.slack.com/authentication/basics"));
        add(new ConnectorInfo(
                "notion",
                "Notion",
                "productivity",
                "Search, read and write pages in Notion.",
                "token",
                true,
                "Internal integration secret",
                List.of(
                        "Go to notion.so/profile/integrations and create a new internal integration for your workspace.",
                        "Give it the capabilities Read content, Insert content and Update content.",
                        "Copy the Internal Integration Secret and paste it here.",
                        "In Notion, open each page agents may use, choose Connections from the page menu, and add the integration."),
                "https://developers.notion.com/docs/create-a-notion-integration"));
        add(new ConnectorInfo(
                "linear",
                "Linear",
                "engineering",
                "Read, create and update issues in Linear.",
                "token",
                true,
                "Personal API key",
                List.of(
                        "In Linear, open Settings, then Security and access.",
                        "Under Personal API keys, create a new key named AI Workforce OS.",
                        "Copy the key and paste it here."),
                "https://linear.app/docs/api-and-webhooks"));
        add(new ConnectorInfo(
                "hubspot",
                "HubSpot",
                "sales",
                "Look up and add contacts and review deals in HubSpot.",
                "token",
                true,
                "Private app access token",
                List.of(
                        "In HubSpot, open Settings, then Integrations, then Private Apps, and create a private app.",
                        "Under Scopes, allow crm.objects.contacts.read, crm.objects.contacts.write and crm.objects.deals.read.",
                        "Create the app, copy its access token, and paste it here."),
                "https://developers.hubspot.com/docs/api/private-apps"));
        add(new ConnectorInfo(
                "webhook",
                "Webhook",
                "automation",
                "Send events as JSON to any system that accepts a webhook.",
                "url",
                true,
                "Webhook address (https://...)",
                List.of(
                        "In the system that should receive events, create an incoming webhook and copy its address.",
                        "The address must start with https:// and must not point inside a private network.",
                        "Paste the address here. Each event is sent to it as a JSON POST request."),
                null));

        // ---- Live with a token typed into the connect dialog -------------------------------------
        List<CredentialField> atlassian = List.of(
                CredentialField.plain("site", "Atlassian site", "https://your-team.atlassian.net",
                        "The address you sign in to, ending in .atlassian.net."),
                CredentialField.plain("email", "Account email", "you@company.com",
                        "The email of the Atlassian account that created the token."),
                CredentialField.secret("token", "API token", "Created in your Atlassian account security settings."));
        token("jira", "Jira", "engineering", "Search, create and update Jira issues.", "API token", atlassian,
                List.of(
                        "Sign in to id.atlassian.com/manage-profile/security/api-tokens with the account agents should act as.",
                        "Choose Create API token, name it AI Workforce OS, and copy it. Atlassian shows it once.",
                        "Enter your site address (for example your-team.atlassian.net), the account's email and the token here.",
                        "Agents can only see the projects that account can see."),
                "https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/");
        token("confluence", "Confluence", "productivity", "Search, read and write pages in Confluence.", "API token",
                atlassian,
                List.of(
                        "Sign in to id.atlassian.com/manage-profile/security/api-tokens with the account agents should act as.",
                        "Choose Create API token, name it AI Workforce OS, and copy it. Atlassian shows it once.",
                        "Enter your site address (for example your-team.atlassian.net), the account's email and the token here.",
                        "Agents can only see the spaces that account can see."),
                "https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/");
        token("asana", "Asana", "productivity", "Read, create, update and delete tasks in Asana.",
                "Personal access token", List.of(),
                List.of(
                        "In Asana, open your profile photo, then Settings, then Apps, then Service accounts or Developer apps.",
                        "Choose Create new token, name it AI Workforce OS and accept the terms.",
                        "Copy the token and paste it here. Asana shows it once.",
                        "Agents can see the same projects and tasks as the account that created the token."),
                "https://developers.asana.com/docs/personal-access-token");
        token("zendesk", "Zendesk", "support",
                "Read and update support tickets, add private notes and reply to customers in Zendesk.", "API token",
                List.of(
                        CredentialField.plain("subdomain", "Zendesk subdomain", "your-team",
                                "The first part of your-team.zendesk.com."),
                        CredentialField.plain("email", "Agent email", "agent@company.com",
                                "The email of the agent account that created the token."),
                        CredentialField.secret("token", "API token",
                                "In Admin Center, under Apps and integrations, then APIs, then Zendesk API.")),
                List.of(
                        "In Zendesk, open Admin Center, then Apps and integrations, then APIs, then Zendesk API.",
                        "Turn on Token access, choose Add API token, name it AI Workforce OS and copy it. Zendesk shows it once.",
                        "Enter your subdomain, the agent's email and the token here.",
                        "Replies sent from here reach customers by email, so each one asks a person first."),
                "https://developer.zendesk.com/api-reference/introduction/security-and-auth/");
        token("stripe", "Stripe", "finance",
                "Look up customers, payments and invoices, and refund payments, in Stripe.", "Restricted API key",
                List.of(),
                List.of(
                        "In the Stripe Dashboard, open Developers, then API keys, and choose Create restricted key.",
                        "Give it Read access to Customers, PaymentIntents and Invoices, and Write access to Refunds only if agents may propose refunds.",
                        "Create the key, copy it (it starts with rk_), and paste it here. A secret key (sk_) also works but is broader than needed.",
                        "Every refund asks a person first, whatever the key allows."),
                "https://docs.stripe.com/keys#create-restricted-api-secret-key");
        token("zoom", "Zoom", "communication", "List, schedule and cancel Zoom meetings, and find recordings.",
                "Client secret",
                List.of(
                        CredentialField.plain("accountId", "Account ID", null, "Shown on the app's App Credentials page."),
                        CredentialField.plain("clientId", "Client ID", null, "Shown on the app's App Credentials page."),
                        CredentialField.secret("clientSecret", "Client secret", "Shown on the app's App Credentials page.")),
                List.of(
                        "Go to marketplace.zoom.us, choose Develop, then Build App, and create a Server-to-Server OAuth app.",
                        "Under Scopes, add the meeting scopes to list, read, create and delete meetings, and the cloud recording scope to list a user's recordings.",
                        "Activate the app, then copy the Account ID, Client ID and Client Secret from App Credentials.",
                        "Enter all three here. Agents act on the meetings of the account that owns the app."),
                "https://developers.zoom.us/docs/internal-apps/s2s-oauth/");

        token("twilio", "Twilio", "voice",
                "Find, buy and release phone numbers in Twilio, and connect them to ElevenLabs for phone calls.",
                "Auth token",
                List.of(
                        CredentialField.plain("accountSid", "Account SID", "AC followed by 32 letters and digits",
                                "Shown in the Twilio Console under Account info. It starts with AC."),
                        CredentialField.secret("authToken", "Auth token",
                                "Shown beside the Account SID in the Twilio Console. Choose Show to copy it.")),
                List.of(
                        "Sign in to console.twilio.com with the account whose numbers agents should use.",
                        "On the Account Dashboard, under Account info, copy the Account SID (it starts with AC) and the"
                                + " Auth token.",
                        "Enter both here. The connection check reads the account's name.",
                        "Buying or releasing a number, and connecting one to ElevenLabs, always asks a person first.",
                        "To place calls from a number, also store an ElevenLabs key on this page and give an agent"
                                + " Phone calls."),
                "https://www.twilio.com/docs/iam/api/account");

        // ---- Live through OAuth: an administrator registers the app, then signs in ----------------
        oauth("gmail", "Gmail", "communication", "Read, draft and send email from a Gmail inbox.");
        oauth("calendar", "Google Calendar", "productivity",
                "Check availability and book or remove events in Google Calendar.");
        oauth("drive", "Google Drive", "files", "Find, read and save documents in Google Drive.");
        oauth("sheets", "Google Sheets", "files", "Read rows and add or change rows in Google Sheets.");
        oauth("outlook", "Microsoft Outlook", "communication",
                "Read, draft, send and delete email, and check the calendar, in Microsoft Outlook.");
        oauth("teams", "Microsoft Teams", "communication", "Read channels and post messages in Microsoft Teams.");
        oauth("salesforce", "Salesforce", "sales",
                "Look up accounts and opportunities, update deals and add leads in Salesforce.");
        add(new ConnectorInfo(
                "voice",
                "Voice notes",
                "voice",
                "Write short scripts that the workspace reads aloud in its chosen voice.",
                "none",
                false,
                null,
                List.of("Nothing to connect here. To hear notes read aloud, add an ElevenLabs key under Settings."),
                null));
        add(new ConnectorInfo(
                "browser",
                "Web browser",
                "productivity",
                "Open public web pages, read them and follow links, and fill in and submit forms after a person approves.",
                "none",
                false,
                null,
                List.of(
                        "Nothing to connect here. Give an agent the web browser on its page to let it use it.",
                        "Each page the agent opens is shown as a screenshot in the run, updated after every step.",
                        "Reading pages and typing into fields happen freely. Every click and every form submission"
                                + " asks a person first.",
                        "Pages inside private networks, and addresses on the server itself, are never opened."),
                null));
        add(new ConnectorInfo(
                "phone",
                "Phone calls",
                "voice",
                "Place phone calls with an AI voice through ElevenLabs, and read back each call's summary and transcript.",
                "none",
                false,
                null,
                List.of(
                        "Needs the ElevenLabs key stored for voice on this page, and a phone number added in ElevenLabs"
                                + " under Phone numbers.",
                        "In ElevenLabs, open Phone numbers and add a number (through Twilio or a SIP trunk). The first"
                                + " number on the account is used for every call.",
                        "The first call creates an ElevenLabs agent named AI Workforce caller; each call gives it"
                                + " that call's purpose.",
                        "Every call asks a person first. Premium-rate numbers and short codes are never dialled."),
                "https://elevenlabs.io/docs/agents-platform/phone-numbers/twilio-integration/native-integration"));
    }

    // @find: list all connectors, connector catalog list
    /**
     * Every connector in the order it was added, as an immutable copy. Used by the connectors
     * listing endpoint.
     */
    public List<ConnectorInfo> all() {
        return List.copyOf(connectors.values());
    }

    // @find: find connector by server name, look up connector
    /** One connector by server name, or empty when the name is not in the catalog. */
    public Optional<ConnectorInfo> find(String server) {
        return Optional.ofNullable(connectors.get(server));
    }

    /**
     * Adds a token connector (the administrator pastes a credential). The field list may be empty,
     * which the dialog treats as a single secret box.
     */
    private void token(
            String server,
            String displayName,
            String category,
            String description,
            String tokenLabel,
            List<CredentialField> fields,
            List<String> steps,
            String docsUrl) {
        add(new ConnectorInfo(
                server, displayName, category, description, "token", true, tokenLabel, steps, docsUrl, fields, null));
    }

    /** An OAuth connector: its app steps, scopes and extra settings come from {@link OAuthProviders}. */
    private void oauth(String server, String displayName, String category, String description) {
        OAuthProvider provider = OAuthProviders.forServer(server).orElseThrow();
        OAuthSetup setup = new OAuthSetup(
                provider.id(),
                provider.label(),
                provider.appFields(),
                provider.scopesFor(server),
                provider.appSteps(),
                provider.appDocsUrl());
        add(new ConnectorInfo(
                server,
                displayName,
                category,
                description,
                "oauth",
                true,
                null,
                List.of(
                        "An owner or admin first registers an app with " + provider.label()
                                + " and saves its client ID and secret under Set up the app. That is done once for the workspace.",
                        "Then choose Connect, sign in to " + provider.label() + " and approve the permissions listed.",
                        "Agents act as the account that approved, and only through the actions each agent is given."),
                provider.appDocsUrl(),
                List.of(),
                setup));
    }

    /** Stores one entry and rejects a duplicate server name. */
    private void add(ConnectorInfo info) {
        if (connectors.putIfAbsent(info.server(), info) != null) {
            throw new IllegalStateException("Connector listed twice: " + info.server());
        }
    }
}

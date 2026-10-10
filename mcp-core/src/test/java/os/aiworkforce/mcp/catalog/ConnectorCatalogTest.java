// @find: tests for connector catalog, catalog matches registered servers, every connector has info, live available flag, oauth connectors setup, credential fields, gmail, slack, github, jira, all connectors
// @what: Checks the connector catalog, the registered servers and the live adapters all describe the same connectors.
package os.aiworkforce.mcp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.live.Hosts;
import os.aiworkforce.mcp.live.LiveServerAdapter;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/** The catalog, the registered servers and the live adapters must describe the same things. */
class ConnectorCatalogTest {

    private final ConnectorCatalog catalog = new ConnectorCatalog();
    private final List<McpServerAdapter> servers =
            SandboxServerRegistry.servers(new ObjectMapper(), WebClient.builder(), true);

    @Test
    @DisplayName("every registered server has catalog information, and the catalog lists nothing else")
    void catalogMatchesServers() {
        Set<String> registered = servers.stream().map(McpServerAdapter::server).collect(Collectors.toSet());
        Set<String> listed = catalog.all().stream().map(ConnectorInfo::server).collect(Collectors.toSet());

        assertThat(listed).isEqualTo(registered);
        assertThat(registered)
                .contains(
                        "outlook", "teams", "notion", "linear", "hubspot", "salesforce", "zendesk", "confluence",
                        "asana", "sheets", "stripe", "zoom", "webhook");
    }

    @Test
    @DisplayName("a connector says it can go live exactly when a live adapter exists, and every one but Voice has one")
    void liveAvailableIsHonest() {
        for (McpServerAdapter server : servers) {
            ConnectorInfo info = catalog.find(server.server()).orElseThrow();
            assertThat(info.liveAvailable())
                    .as(server.server())
                    .isEqualTo(server instanceof LiveServerAdapter);
        }
        assertThat(servers.stream().filter(server -> !(server instanceof LiveServerAdapter)).map(McpServerAdapter::server))
                .containsExactly("voice");
        assertThat(catalog.all().stream().filter(info -> !info.liveAvailable()).map(ConnectorInfo::server))
                .containsExactly("voice");
        assertThat(catalog.find("voice").orElseThrow().authType()).isEqualTo("none");
    }

    @Test
    @DisplayName("each live adapter offers exactly its sandbox's tools and implements every one of them")
    void liveToolsEqualSandboxTools() {
        Map<String, List<ToolDefinition>> definitions = SandboxServerRegistry.definitions();
        for (McpServerAdapter server : servers) {
            if (server instanceof LiveServerAdapter live) {
                assertThat(live.tools()).as(live.server()).isEqualTo(definitions.get(live.server()));
                assertThat(live.liveTools())
                        .as(live.server())
                        .isEqualTo(live.tools().stream().map(ToolDefinition::name).collect(Collectors.toSet()));
            }
        }
    }

    @Test
    @DisplayName("every catalog entry is complete enough for the console to show")
    void entriesAreComplete() {
        for (ConnectorInfo info : catalog.all()) {
            assertThat(info.description()).as(info.server()).isNotBlank().endsWith(".");
            assertThat(info.setupSteps()).as(info.server()).isNotEmpty();
            if (info.liveAvailable()) {
                assertThat(info.authType()).as(info.server()).isIn("token", "url", "oauth");
            }
            if (info.liveAvailable() && !"oauth".equals(info.authType())) {
                assertThat(info.tokenLabel()).as(info.server()).isNotBlank();
            }
            if ("oauth".equals(info.authType())) {
                assertThat(info.oauth()).as(info.server()).isNotNull();
                assertThat(info.oauth().scopes()).as(info.server()).isNotEmpty();
                assertThat(info.oauth().appSteps()).as(info.server()).isNotEmpty();
                assertThat(info.oauth().appDocsUrl()).as(info.server()).startsWith("https://");
            } else {
                assertThat(info.oauth()).as(info.server()).isNull();
            }
            info.credentialFields().forEach(field -> {
                assertThat(field.key()).as(info.server()).matches("[a-zA-Z]+");
                assertThat(field.label()).as(info.server() + "." + field.key()).isNotBlank();
            });
        }
    }

    @Test
    @DisplayName("tool names are verb_noun, and sending or removing is never classed as a plain write")
    void sideEffectsFollowTheVerb() {
        SandboxServerRegistry.definitions().forEach((server, tools) -> tools.forEach(tool -> {
            assertThat(tool.name()).as(tool.qualifiedName()).matches("^[a-z]+_[a-z_]+$");
            assertThat(tool.description()).as(tool.qualifiedName()).isNotBlank();
            String verb = tool.name().substring(0, tool.name().indexOf('_'));
            if (Set.of("send", "post").contains(verb)) {
                assertThat(tool.sideEffect()).as(tool.qualifiedName()).isEqualTo(ToolSpec.SideEffect.OUTBOUND);
            }
            if (Set.of("delete", "remove", "refund", "cancel", "archive").contains(verb)) {
                assertThat(tool.sideEffect()).as(tool.qualifiedName()).isEqualTo(ToolSpec.SideEffect.DESTRUCTIVE);
            }
            if (Set.of("list", "search", "get").contains(verb)) {
                assertThat(tool.sideEffect()).as(tool.qualifiedName()).isEqualTo(ToolSpec.SideEffect.READ);
            }
        }));
    }

    @Test
    @DisplayName("each new connector offers between three and six tools, except the webhook")
    void newConnectorsHaveSensibleToolCounts() {
        Map<String, List<ToolDefinition>> definitions = SandboxServerRegistry.definitions();
        for (String server : List.of(
                "outlook", "teams", "notion", "linear", "hubspot", "salesforce", "zendesk", "confluence", "asana",
                "sheets", "stripe", "zoom")) {
            assertThat(definitions.get(server)).as(server).hasSizeBetween(3, 6);
        }
        assertThat(definitions.get("stripe"))
                .filteredOn(tool -> tool.sideEffect() == ToolSpec.SideEffect.DESTRUCTIVE)
                .extracting(ToolDefinition::name)
                .containsExactly("refund_payment");
    }

    @Test
    @DisplayName("token connectors with several parts name each box, and only the secret ones are masked")
    void credentialFieldsDescribeTheDialog() {
        assertThat(catalog.find("jira").orElseThrow().credentialFields())
                .extracting(CredentialField::key, CredentialField::secret)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("site", false),
                        org.assertj.core.groups.Tuple.tuple("email", false),
                        org.assertj.core.groups.Tuple.tuple("token", true));
        assertThat(catalog.find("confluence").orElseThrow().credentialFields()).hasSize(3);
        assertThat(catalog.find("zendesk").orElseThrow().credentialFields())
                .extracting(CredentialField::key)
                .containsExactly("subdomain", "email", "token");
        assertThat(catalog.find("zoom").orElseThrow().credentialFields())
                .extracting(CredentialField::key)
                .containsExactly("accountId", "clientId", "clientSecret");
        assertThat(catalog.find("asana").orElseThrow().credentialFields()).isEmpty();
        assertThat(catalog.find("stripe").orElseThrow().credentialFields()).isEmpty();
    }

    @Test
    @DisplayName("sign-in connectors share one app per provider and ask only for their own permissions")
    void oauthConnectorsDescribeTheirApp() {
        assertThat(catalog.find("gmail").orElseThrow().oauth().provider()).isEqualTo("google");
        assertThat(catalog.find("sheets").orElseThrow().oauth().provider()).isEqualTo("google");
        assertThat(catalog.find("teams").orElseThrow().oauth().provider()).isEqualTo("microsoft");
        assertThat(catalog.find("outlook").orElseThrow().oauth().appFields())
                .extracting(CredentialField::key)
                .containsExactly("tenant");
        assertThat(catalog.find("salesforce").orElseThrow().oauth().appFields())
                .extracting(CredentialField::key)
                .containsExactly("loginDomain");
        assertThat(catalog.find("gmail").orElseThrow().oauth().scopes())
                .contains("https://www.googleapis.com/auth/gmail.send")
                .doesNotContain("https://www.googleapis.com/auth/drive.readonly");
        assertThat(catalog.find("calendar").orElseThrow().oauth().scopes())
                .doesNotContain("https://www.googleapis.com/auth/gmail.send");
        assertThat(catalog.find("outlook").orElseThrow().oauth().scopes()).contains("Mail.Send", "offline_access");
    }

    @Test
    @DisplayName("typed-in hosts only ever become the provider's own domain")
    void hostsAreGuarded() {
        assertThat(Hosts.atlassianBase("acme")).isEqualTo("https://acme.atlassian.net");
        assertThat(Hosts.atlassianBase("https://acme.atlassian.net/")).isEqualTo("https://acme.atlassian.net");
        assertThat(Hosts.atlassianBase("ACME.atlassian.net")).isEqualTo("https://acme.atlassian.net");
        assertThat(Hosts.zendeskBase("acme")).isEqualTo("https://acme.zendesk.com");
        for (String bad : new String[] {
            "https://evil.example.com", "acme.atlassian.net.evil.com", "http://acme.atlassian.net",
            "https://acme.atlassian.net@evil.com", "acme.atlassian.net/../x", "https://169.254.169.254",
            "acme.atlassian.net:8443", "", "a b", "acme.atlassian.net?x=1", "-acme"
        }) {
            assertThatThrownBy(() -> Hosts.atlassianBase(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Hosts.zendeskBase("evil.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Hosts.salesforceInstance("https://acme.my.salesforce.com")).isEqualTo("https://acme.my.salesforce.com");
        assertThat(Hosts.salesforceInstance("https://na1.salesforce.com/")).isEqualTo("https://na1.salesforce.com");
        for (String bad : new String[] {
            "https://salesforce.com.evil.com", "https://evil.com", "http://acme.my.salesforce.com", "https://127.0.0.1"
        }) {
            assertThatThrownBy(() -> Hosts.salesforceInstance(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Hosts.salesforceLoginDomain("login.salesforce.com")).isEqualTo("login.salesforce.com");
        assertThat(Hosts.salesforceLoginDomain("acme.my.salesforce.com")).isEqualTo("acme.my.salesforce.com");
        assertThatThrownBy(() -> Hosts.salesforceLoginDomain("evil.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Hosts.microsoftTenant("common")).isEqualTo("common");
        assertThat(Hosts.microsoftTenant("acme.onmicrosoft.com")).isEqualTo("acme.onmicrosoft.com");
        assertThat(Hosts.microsoftTenant("72f988bf-86f1-41af-91ab-2d7cd011db47"))
                .isEqualTo("72f988bf-86f1-41af-91ab-2d7cd011db47");
        assertThatThrownBy(() -> Hosts.microsoftTenant("../evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hosts.microsoftTenant("a/b")).isInstanceOf(IllegalArgumentException.class);
    }
}

package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/** Schedules read the workspace's zone from the internal endpoint, once per cache lifetime. */
class ScheduleZoneLookupTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final List<ClientRequest> sent = new ArrayList<>();
    private ScheduleZoneLookup lookup;

    @BeforeEach
    void setUp() {
        PlatformProperties properties = mock(PlatformProperties.class);
        PlatformProperties.Services services = mock(PlatformProperties.Services.class);
        when(properties.services()).thenReturn(services);
        when(services.organisation()).thenReturn("http://organisation");
        InternalTokenProvider tokens = mock(InternalTokenProvider.class);
        when(tokens.forService("organisation")).thenReturn("service-token");

        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            sent.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"id\":\"" + ORG + "\",\"name\":\"Acme\",\"slug\":\"acme\","
                            + "\"timezone\":\"Europe/London\",\"status\":\"active\"}")
                    .build());
        });
        lookup = new ScheduleZoneLookup(builder, properties, tokens);
    }

    @Test
    @DisplayName("the zone comes from the internal workspace endpoint and is then served from cache")
    void internalEndpointThenCache() {
        assertThat(lookup.zoneFor(ORG)).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(lookup.zoneFor(ORG)).isEqualTo(ZoneId.of("Europe/London"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).url().getPath()).isEqualTo("/internal/workspaces/" + ORG);
        assertThat(sent.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer service-token");
    }
}

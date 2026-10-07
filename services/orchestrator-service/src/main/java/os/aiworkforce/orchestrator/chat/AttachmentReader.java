package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Asks the knowledge service what a file really is and what it says.
 *
 * <p>The knowledge service already reads every format a workspace uploads - PDF, Word, PowerPoint,
 * Excel, CSV, text and the rest - and detects the type from the bytes rather than the name. A chat
 * attachment is read by the same code, through its internal extract endpoint, so there is one set
 * of parsers and one set of plain reasons a file could not be read. Nothing is indexed there.
 */
public interface AttachmentReader {

    /** The most text kept from one file; far more than any one message's run is given. */
    int MAX_TEXT_CHARS = 200_000;

    /**
     * @param mediaType what the file is, detected from its content
     * @param text its readable text, possibly empty
     * @param pageCount pages, where the format has them
     * @param contentHash identifies the exact bytes
     * @param problem why there is no text, for a person to act on; null when it was read
     * @param notice something to know that did not stop the reading
     * @param truncated whether the text was cut at {@link #MAX_TEXT_CHARS}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Read(
            String mediaType,
            String text,
            Integer pageCount,
            String contentHash,
            String problem,
            String notice,
            boolean truncated) {}

    /**
     * @throws ApiException {@code DEPENDENCY_UNAVAILABLE} when the file could not be read at all
     *     right now, so an upload is refused rather than stored unchecked
     */
    Read read(UUID orgId, String name, byte[] content);

    /** The knowledge service's internal extract endpoint, called with a service token. */
    @Component
    class Remote implements AttachmentReader {

        private static final Logger log = LoggerFactory.getLogger(Remote.class);

        private final WebClient client;
        private final InternalTokenProvider tokens;

        public Remote(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
            this.client = builder.clone()
                    .baseUrl(properties.services().knowledge())
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * MAX_TEXT_CHARS + 65_536))
                    .build();
            this.tokens = tokens;
        }

        @Override
        public Read read(UUID orgId, String name, byte[] content) {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("file", new ByteArrayResource(content) {
                        @Override
                        public String getFilename() {
                            return name;
                        }
                    })
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            try {
                Read read = client.post()
                        .uri("/internal/knowledge/extract?maxChars=" + MAX_TEXT_CHARS)
                        .header("Authorization", "Bearer " + tokens.forService("knowledge", orgId))
                        .header("X-Workspace-Id", orgId.toString())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(body.build()))
                        .retrieve()
                        .bodyToMono(Read.class)
                        .timeout(Duration.ofSeconds(60))
                        .block();
                if (read == null) {
                    throw unavailable();
                }
                return read;
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Could not read attachment {} in workspace {}: {}", name, orgId, e.getMessage());
                throw unavailable();
            }
        }

        private static ApiException unavailable() {
            return new ApiException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE, "Files cannot be read right now. Try again in a minute.");
        }
    }
}

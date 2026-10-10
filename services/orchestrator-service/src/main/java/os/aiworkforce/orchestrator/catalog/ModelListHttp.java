// @find: model list http, GET provider model list, http seam, testable network call, ModelListHttp, API key header, no logging of keys
// @what: The single HTTP GET a model list needs, behind an interface so tests need no network.
// @flow: Used by ModelCatalogService and BedrockRegionFinder callers.
package os.aiworkforce.orchestrator.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one GET a model list needs. Behind an interface so the catalogue is tested without a network.
 *
 * <p>Headers can carry a workspace's key. Nothing here logs a header, a URL with a key in it, or a
 * response body.
 */
public interface ModelListHttp {

    /** What came back: the status, and the body as text. */
    record Response(int status, String body) {}

    /**
     * @throws IOException when the provider could not be reached or did not answer in time
     */
    Response get(URI uri, Map<String, String> headers) throws IOException;

    /** The real one, on the JDK's client, with short timeouts: a listing is never worth a long wait. */
    static ModelListHttp jdk() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (uri, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET();
            headers.forEach(request::header);
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        };
    }
}

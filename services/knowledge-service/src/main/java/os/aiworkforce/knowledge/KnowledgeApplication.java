// @find: knowledge service, knowledge base service, knowledge-service startup, Spring Boot main, KnowledgeApplication, documents sources ingestion embeddings Qdrant search
// @what: Starts the knowledge-service Spring Boot application that owns the knowledge base (sources, documents, chunks, vector search).
// @flow: Boots the controllers in web/, IngestionService, RetrievalService and QdrantClient.
package os.aiworkforce.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Knowledge: ingestion, embedding and cited retrieval.
 *
 * <p>It deliberately does not scan the model layer. Embeddings go through the orchestrator,
 * which owns provider configuration and spend, so pulling the router in here would require this
 * service to hold a second copy of both.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(scanBasePackageClasses = {KnowledgeApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(basePackageClasses = {KnowledgeApplication.class, PlatformCore.class, PlatformWeb.class})
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}

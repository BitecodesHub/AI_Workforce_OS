package os.aiworkforce.orchestrator.catalog;

import java.math.BigDecimal;

/**
 * One model as a provider's own model list describes it, reduced to what choosing a model for an
 * agent needs.
 *
 * @param id the provider's model identifier, exactly as a request names it
 * @param displayName a readable name: the provider's own where it gives one, otherwise made from the id
 * @param free the provider charges nothing for it
 * @param toolCalling it can call tools (function calling), which every agent needs
 * @param vision it reads images as well as text
 * @param contextLength its context window in tokens, or 0 when the listing does not say
 * @param maxOutputTokens the most it writes in one answer, or 0 when the listing does not say
 * @param jsonMode it can be asked for JSON output
 * @param pricePerMTokIn US dollars per million input tokens, or null when not known
 * @param pricePerMTokOut US dollars per million output tokens, or null when not known
 * @param pricingNote a short, plain note on how the provider charges, or null
 */
public record CatalogModel(
        String id,
        String displayName,
        boolean free,
        boolean toolCalling,
        boolean vision,
        int contextLength,
        int maxOutputTokens,
        boolean jsonMode,
        BigDecimal pricePerMTokIn,
        BigDecimal pricePerMTokOut,
        String pricingNote) {}

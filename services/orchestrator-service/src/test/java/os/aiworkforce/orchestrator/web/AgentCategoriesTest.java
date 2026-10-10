// @find: tests for agent categories, agent categories, category derivation, agent grouping
// @what: Unit and integration tests (1 cases) for agent categories, for example: matches the constraint.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The categories the API accepts are exactly the ones the database's check constraint allows, so
 * an unknown category is refused as a validation error naming the choices, never by the
 * constraint (which used to surface as "That item already exists").
 */
class AgentCategoriesTest {

    @Test
    @DisplayName("the API's categories match the agents_category_valid constraint")
    void matchesTheConstraint() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__orchestrator.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("agents_category_valid CHECK \\(category IN \\(([^)]*)\\)\\)").matcher(sql);
        assertThat(m.find()).isTrue();
        String[] allowed = m.group(1).replace("'", "").split("\\s*,\\s*");

        assertThat(AgentController.CATEGORIES).containsExactlyInAnyOrder(allowed);
    }
}

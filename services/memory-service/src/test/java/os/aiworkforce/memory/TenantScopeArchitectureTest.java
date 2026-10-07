package os.aiworkforce.memory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Parameter;
import java.util.UUID;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No endpoint in this service takes the workspace from the request.
 *
 * <p>The workspace comes from the caller's token. The memory API once read it from an
 * {@code orgId} query parameter instead, which let anyone signed in to one workspace read and
 * write another's agent memory; this keeps that shape from coming back.
 */
class TenantScopeArchitectureTest {

    private static final ArchRule WORKSPACE_COMES_FROM_THE_TOKEN = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .should(notTakeOrgIdAsRequestParameter())
            .because("the workspace must come from the caller's token, never from the request");

    @Test
    @DisplayName("no controller method has a request parameter named orgId")
    void noOrgIdRequestParameter() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("os.aiworkforce.memory");

        WORKSPACE_COMES_FROM_THE_TOKEN.check(classes);
    }

    @Test
    @DisplayName("the rule catches the shape it exists to forbid")
    void ruleBites() {
        JavaClasses offender = new ClassFileImporter().importClasses(OffendingController.class);

        assertThatThrownBy(() -> WORKSPACE_COMES_FROM_THE_TOKEN.check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("orgId");
    }

    private static ArchCondition<JavaMethod> notTakeOrgIdAsRequestParameter() {
        return new ArchCondition<>("not take a request parameter named orgId") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (Parameter parameter : method.reflect().getParameters()) {
                    RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                    if (annotation == null) {
                        continue;
                    }
                    String name = !annotation.name().isEmpty()
                            ? annotation.name()
                            : !annotation.value().isEmpty() ? annotation.value() : parameter.getName();
                    if ("orgId".equalsIgnoreCase(name)) {
                        events.add(SimpleConditionEvent.violated(
                                method, method.getFullName() + " takes orgId as a request parameter"));
                    }
                }
            }
        };
    }

    @RestController
    static class OffendingController {
        @GetMapping("/api/example")
        public String read(@RequestParam UUID orgId) {
            return orgId.toString();
        }
    }
}

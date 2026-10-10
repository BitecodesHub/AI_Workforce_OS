// @find: tests for integrity error code, database constraint violation, conflict response, duplicate key error
// @what: Checks database integrity failures are turned into the right error codes.
package os.aiworkforce.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import os.aiworkforce.platform.error.ErrorCode;

class IntegrityCodeTest {

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("wrapped", new SQLException("constraint", sqlState)));
    }

    @Test
    @DisplayName("only a unique constraint is reported as already existing")
    void uniqueIsAlreadyExists() {
        assertThat(GlobalExceptionHandler.integrityCode(violation("23505"))).isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("a check, not-null, foreign-key or too-long value is a validation failure")
    void otherConstraintsAreValidation() {
        assertThat(GlobalExceptionHandler.integrityCode(violation("23514"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(GlobalExceptionHandler.integrityCode(violation("23502"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(GlobalExceptionHandler.integrityCode(violation("23503"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(GlobalExceptionHandler.integrityCode(violation("22001"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("without an SQL state it stays a conflict, as before")
    void unknownStaysConflict() {
        assertThat(GlobalExceptionHandler.integrityCode(new DataIntegrityViolationException("x")))
                .isEqualTo(ErrorCode.ALREADY_EXISTS);
    }
}

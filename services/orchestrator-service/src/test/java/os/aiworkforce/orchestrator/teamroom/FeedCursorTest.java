// @find: tests for team room feed cursor, encode decode cursor, cursor order, invalid cursor is a validation error, FeedCursorTest, FeedCursor
// @what: Unit tests for the team room conversation's opaque cursor: round trip, order and refusal of anything it did not write.
// @flow: Exercises FeedCursor
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.error.ApiException;

/** A cursor is a moment (to the microsecond) and an event id, carried as opaque URL-safe text. */
class FeedCursorTest {

    private static final Instant AT = Instant.parse("2026-10-12T09:30:15.123456789Z");

    // @find: test round trip, feed cursor
    @Test
    @DisplayName("a cursor survives the trip to the client and back, at the database's microsecond precision")
    void roundTrip() {
        FeedCursor cursor = new FeedCursor(AT, "t:0192-abc:2:f");

        FeedCursor back = FeedCursor.decode(cursor.encode(), "after");

        assertThat(back).isEqualTo(cursor);
        assertThat(back.at()).isEqualTo(Instant.parse("2026-10-12T09:30:15.123456Z"));
        assertThat(cursor.encode()).doesNotContain("=", "+", "/");
    }

    // @find: test order, feed cursor
    @Test
    @DisplayName("positions order by moment, then by event id, and the empty id comes before every event at its moment")
    void order() {
        FeedCursor early = new FeedCursor(AT, "z");
        FeedCursor sameMomentA = new FeedCursor(AT.plusSeconds(1), "a:1");
        FeedCursor sameMomentB = new FeedCursor(AT.plusSeconds(1), "a:2");
        FeedCursor line = FeedCursor.before(AT.plusSeconds(1));

        assertThat(early).isLessThan(line);
        assertThat(line).isLessThan(sameMomentA);
        assertThat(sameMomentA).isLessThan(sameMomentB);
        assertThat(sameMomentA.max(sameMomentB)).isEqualTo(sameMomentB);
        assertThat(sameMomentA.min(sameMomentB)).isEqualTo(sameMomentA);
        assertThat(early.max(null)).isEqualTo(early);
        assertThat(early.min(null)).isEqualTo(early);
    }

    // @find: test poll position with a floor, feed cursor
    @Test
    @DisplayName("a poll position carries its floor to the client and back, and a plain cursor is one with no floor")
    void pollPositionRoundTrip() {
        FeedCursor progress = new FeedCursor(AT, "t:0192-abc:2:f");
        FeedCursor floor = FeedCursor.before(AT.minusSeconds(20));

        FeedCursor.Poll poll = new FeedCursor.Poll(progress, floor);
        FeedCursor.Poll back = FeedCursor.Poll.decode(poll.encode(), "after");

        assertThat(back).isEqualTo(poll);
        assertThat(back.floor()).isEqualTo(new FeedCursor(Instant.parse("2026-10-12T09:29:55.123456Z"), ""));
        assertThat(poll.encode()).doesNotContain("=", "+", "/");
        assertThat(FeedCursor.Poll.decode(progress.encode(), "after")).isEqualTo(FeedCursor.Poll.at(progress));
        assertThat(FeedCursor.Poll.at(progress).encode()).isEqualTo(progress.encode());
        // A floor that is not before the progress would read nothing again, so it is dropped.
        assertThat(new FeedCursor.Poll(progress, progress).floor()).isNull();
        assertThat(FeedCursor.Poll.decode(" ", "after")).isNull();
        String brokenFloor = Base64.getUrlEncoder()
                .encodeToString("1~g:1|soon~g:2".getBytes(StandardCharsets.UTF_8));
        ApiException thrown =
                catchThrowableOfType(() -> FeedCursor.Poll.decode(brokenFloor, "after"), ApiException.class);
        assertThat(thrown.details()).containsEntry("field", "after");
    }

    // @find: test blank is no cursor, feed cursor
    @Test
    @DisplayName("a missing or blank parameter is no cursor at all")
    void blankIsNoCursor() {
        assertThat(FeedCursor.decode(null, "after")).isNull();
        assertThat(FeedCursor.decode("  ", "before")).isNull();
    }

    // @find: test anything else is a validation error naming the field, feed cursor
    @Test
    @DisplayName("text the service did not write is a validation error that names the parameter")
    void anythingElseIsAValidationError() {
        String noSeparator = Base64.getUrlEncoder().encodeToString("12345".getBytes(StandardCharsets.UTF_8));
        String notANumber = Base64.getUrlEncoder().encodeToString("soon~g:1".getBytes(StandardCharsets.UTF_8));
        for (String value : new String[] {"%%%", noSeparator, notANumber, "x".repeat(500)}) {
            ApiException thrown = catchThrowableOfType(() -> FeedCursor.decode(value, "before"), ApiException.class);
            assertThat(thrown).as(value).isNotNull();
            assertThat(thrown.details()).containsEntry("field", "before");
        }
    }
}

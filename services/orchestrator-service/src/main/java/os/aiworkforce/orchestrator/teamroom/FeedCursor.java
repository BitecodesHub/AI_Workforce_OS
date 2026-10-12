// @find: team room feed cursor, feed paging, after cursor, before cursor, stable cursor pagination, keyset cursor, settle floor, poll position, FeedCursor, Team room
// @what: An opaque, stable position in the team room's conversation (a moment and an event id), and a poll's position with the floor it must read again from.
// @flow: Read and written by TeamRoomService; decoded from the after and before parameters of TeamRoomController.
package os.aiworkforce.orchestrator.teamroom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;

import os.aiworkforce.platform.error.ApiException;

/**
 * One position in the team room's conversation, between two events.
 *
 * <p>Events are ordered by the moment they happened and then by their id, compared character by
 * character (the database compares them under the {@code "C"} collation, which is the same order
 * {@link String#compareTo} gives for the ASCII ids used here). The pair is unique for every event,
 * so a page that ends at a cursor and the next page that starts after it never skip or repeat an
 * event, however many share one moment.
 *
 * <p>On the wire a cursor is URL-safe base64 of {@code <epoch microseconds>~<event id>}: opaque to
 * the client, and short enough for a query string. Microseconds, because that is what the database
 * keeps, so a moment read from a row and written back into a cursor compares equal to that row.
 *
 * @param at the moment, truncated to microseconds
 * @param id the event id; an empty id sorts before every real one at the same moment
 */
public record FeedCursor(Instant at, String id) implements Comparable<FeedCursor> {

    /** Orders positions by moment, then by event id. */
    static final Comparator<FeedCursor> ORDER =
            Comparator.comparing(FeedCursor::at).thenComparing(FeedCursor::id);

    /** The separator between the moment and the id inside the encoded text; never part of an id. */
    private static final char SEPARATOR = '~';

    /** Longest encoded cursor accepted, so a pasted address cannot make the decoder work on megabytes. */
    private static final int MAX_ENCODED = 200;

    /** Truncates the moment to microseconds and treats a null id as the empty id. */
    public FeedCursor {
        if (at == null) {
            throw new IllegalArgumentException("A feed cursor needs a moment.");
        }
        at = at.truncatedTo(ChronoUnit.MICROS);
        id = id == null ? "" : id;
    }

    /** The position just before every event that happened at {@code at} (an empty id). Pure. */
    public static FeedCursor before(Instant at) {
        return new FeedCursor(at, "");
    }

    /** Compares by moment, then by id. Pure. */
    @Override
    public int compareTo(FeedCursor other) {
        return ORDER.compare(this, other);
    }

    /** The later of two positions; {@code other} may be null. Pure. */
    public FeedCursor max(FeedCursor other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }

    /** The earlier of two positions; {@code other} may be null. Pure. */
    public FeedCursor min(FeedCursor other) {
        return other == null || compareTo(other) <= 0 ? this : other;
    }

    /** The cursor as the client carries it: URL-safe base64, no padding. Pure. */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor the server handed out earlier.
     *
     * <p>Pure. A blank value is no cursor at all (null). Anything that is not a cursor this class
     * wrote - wrong alphabet, no separator, a moment that is not a number - is a validation error
     * naming {@code field}, so a mangled link answers 422 rather than 500.
     *
     * @param field the request parameter the value came from, named in the error
     */
    public static FeedCursor decode(String encoded, String field) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return parse(plainText(encoded, field), field);
    }

    /** The decoded text of an encoded cursor or poll position; a validation error naming {@code field} when it is not one. */
    private static String plainText(String encoded, String field) {
        if (encoded.length() > MAX_ENCODED) {
            throw invalid(field);
        }
        try {
            return new String(Base64.getUrlDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException unreadable) {
            throw invalid(field);
        }
    }

    /** One {@code <epoch microseconds>~<event id>} text as a cursor; a validation error naming {@code field} otherwise. */
    private static FeedCursor parse(String plain, String field) {
        try {
            int split = plain.indexOf(SEPARATOR);
            if (split <= 0) {
                throw new IllegalArgumentException("no separator");
            }
            long micros = Long.parseLong(plain.substring(0, split));
            Instant at = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            return new FeedCursor(at, plain.substring(split + 1));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException unreadable) {
            throw invalid(field);
        }
    }

    /** The plain text of a cursor, before it is encoded. */
    private String plain() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, at);
        return micros + String.valueOf(SEPARATOR) + id;
    }

    /** The validation error for a value that is not a position this class wrote. */
    private static ApiException invalid(String field) {
        return ApiException.validation(field, "is not a position in the team room's conversation");
    }

    /**
     * Where the next poll of the conversation starts: its {@code progress} (the events after it are
     * new) and, while a backlog is being read page by page, the {@code floor} it must read again
     * from once the backlog is drained.
     *
     * <p>Why a floor: an event is stamped when it happens but readable only once its transaction
     * commits, a moment later. A poll therefore reads the last few seconds twice (the settle window,
     * {@code TeamRoomService.SETTLE}). A page that ends inside that window because more than one
     * page was waiting cannot reach back without re-reading the backlog it just passed, so it
     * carries the floor forward instead, and the first poll after the backlog reads again from it.
     *
     * <p>On the wire it is the progress cursor's text, followed by {@code |} and the floor's when
     * there is one, encoded as one value; a plain cursor is a poll position with no floor.
     *
     * @param floor null when every event up to {@code progress} has already been read after it settled
     */
    public record Poll(FeedCursor progress, FeedCursor floor) {

        /** The separator between the progress and the floor inside the encoded text; never part of an id. */
        private static final char FLOOR_SEPARATOR = '|';

        /** Requires the progress; drops a floor that is not before it, since it would read nothing again. */
        public Poll {
            if (progress == null) {
                throw new IllegalArgumentException("A poll position needs a progress cursor.");
            }
            if (floor != null && floor.compareTo(progress) >= 0) {
                floor = null;
            }
        }

        /** A poll position with nothing to read again. Pure. */
        public static Poll at(FeedCursor progress) {
            return new Poll(progress, null);
        }

        /** The position as the client carries it: URL-safe base64, no padding. Pure. */
        public String encode() {
            String plain = floor == null
                    ? progress.plain()
                    : progress.plain() + FLOOR_SEPARATOR + floor.plain();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Reads a poll position the server handed out earlier, or a plain cursor (no floor).
         *
         * <p>Pure. Blank is no position (null); anything else this class did not write is a
         * validation error naming {@code field}.
         */
        public static Poll decode(String encoded, String field) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            String plain = plainText(encoded, field);
            int split = plain.indexOf(FLOOR_SEPARATOR);
            if (split < 0) {
                return at(parse(plain, field));
            }
            return new Poll(parse(plain.substring(0, split), field), parse(plain.substring(split + 1), field));
        }
    }
}

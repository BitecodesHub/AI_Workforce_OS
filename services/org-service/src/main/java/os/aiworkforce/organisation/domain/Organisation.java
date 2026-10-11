// @find: organisation entity, workspace entity, workspace name, slug, timezone, working hours, status, owner id, organisations table
// @what: JPA entity for a workspace (organisation) and its settings.
// @flow: Mapped to the organisations table; used by Organisations repository and WorkspaceController.
package os.aiworkforce.organisation.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * A company's workspace.
 *
 * <p>Working hours are held here because agents act on them. An outbound message that an agent
 * wants to send at two in the morning is usually a mistake, and the platform can hold it until
 * the workspace is open rather than letting the agent decide what "reasonable" means.
 */
@Entity
@Table(name = "organisations")
public class Organisation extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** Short unique name used in addresses; compared without regard to letter case. */
    @Column(nullable = false)
    private String slug;

    /** Lifecycle state, {@code active} by default. */
    @Column(nullable = false)
    private String status = "active";

    /** Zone id (for example {@code Australia/Melbourne}) in which working hours are read. */
    @Column(nullable = false)
    private String timezone = "Australia/Melbourne";

    /** Minutes from midnight, so the comparison needs no date arithmetic. */
    @Column(name = "working_hours_start_minute", nullable = false)
    private int workingHoursStartMinute = 540;

    /**
     * End of the working day in minutes from midnight; the end minute itself is outside working hours.
     * The default of 1050 is 17:30.
     */
    @Column(name = "working_hours_end_minute", nullable = false)
    private int workingHoursEndMinute = 1050;

    /** Comma-separated three-letter day codes, for example {@code MON,TUE}. */
    @Column(name = "working_days", nullable = false)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    /** The member who owns the workspace, or {@code null} until one is recorded. */
    @Column(name = "owner_id")
    private UUID ownerId;

    /** Whether the status is {@code active}. */
    public boolean isActive() {
        return "active".equals(status);
    }

    /**
     * Whether the workspace is open right now, in its own timezone rather than the server's.
     *
     * <p>Reads the current time in the workspace's zone, checks the day against {@code workingDays}
     * and the minute of the day against the start (inclusive) and end (exclusive). Throws
     * {@link java.time.DateTimeException} for an invalid zone and {@link IllegalArgumentException} for an
     * unknown day code. Reads the clock only; nothing is stored.
     */
    public boolean isWithinWorkingHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
        Set<DayOfWeek> days = Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .filter(day -> !day.isEmpty())
                .map(Organisation::toDayOfWeek)
                .collect(Collectors.toSet());
        if (!days.contains(now.getDayOfWeek())) {
            return false;
        }
        int minuteOfDay = now.toLocalTime().toSecondOfDay() / 60;
        return minuteOfDay >= workingHoursStartMinute && minuteOfDay < workingHoursEndMinute;
    }

    /**
     * Converts a three-letter day code, in any letter case, to a day of the week; an unknown code
     * throws {@link IllegalArgumentException}.
     */
    private static DayOfWeek toDayOfWeek(String abbreviation) {
        return switch (abbreviation.toUpperCase(java.util.Locale.ROOT)) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown day: " + abbreviation);
        };
    }

    /** The start of the working day as a clock time, converted from the stored minute count. */
    public LocalTime workingHoursStart() {
        return LocalTime.ofSecondOfDay(workingHoursStartMinute * 60L);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getWorkingHoursStartMinute() {
        return workingHoursStartMinute;
    }

    public void setWorkingHoursStartMinute(int workingHoursStartMinute) {
        this.workingHoursStartMinute = workingHoursStartMinute;
    }

    public int getWorkingHoursEndMinute() {
        return workingHoursEndMinute;
    }

    public void setWorkingHoursEndMinute(int workingHoursEndMinute) {
        this.workingHoursEndMinute = workingHoursEndMinute;
    }

    public String getWorkingDays() {
        return workingDays;
    }

    public void setWorkingDays(String workingDays) {
        this.workingDays = workingDays;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }
}

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

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String status = "active";

    @Column(nullable = false)
    private String timezone = "Australia/Melbourne";

    /** Minutes from midnight, so the comparison needs no date arithmetic. */
    @Column(name = "working_hours_start_minute", nullable = false)
    private int workingHoursStartMinute = 540;

    @Column(name = "working_hours_end_minute", nullable = false)
    private int workingHoursEndMinute = 1050;

    @Column(name = "working_days", nullable = false)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    @Column(name = "owner_id")
    private UUID ownerId;

    public boolean isActive() {
        return "active".equals(status);
    }

    /** Whether the workspace is open right now, in its own timezone rather than the server's. */
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

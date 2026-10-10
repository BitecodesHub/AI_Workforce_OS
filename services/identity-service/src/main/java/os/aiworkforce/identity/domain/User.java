// @find: user, account, person who signs in, password hash, failed login, lockout, locked account, mfa, email verified, User entity, users table
// @what: JPA entity for a person account with credentials and lockout state.
// @flow: Used by Users repository, AuthService, PasswordResetService, DemoDataSeeder.
package os.aiworkforce.identity.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/** A person who can sign in. */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Argon2id, encoded with its own parameters. Null for an account that only uses OAuth. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(nullable = false)
    private String locale = "en-AU";

    @Column(nullable = false)
    private String timezone = "UTC";

    public boolean isActive() {
        return "active".equals(status);
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    // @find: failed login, wrong password, lock account, lockout
    /**
     * Records a failed sign-in and locks the account once the threshold is reached.
     *
     * <p>Locking on repeated failure is what makes a password guessable only at the rate a person
     * could type it. The counter resets on success, so an occasional typo never accumulates
     * towards a lockout weeks later.
     */
    public void recordFailedLogin(int threshold, Duration lockFor) {
        failedLoginCount++;
        if (failedLoginCount >= threshold) {
            lockedUntil = Instant.now().plus(lockFor);
        }
    }

    public void recordSuccessfulLogin() {
        clearLockout();
        lastLoginAt = Instant.now();
    }

    // @find: unlock account, reset lockout
    /** Forgets earlier failures, as a successful sign-in or a password reset does. */
    public void clearLockout() {
        failedLoginCount = 0;
        lockedUntil = null;
    }

    // ---- Accessors -----------------------------------------------------------------------

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Stores a hash without recording a change of password.
     *
     * <p>For registration, seeding and the silent re-hash on sign-in when the cost parameters have
     * been raised. None of those is the person choosing a new password, and stamping
     * {@code password_changed_at} on them would make the column useless for its one purpose:
     * telling apart what was issued before a real change from what was issued after it.
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    // @find: change password, new password, password changed at
    /** A new password chosen by the person, or set through a reset link. */
    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.passwordChangedAt = Instant.now();
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}

// @find: error codes, error code list, http status per code, retryable, not found, forbidden, validation failed, rate limited, default message
// @what: Catalogue of every deliberate failure code with its HTTP status, retry flag and safe message.
// @flow: Used by ApiException, ProblemResponse and the web client
package os.aiworkforce.platform.error;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every deliberate failure in the platform has a code here.
 *
 * <p>The web client and the tests switch on the code, never on the sentence, so wording can be
 * improved without breaking a caller. Each constant carries its HTTP status, whether repeating
 * the same request unchanged could succeed, and a default sentence that is safe to show a
 * person: it names what happened without leaking which account exists, which row was found, or
 * what an internal service is called.
 */
public enum ErrorCode {

    // ---- Request shape -------------------------------------------------------------------
    VALIDATION_FAILED(422, false, "Some of the values supplied are not valid."),
    MALFORMED_REQUEST(400, false, "The request could not be read."),
    UNSUPPORTED_MEDIA_TYPE(415, false, "That content type is not accepted here."),
    PAYLOAD_TOO_LARGE(413, false, "The request is larger than this endpoint accepts."),
    METHOD_NOT_ALLOWED(405, false, "That method is not available on this endpoint."),

    // ---- Authentication ------------------------------------------------------------------
    NOT_AUTHENTICATED(401, false, "Sign in to continue."),
    INVALID_CREDENTIALS(401, false, "The email address or password is incorrect."),
    TOKEN_EXPIRED(401, false, "The session has expired. Sign in again."),
    TOKEN_REVOKED(401, false, "The session is no longer valid. Sign in again."),
    TOKEN_INVALID(401, false, "The session could not be verified. Sign in again."),
    SESSION_REUSE_DETECTED(401, false, "The session was ended for safety. Sign in again."),
    ACCOUNT_LOCKED(423, false, "The account is locked after repeated failed sign-ins."),
    ACCOUNT_DISABLED(403, false, "The account is disabled."),
    MFA_REQUIRED(401, false, "A second factor is required to continue."),
    EMAIL_NOT_VERIFIED(403, false, "Confirm the email address before continuing."),

    // ---- Authorisation -------------------------------------------------------------------
    PERMISSION_DENIED(403, false, "This account does not have permission for that action."),
    ORGANISATION_MISMATCH(403, false, "That item belongs to a different workspace."),
    MEMBERSHIP_INACTIVE(403, false, "This account is no longer a member of the workspace."),
    PERMISSION_SET_STALE(401, false, "Permissions changed. Sign in again to refresh them."),
    LAST_OWNER_PROTECTED(409, false, "A workspace must keep at least one owner."),
    SELF_ACTION_FORBIDDEN(409, false, "An account cannot perform that action on itself."),

    // ---- Resources -----------------------------------------------------------------------
    NOT_FOUND(404, false, "That item does not exist, or is not visible to this account."),
    ALREADY_EXISTS(409, false, "That item already exists."),
    CONFLICT(409, false, "The item changed while this request was in flight."),
    VERSION_CONFLICT(409, false, "Someone else saved a change first. Reload and try again."),
    IMMUTABLE_RESOURCE(409, false, "That item has been used and can no longer be changed."),
    RESOURCE_IN_USE(409, false, "That item is in use and cannot be removed."),

    // ---- Policy and limits ---------------------------------------------------------------
    RATE_LIMITED(429, true, "Too many requests. Try again shortly."),
    QUOTA_EXCEEDED(429, false, "The workspace has reached its quota for this resource."),
    BUDGET_EXCEEDED(402, false, "The workspace language model budget is exhausted."),
    APPROVAL_REQUIRED(202, false, "This action is waiting for approval."),
    APPROVAL_EXPIRED(409, false, "The approval window closed before a decision was made."),
    APPROVAL_ALREADY_DECIDED(409, false, "That approval has already been decided."),
    QUESTION_ALREADY_ANSWERED(409, false, "That question has already been answered."),
    QUESTION_CLOSED(409, false, "That question is no longer open."),
    POLICY_VIOLATION(403, false, "Workspace policy does not allow that action."),

    // ---- Upstream dependencies -----------------------------------------------------------
    UPSTREAM_UNAVAILABLE(503, true, "A required service is unavailable. Try again shortly."),
    UPSTREAM_TIMEOUT(504, true, "A required service did not respond in time."),
    UPSTREAM_ERROR(502, true, "A required service returned an unexpected response."),
    CIRCUIT_OPEN(503, true, "A required service is failing and calls are paused."),
    DEPENDENCY_DEGRADED(503, true, "The platform is running with reduced capability."),
    DEPENDENCY_UNAVAILABLE(503, true, "A platform service was briefly unreachable. Try again in a minute."),

    // ---- Language models -----------------------------------------------------------------
    NO_MODEL_AVAILABLE(503, true, "No language model in the routing policy is available."),
    MODEL_NOT_FOUND(404, false, "The selected model is not available from that provider."),
    PROVIDER_NOT_CONFIGURED(409, false, "That provider has not been configured for this workspace."),
    PROVIDER_CREDENTIAL_INVALID(502, false, "The provider rejected the stored credential."),
    /*
     * The provider's own account is empty or has hit a cap set at the provider. Distinct from
     * BUDGET_EXCEEDED, which is this workspace's cap in this platform: the remedies differ (top up
     * the vendor account, or raise the cap here), so the codes must too.
     */
    PROVIDER_QUOTA_EXHAUSTED(
            502, false, "The model provider account has used up its quota or reached its spending limit."),
    CONTEXT_LENGTH_EXCEEDED(422, false, "The conversation is too long for the selected model."),
    CONTENT_FILTERED(422, false, "The model declined to answer this request."),
    OUTPUT_TRUNCATED(200, false, "The answer was cut short by the output limit."),
    TOOL_CALL_MALFORMED(502, true, "The model returned a tool call that could not be read."),
    CAPABILITY_UNSUPPORTED(409, false, "The selected model does not support what this task needs."),

    // ---- Tools and integrations ----------------------------------------------------------
    TOOL_NOT_GRANTED(403, false, "This agent has not been granted that tool."),
    TOOL_ARGUMENTS_INVALID(422, false, "The tool was called with arguments it cannot accept."),
    TOOL_NOT_FOUND(404, false, "That tool is not offered by the connected server."),
    INTEGRATION_NOT_CONNECTED(409, false, "Connect the integration before using it."),
    INTEGRATION_CONSENT_REQUIRED(409, false, "Reconnect the integration to continue."),
    INTEGRATION_SCOPE_MISSING(403, false, "The integration is missing a permission this tool needs."),
    TOOL_OUTCOME_INDETERMINATE(
            502, false, "The tool did not confirm the result, so the action was not repeated automatically."),
    VOICE_NOT_CONFIGURED(409, false, "No ElevenLabs key is stored for this workspace."),
    CONNECTOR_NOT_LIVE(422, false, "This connector works with practice data only and cannot be connected yet."),
    CONNECTOR_CHECK_FAILED(422, false, "The connector did not accept that token."),

    // ---- Knowledge base ------------------------------------------------------------------
    DOCUMENT_NOT_INDEXABLE(422, false, "That document holds no text that can be indexed."),
    INGESTION_FAILED(500, true, "The document could not be processed."),
    NO_EVIDENCE_FOUND(404, false, "No source document supports an answer to that question."),
    EMBEDDING_DIMENSION_MISMATCH(409, false, "The index was built with a different embedding model."),

    // ---- Catch all -----------------------------------------------------------------------
    INTERNAL_ERROR(500, false, "Something went wrong. The incident has been recorded."),
    NOT_IMPLEMENTED(501, false, "That capability is not available yet."),
    SERVICE_UNAVAILABLE(503, true, "The service is starting up or shutting down.");

    private static final Map<String, ErrorCode> BY_WIRE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::wire, Function.identity()));

    private final int status;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(int status, boolean retryable, String defaultMessage) {
        this.status = status;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    /** HTTP status this code maps to. */
    public int status() {
        return status;
    }

    /**
     * Whether repeating the identical request could succeed.
     *
     * <p>This drives automatic retry in clients and in the model router, so it is a promise, not
     * a hint. A code is retryable only when the failure is about availability rather than about
     * the request itself.
     */
    public boolean retryable() {
        return retryable;
    }

    /** A sentence safe to show a person, with no internal detail in it. */
    public String defaultMessage() {
        return defaultMessage;
    }

    /** The stable lower_snake_case form used on the wire. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ErrorCode fromWire(String wire) {
        ErrorCode code = BY_WIRE.get(wire == null ? "" : wire.toLowerCase(java.util.Locale.ROOT));
        return code == null ? INTERNAL_ERROR : code;
    }
}

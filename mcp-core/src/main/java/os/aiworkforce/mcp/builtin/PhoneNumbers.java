// @find: phone number check, E.164, international format, premium rate numbers blocked, short codes refused, place call number validation, PhoneNumbers
// @what: Checks a number before a call is placed: international E.164 format only, and never a premium-rate or international shared-cost range.
// @flow: Called by PhoneAdapter.placeCall before anything is sent to ElevenLabs
package os.aiworkforce.mcp.builtin;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The numbers an agent may ask to call.
 *
 * <p>E.164 only: a plus sign, a country code and the number, eight to fifteen digits in all, so a
 * short code or an emergency number can never be dialled. Premium-rate and international
 * shared-cost ranges are refused outright, because a call to one costs money by itself and is the
 * classic way an automated dialler is abused.
 */
public final class PhoneNumbers {

    /** International format: a plus sign, a non-zero first digit and 8 to 15 digits in all. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    /* Premium-rate and costly shared ranges, by prefix (country code included). */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "+1900", "+1976", // North America premium
            "+4487", "+4490", "+4491", "+4498", "+4470", // United Kingdom premium and personal numbers
            "+6119", // Australia 19xx premium
            "+64900", // New Zealand premium
            "+49900", "+49137", // Germany premium and televoting
            "+3389", "+3381", "+3382", // France premium and shared cost
            "+34803", "+34806", "+34807", "+34905", // Spain premium
            "+39899", "+39892", "+39895", // Italy premium
            "+91900", // India premium
            "+979", "+881", "+882", "+883", "+870", "+808" // international premium, satellite, shared-cost
            );

    /** Not instantiable. */
    private PhoneNumbers() {}

    /**
     * The number with spaces, dashes, dots and brackets taken out. A null number becomes an empty
     * string. Called first, so that {@link #problem} and the call itself see the same text.
     */
    public static String normalise(String number) {
        return number == null ? "" : number.strip().replaceAll("[\\s().-]", "");
    }

    // @find: is this phone number allowed, refuse premium number
    /**
     * Why {@code number} may not be called, or null when it may. Expects the normalised form.
     *
     * <p>Checks, in order: present, E.164 shape, then the blocked premium and shared-cost prefixes.
     * The message is written for the agent and the person reading the trace. Pure; no network call.
     * The prefix list is a safeguard and is not a complete catalogue of premium ranges.
     */
    public static String problem(String number) {
        if (number == null || number.isBlank()) {
            return "Give the number to call in international format, for example +61 2 9999 0000.";
        }
        if (!E164.matcher(number).matches()) {
            return "The number must be in international format: a plus sign, the country code and the number,"
                    + " for example +61 2 9999 0000. Short codes and emergency numbers cannot be called.";
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (number.startsWith(prefix)) {
                return "That number is in a premium-rate or shared-cost range, which agents may not call.";
            }
        }
        return null;
    }
}

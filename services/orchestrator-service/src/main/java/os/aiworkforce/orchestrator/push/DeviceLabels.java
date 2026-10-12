// @find: device label, browser name from user agent, Chrome on Android, Safari on iPhone, push device name, DeviceLabels
// @what: Turns a browser's User-Agent into a short label a person recognises their device by, such as "Safari on iPhone".
// @flow: Used by PushService when a subscription is saved
package os.aiworkforce.orchestrator.push;

import java.util.Locale;

/**
 * A device as its owner would name it.
 *
 * <p>Only a browser and a platform, from a handful of well-known markers; never the full
 * User-Agent, which is long, changes with every update and says more about the device than the
 * settings page needs. Anything unrecognised reads "This browser".
 */
public final class DeviceLabels {

    /** The label for a User-Agent nothing is recognised in. */
    public static final String UNKNOWN = "This browser";

    private DeviceLabels() {}

    // @find: label a push device
    /** "Chrome on Android", "Safari on iPhone", "Firefox on Windows"; {@link #UNKNOWN} for null or unrecognised text. Pure. */
    public static String fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = browser(ua);
        String platform = platform(ua);
        if (browser == null && platform == null) {
            return UNKNOWN;
        }
        if (browser == null) {
            return "A browser on " + platform;
        }
        return platform == null ? browser : browser + " on " + platform;
    }

    // The browser, in the order that tells them apart: every Chromium browser also says "chrome", and Chrome also says "safari".
    private static String browser(String ua) {
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("samsungbrowser/")) {
            return "Samsung Internet";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/") || ua.contains("chromium/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return null;
    }

    // The platform; an iPad that asks for desktop sites reads as a Mac, which is what it says it is.
    private static String platform(String ua) {
        if (ua.contains("iphone")) {
            return "iPhone";
        }
        if (ua.contains("ipad")) {
            return "iPad";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "Mac";
        }
        if (ua.contains("cros")) {
            return "ChromeOS";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return null;
    }
}

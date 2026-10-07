package os.aiworkforce.llm.model;

import java.util.Objects;
import java.util.Set;

/**
 * A picture a person attached, sent to a model that can look at it.
 *
 * <p>Carried on a {@link ChatMessage} beside its text. Only the OpenAI-compatible family (which
 * includes OpenRouter) is given the bytes today; for every other candidate, and for any model whose
 * row does not say it accepts images, the router replaces the picture with {@link #unreadableNote}
 * so the model says plainly that it could not see it rather than describing something it never saw.
 *
 * @param name the file name the person attached, used in that note and so an answer can cite it
 * @param mediaType {@code image/png}, {@code image/jpeg}, {@code image/webp} or {@code image/gif}
 * @param base64Data the image, base64 encoded, without a {@code data:} prefix
 */
public record ImagePart(String name, String mediaType, String base64Data) {

    /** The formats vision models accept. HEIC and the rest are told to the model as unreadable. */
    public static final Set<String> VIEWABLE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /**
     * About what one picture costs a vision model. Providers count tiles of the image rather than
     * its bytes; a full-size picture comes to roughly this many tokens on the common models, and the
     * estimate is used only to decide whether a request fits a window, so it errs high.
     */
    public static final int APPROXIMATE_TOKENS = 1_200;

    public ImagePart {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(base64Data, "base64Data");
        name = name == null || name.isBlank() ? "image" : name;
    }

    public static boolean isViewable(String mediaType) {
        return mediaType != null && VIEWABLE_TYPES.contains(mediaType.toLowerCase(java.util.Locale.ROOT));
    }

    /** The URL form the OpenAI-compatible APIs take an inline image in. */
    public String dataUrl() {
        return "data:" + mediaType + ";base64," + base64Data;
    }

    /**
     * What a model that cannot see this picture is told in its place.
     *
     * @param model the model answering, as it is named to people, or null when unknown
     */
    public String unreadableNote(String model) {
        String who = model == null || model.isBlank() ? "The model answering" : model;
        return "[The attached image \"" + name + "\" could not be shown to you: " + who
                + " cannot read images. Tell the person plainly that you could not see this image with the"
                + " current model, and that choosing a vision-capable model in Model routing would let an"
                + " agent read it. Say this in your answer rather than asking the person a question about it,"
                + " answer whatever else you can, and do not describe or guess what the image shows.]";
    }
}

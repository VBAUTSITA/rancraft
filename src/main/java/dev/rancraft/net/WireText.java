package dev.rancraft.net;

/**
 * Keeps strings inside the caps the payload codecs write them with.
 *
 * <p>{@code FriendlyByteBuf.writeUtf(text, max)} throws on a string longer than {@code max}
 * chars. For a server-to-client payload that is not a dropped packet but a dropped player: the
 * encoder rethrows, the connection closes with "Internal Exception", and the same payload does it
 * again after every reconnect. So every string a payload writes is clamped first, in the payload,
 * where the cap lives. The sources are held to the same caps too (see
 * {@link dev.rancraft.rf.CellParams#MAX_BAND_ID_LENGTH}); this is the backstop.
 */
final class WireText {

    private WireText() {
    }

    /**
     * {@code text} cut to at most {@code maxChars} chars; {@code null} reads as {@code ""}. A cut is
     * never made between the two halves of a surrogate pair, so a clamped string is still valid
     * UTF-16 and never grows when encoded.
     */
    static String clamp(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}

package pl.ks.profiling.safepoint.analyzer.cli.tui;

final class Formats {
    private Formats() {
    }

    /**
     * The text cut or padded with spaces to the given width.
     */
    static String pad(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() > width) {
            return width <= 2 ? text.substring(0, width) : text.substring(0, width - 2) + "..";
        }
        return text + " ".repeat(width - text.length());
    }
}

package pl.ks.profiling.safepoint.analyzer.cli.tui;

import com.googlecode.lanterna.TextColor;

/**
 * Colors of the console views - the dark theme of the toolkit web pages, mapped to the 256 terminal colors.
 */
final class Colors {
    static final TextColor BACKGROUND = TextColor.Indexed.fromRGB(0x0f, 0x17, 0x2a);
    static final TextColor SIDEBAR = TextColor.Indexed.fromRGB(0x02, 0x06, 0x17);
    static final TextColor BAR = TextColor.Indexed.fromRGB(0x1e, 0x1b, 0x4b);
    static final TextColor STATUS = TextColor.Indexed.fromRGB(0x1e, 0x29, 0x3b);
    static final TextColor TABLE_HEADER = TextColor.Indexed.fromRGB(0x27, 0x35, 0x48);
    static final TextColor SELECTED = TextColor.Indexed.fromRGB(0x4f, 0x46, 0xe5);
    static final TextColor SELECTED_UNFOCUSED = TextColor.Indexed.fromRGB(0x33, 0x41, 0x55);
    static final TextColor TITLE = TextColor.Indexed.fromRGB(0xf1, 0xf5, 0xf9);
    static final TextColor TEXT = TextColor.Indexed.fromRGB(0xcb, 0xd5, 0xe1);
    static final TextColor MUTED = TextColor.Indexed.fromRGB(0x94, 0xa3, 0xb8);
    static final TextColor HEADING = TextColor.Indexed.fromRGB(0xa5, 0xb4, 0xfc);
    static final TextColor CHART = TextColor.Indexed.fromRGB(0xfd, 0xba, 0x74);

    private Colors() {
    }
}

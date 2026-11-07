package pl.ks.profiling.safepoint.analyzer.standalone;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import pl.ks.profiling.xchart.commons.ChartTheme;

import java.awt.Color;
import java.util.Map;

/**
 * Colors of the application. The standard components get them from the FlatLaf look and feel, the components
 * styled by the application (sidebar, page titles, tables, charts) from the fields below.
 */
enum Theme {
    LIGHT(ChartTheme.DEFAULT,
            Color.WHITE,
            new Color(30, 41, 59),
            new Color(80, 90, 100),
            new Color(100, 100, 100),
            new Color(100, 116, 139),
            new Color(130, 140, 155),
            new Color(245, 247, 250),
            new Color(218, 222, 230),
            new Color(232, 236, 242),
            new Color(219, 234, 254),
            new Color(30, 64, 175),
            Color.WHITE,
            new Color(245, 247, 250),
            new Color(228, 232, 238),
            new Color(219, 234, 254),
            new Color(30, 41, 59),
            new Color(248, 250, 252),
            new Color(71, 85, 105),
            new Color(248, 250, 252)),
    // The colors of the dark theme of the toolkit web pages (/css/app.css)
    DARK(ChartTheme.DARK,
            Color.decode("#1e293b"),
            Color.decode("#f1f5f9"),
            Color.decode("#94a3b8"),
            Color.decode("#94a3b8"),
            Color.decode("#94a3b8"),
            Color.decode("#64748b"),
            Color.decode("#0f172a"),
            Color.decode("#334155"),
            Color.decode("#273548"),
            Color.decode("#312e81"),
            Color.decode("#c7d2fe"),
            Color.decode("#1e293b"),
            Color.decode("#273548"),
            Color.decode("#334155"),
            Color.decode("#312e81"),
            Color.decode("#e0e7ff"),
            Color.decode("#0f172a"),
            Color.decode("#94a3b8"),
            Color.decode("#0f172a"));

    final ChartTheme chartTheme;
    final Color contentBackground;
    final Color heading;
    final Color description;
    final Color subtitle;
    final Color hint;
    final Color sidebarLabel;
    final Color sidebarBackground;
    final Color border;
    final Color sidebarHover;
    final Color selectedBackground;
    final Color selectedForeground;
    final Color tableEvenRow;
    final Color tableOddRow;
    final Color tableGrid;
    final Color tableSelectionBackground;
    final Color tableSelectionForeground;
    final Color tableHeaderBackground;
    final Color tableHeaderForeground;
    // Toolbars and the text of the GC logs
    final Color panelBackground;

    Theme(ChartTheme chartTheme, Color contentBackground, Color heading, Color description, Color subtitle, Color hint,
          Color sidebarLabel, Color sidebarBackground, Color border, Color sidebarHover, Color selectedBackground,
          Color selectedForeground, Color tableEvenRow, Color tableOddRow, Color tableGrid, Color tableSelectionBackground,
          Color tableSelectionForeground, Color tableHeaderBackground, Color tableHeaderForeground, Color panelBackground) {
        this.chartTheme = chartTheme;
        this.contentBackground = contentBackground;
        this.heading = heading;
        this.description = description;
        this.subtitle = subtitle;
        this.hint = hint;
        this.sidebarLabel = sidebarLabel;
        this.sidebarBackground = sidebarBackground;
        this.border = border;
        this.sidebarHover = sidebarHover;
        this.selectedBackground = selectedBackground;
        this.selectedForeground = selectedForeground;
        this.tableEvenRow = tableEvenRow;
        this.tableOddRow = tableOddRow;
        this.tableGrid = tableGrid;
        this.tableSelectionBackground = tableSelectionBackground;
        this.tableSelectionForeground = tableSelectionForeground;
        this.tableHeaderBackground = tableHeaderBackground;
        this.tableHeaderForeground = tableHeaderForeground;
        this.panelBackground = panelBackground;
    }

    FlatLaf createLookAndFeel() {
        if (this == LIGHT) {
            return new FlatLightLaf();
        }
        FlatDarkLaf lookAndFeel = new FlatDarkLaf();
        // Slate background and indigo accent, as in the dark theme of the web pages
        lookAndFeel.setExtraDefaults(Map.of(
                "@background", "#1e293b",
                "@accentColor", "#6366f1"
        ));
        return lookAndFeel;
    }
}

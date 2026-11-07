package pl.ks.profiling.xchart.commons;

import org.knowm.xchart.style.AxesChartStyler;
import org.knowm.xchart.style.Styler;

import java.awt.Color;

/**
 * Colors of the charts drawn by {@link XChartCreator}.
 */
public enum ChartTheme {
    /**
     * XChart defaults on a white background - the look of the standalone application.
     */
    DEFAULT(Color.WHITE, null, null, null, null),
    /**
     * Light theme of the toolkit web pages (the colors of /css/app.css).
     */
    LIGHT(Color.decode("#ffffff"), Color.decode("#0f172a"), Color.decode("#64748b"), Color.decode("#e2e8f0"), null),
    /**
     * Dark theme of the toolkit web pages (the colors of /css/app.css).
     */
    DARK(Color.decode("#1e293b"), Color.decode("#cbd5e1"), Color.decode("#94a3b8"), Color.decode("#334155"), Color.decode("#f8fafc"));

    private final Color background;
    // null - XChart defaults for everything but the background
    private final Color text;
    private final Color mutedText;
    private final Color lines;
    // null - XChart default; the labels of the pie chart are drawn on the slices
    private final Color annotations;

    ChartTheme(Color background, Color text, Color mutedText, Color lines, Color annotations) {
        this.background = background;
        this.text = text;
        this.mutedText = mutedText;
        this.lines = lines;
        this.annotations = annotations;
    }

    void apply(Styler styler) {
        styler.setChartBackgroundColor(background);
        if (text == null) {
            return;
        }
        styler.setPlotBackgroundColor(background);
        styler.setPlotBorderColor(lines);
        styler.setChartFontColor(text);
        styler.setChartTitleBoxBackgroundColor(background);
        styler.setChartTitleBoxBorderColor(background);
        styler.setLegendBackgroundColor(background);
        styler.setLegendBorderColor(lines);
        if (annotations != null) {
            styler.setAnnotationsFontColor(annotations);
        }
        if (styler instanceof AxesChartStyler axesStyler) {
            axesStyler.setAxisTickLabelsColor(mutedText);
            axesStyler.setAxisTickMarksColor(lines);
            axesStyler.setPlotGridLinesColor(lines);
        }
    }
}

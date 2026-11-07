package pl.ks.profiling.safepoint.analyzer.cli;

import org.knowm.xchart.BitmapEncoder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import pl.ks.profiling.gui.commons.Chart;
import pl.ks.profiling.gui.commons.Page;
import pl.ks.profiling.gui.commons.PageContent;
import pl.ks.profiling.safepoint.analyzer.commons.shared.ParsingMode;
import pl.ks.profiling.safepoint.analyzer.commons.shared.report.JvmLogFile;
import pl.ks.profiling.xchart.commons.ChartTheme;
import pl.ks.profiling.xchart.commons.XChartCreator;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * HTML report of the parsed logs with the look of the toolkit web pages (light and dark theme). Next to report.html
 * there are the charts as images - each one in both themes - and the stylesheets and scripts the page needs, so the
 * directory can be opened anywhere, without the application.
 */
class HtmlReport {
    static final String REPORT_FILE = "report.html";
    static final String DARK_SUFFIX = "-dark";

    private static final int CHART_WIDTH = 1200;
    // Copied from the classpath (web-commons and gc-logs-gui-commons, where they are /public resources)
    private static final List<String> ASSETS = List.of("css/app.css", "css/gc-logs.css", "js/theme.js");

    private final Path directory;
    private final String logFileName;
    private final ParsingMode parsingMode;
    private final XChartCreator xChartCreator = new XChartCreator(new PresentationFontProviderCli());

    record Summary(int pages, int charts, int tables) {
    }

    HtmlReport(Path directory, String logFileName, ParsingMode parsingMode) {
        this.directory = directory;
        this.logFileName = logFileName;
        this.parsingMode = parsingMode;
    }

    Summary write(JvmLogFile jvmLogFile) throws IOException {
        List<Page> pages = jvmLogFile.getPages();
        Files.createDirectories(directory.resolve("charts"));
        copyAssets();
        Map<PageContent, String> chartImages = drawCharts(pages);
        writeHtml(pages, chartImages);

        int tables = (int) pages.stream()
                .flatMap(page -> page.getPageContents().stream())
                .filter(content -> content.getType() == PageContent.ContentType.TABLE)
                .count();
        return new Summary(pages.size(), chartImages.size(), tables);
    }

    private void copyAssets() throws IOException {
        for (String asset : ASSETS) {
            try (InputStream inputStream = HtmlReport.class.getClassLoader().getResourceAsStream("public/" + asset)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Missing resource public/" + asset);
                }
                Path target = directory.resolve(asset);
                Files.createDirectories(target.getParent());
                Files.copy(inputStream, target);
            }
        }
    }

    /**
     * @return path of the light theme image (without the extension) of every chart that could be drawn
     */
    private Map<PageContent, String> drawCharts(List<Page> pages) {
        List<Chart> charts = pages.stream()
                .flatMap(page -> page.getPageContents().stream())
                .filter(content -> content.getType() == PageContent.ContentType.CHART)
                .map(Chart.class::cast)
                .toList();
        List<Integer> drawn = IntStream.range(0, charts.size())
                .parallel()
                .filter(index -> draw(charts.get(index), imagePath(index)))
                .boxed()
                .toList();

        // A chart can be equal to another one, but each of them has its own image
        Map<PageContent, String> chartImages = new IdentityHashMap<>();
        drawn.forEach(index -> chartImages.put(charts.get(index), imagePath(index)));
        return chartImages;
    }

    private static String imagePath(int index) {
        return "charts/chart-" + (index + 1);
    }

    private boolean draw(Chart chart, String imagePath) {
        try {
            // The title is a heading of the page, so it is not repeated on the image
            for (ChartTheme theme : List.of(ChartTheme.LIGHT, ChartTheme.DARK)) {
                org.knowm.xchart.internal.chartpart.Chart<?, ?> image = switch (chart.getChartType()) {
                    case PIE -> xChartCreator.createPieChart(chart, "", CHART_WIDTH, theme);
                    case LINE, POINTS, POINTS_OR_LINE -> xChartCreator.createXyChart(chart, "", CHART_WIDTH, theme);
                };
                String file = imagePath + (theme == ChartTheme.DARK ? DARK_SUFFIX : "");
                BitmapEncoder.saveBitmap(image, directory.resolve(file).toString(), BitmapEncoder.BitmapFormat.PNG);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Chart '" + chart.getTitle() + "' cannot be drawn: " + e);
            return false;
        }
    }

    private void writeHtml(List<Page> pages, Map<PageContent, String> chartImages) throws IOException {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        Context context = new Context(Locale.ENGLISH);
        context.setVariable("pages", pages);
        context.setVariable("chartImages", chartImages);
        context.setVariable("darkSuffix", DARK_SUFFIX);
        context.setVariable("logFileName", logFileName);
        context.setVariable("parsingMode", parsingMode);
        try (Writer writer = Files.newBufferedWriter(directory.resolve(REPORT_FILE), StandardCharsets.UTF_8)) {
            engine.process("gc-logs-report", context, writer);
        }
    }
}

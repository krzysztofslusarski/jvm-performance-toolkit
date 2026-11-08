package pl.ks.profiling.safepoint.analyzer.cli.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import org.knowm.xchart.BitmapEncoder;
import pl.ks.profiling.gui.commons.Chart;
import pl.ks.profiling.gui.commons.Page;
import pl.ks.profiling.safepoint.analyzer.commons.shared.report.JvmLogFile;
import pl.ks.profiling.xchart.commons.ChartTheme;
import pl.ks.profiling.xchart.commons.PresentationFontProvider;
import pl.ks.profiling.xchart.commons.XChartCreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * Full screen console viewer of the report: the pages on the left, the chosen page on the right. The tables are
 * scrolled, a chart is shown on demand as an image drawn by the terminal - the one chosen from the charts of the page.
 */
public class GcLogsTui {
    private static final String KEY_HINTS =
            "↑↓ move  Tab/←→ page list/page  PgUp/PgDn scroll  Enter/c chart  1-9 chart  ? help  q quit";
    private static final String CHART_KEY_HINTS = "←→ previous/next chart  o open in the image viewer  Esc/q back";
    // Size of a terminal cell assumed for drawing the charts - the terminal scales the image to the cells anyway
    private static final int CELL_WIDTH_PX = 9;
    private static final int CELL_HEIGHT_PX = 19;

    private final JvmLogFile jvmLogFile;
    private final String title;
    private final TerminalImages terminalImages;
    private final ChartTheme chartTheme;
    private final XChartCreator xChartCreator;

    private Screen screen;
    private OutputStream rawOutput;
    private WindowBasedTextGUI gui;
    private BasicWindow window;
    private Label headerLabel;
    private Label footerLabel;
    private PageListView pageList;
    private PageView pageView;

    /**
     * @param title the analyzed file and the parser, shown in the header
     */
    public GcLogsTui(JvmLogFile jvmLogFile, String title, TerminalImages terminalImages, ChartTheme chartTheme,
                     PresentationFontProvider fontProvider) {
        this.jvmLogFile = jvmLogFile;
        this.title = title;
        this.terminalImages = terminalImages;
        this.chartTheme = chartTheme;
        this.xChartCreator = new XChartCreator(fontProvider);
    }

    public void run() throws IOException {
        // A text terminal only - lanterna would open a Swing window instead when there is no console
        run(new TerminalScreen(new DefaultTerminalFactory().setForceTextTerminal(true).createTerminal()), System.out);
    }

    /**
     * @param rawOutput the stream of the terminal, the images are written to it directly
     */
    void run(Screen screen, OutputStream rawOutput) throws IOException {
        this.screen = screen;
        this.rawOutput = rawOutput;
        screen.startScreen();
        screen.setCursorPosition(null);
        try {
            gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));
            buildWindow();
            gui.addWindow(window);
            showPage(0);
            gui.waitForWindowToClose(window);
        } finally {
            screen.stopScreen();
        }
    }

    private void buildWindow() {
        window = new BasicWindow();
        window.setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS, Window.Hint.NO_POST_RENDERING));

        headerLabel = new Label("");
        headerLabel.setBackgroundColor(Colors.BAR);
        headerLabel.setForegroundColor(Colors.TITLE);
        footerLabel = new Label("");
        footerLabel.setBackgroundColor(Colors.STATUS);
        footerLabel.setForegroundColor(Colors.TEXT);

        pageList = new PageListView(jvmLogFile.getPages(), this::showPage, () -> screen.getTerminalSize().getColumns());
        pageView = new PageView();

        Panel content = new Panel(new BorderLayout());
        content.addComponent(pageList, BorderLayout.Location.LEFT);
        content.addComponent(pageView, BorderLayout.Location.CENTER);

        Panel root = new Panel(new BorderLayout());
        root.addComponent(headerLabel, BorderLayout.Location.TOP);
        root.addComponent(content, BorderLayout.Location.CENTER);
        root.addComponent(footerLabel, BorderLayout.Location.BOTTOM);
        window.setComponent(root);
        pageList.takeFocus();

        window.addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onUnhandledInput(Window basePane, KeyStroke keyStroke, AtomicBoolean hasBeenHandled) {
                hasBeenHandled.set(handleGlobalKey(keyStroke));
            }

            @Override
            public void onResized(Window window, TerminalSize oldSize, TerminalSize newSize) {
                updateLabels();
            }
        });
    }

    private void showPage(int index) {
        pageView.setPage(jvmLogFile.getPages().get(index));
        updateLabels();
    }

    private boolean handleGlobalKey(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.F1) {
            showHelp();
            return true;
        }
        if (keyStroke.getKeyType() == KeyType.Enter) {
            chooseChart();
            return true;
        }
        if (keyStroke.getKeyType() == KeyType.Escape && pageView.isFocused()) {
            pageList.takeFocus();
            return true;
        }
        if (keyStroke.getKeyType() != KeyType.Character) {
            return false;
        }
        char character = Character.toLowerCase(keyStroke.getCharacter());
        if (character >= '1' && character <= '9') {
            showChart(pageView.getCharts(), character - '1');
            return true;
        }
        switch (character) {
            case 'c' -> chooseChart();
            case '?' -> showHelp();
            case 'q' -> window.close();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void chooseChart() {
        List<Chart> charts = pageView.getCharts();
        if (charts.isEmpty()) {
            MessageDialog.showMessageDialog(gui, "Charts", "There are no charts on this page.", MessageDialogButton.OK);
            return;
        }
        if (charts.size() == 1) {
            showChart(charts, 0);
            return;
        }
        String[] items = IntStream.range(0, charts.size())
                .mapToObj(index -> (index + 1) + ". " + chartTitle(charts.get(index)))
                .toArray(String[]::new);
        String chosen = ListSelectDialog.showDialog(gui, "Charts", "Choose the chart to show", items);
        if (chosen != null) {
            showChart(charts, List.of(items).indexOf(chosen));
        }
    }

    /**
     * Shows the chart as an image until it is closed - outside of the windows of lanterna, which knows nothing about
     * the images, so the whole screen is drawn again afterwards.
     */
    private void showChart(List<Chart> charts, int index) {
        if (index < 0 || index >= charts.size()) {
            return;
        }
        try {
            String message = null;
            boolean redraw = true;
            while (true) {
                if (redraw) {
                    drawChart(charts, index, message);
                    redraw = false;
                }
                KeyStroke key = screen.pollInput();
                if (key == null) {
                    if (screen.doResizeIfNecessary() != null) {
                        redraw = true;
                    }
                    Thread.sleep(40);
                    continue;
                }
                message = null;
                KeyType type = key.getKeyType();
                if (type == KeyType.Escape || type == KeyType.Backspace || type == KeyType.EOF
                        || (type == KeyType.Character && Character.toLowerCase(key.getCharacter()) == 'q')) {
                    break;
                } else if (type == KeyType.ArrowLeft && index > 0) {
                    index--;
                    redraw = true;
                } else if (type == KeyType.ArrowRight && index < charts.size() - 1) {
                    index++;
                    redraw = true;
                } else if (type == KeyType.Character && Character.toLowerCase(key.getCharacter()) == 'o') {
                    message = openInViewer(charts.get(index));
                    redraw = true;
                } else if (type == KeyType.Character && Character.isDigit(key.getCharacter())) {
                    int chosen = key.getCharacter() - '1';
                    if (chosen >= 0 && chosen < charts.size()) {
                        index = chosen;
                        redraw = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            MessageDialog.showMessageDialog(gui, "Error", "The chart cannot be shown: " + e.getMessage(), MessageDialogButton.OK);
        } finally {
            restoreScreen();
        }
    }

    private void drawChart(List<Chart> charts, int index, String message) throws IOException {
        Chart chart = charts.get(index);
        TerminalSize size = screen.getTerminalSize();
        int columns = size.getColumns();
        int imageRows = Math.max(1, size.getRows() - 2);

        screen.clear();
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setBackgroundColor(Colors.BAR);
        graphics.setForegroundColor(Colors.TITLE);
        graphics.enableModifiers(SGR.BOLD);
        graphics.putString(0, 0, Formats.pad(" Chart " + (index + 1) + " of " + charts.size() + ": " + chartTitle(chart)
                + " | " + currentPage().getFullName(), columns));
        graphics.clearModifiers();
        graphics.setBackgroundColor(Colors.STATUS);
        graphics.setForegroundColor(Colors.TEXT);
        graphics.putString(0, size.getRows() - 1, Formats.pad(" " + (message != null ? message : CHART_KEY_HINTS), columns));

        if (!terminalImages.isSupported()) {
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            graphics.setForegroundColor(Colors.TEXT);
            Path file = writeChart(chart);
            List<String> lines = List.of(
                    "This terminal cannot draw images - the charts are shown by terminals of the iTerm2 or kitty",
                    "image protocols: iTerm2, kitty, WezTerm, Ghostty, VS Code. A terminal can be chosen with the",
                    "--images=iterm2 or --images=kitty option.",
                    "",
                    "The chart is saved to " + file,
                    "Press o to open it in the image viewer.");
            for (int i = 0; i < lines.size() && i + 2 < size.getRows() - 1; i++) {
                graphics.putString(1, i + 2, lines.get(i));
            }
            screen.refresh(Screen.RefreshType.COMPLETE);
            return;
        }

        screen.refresh(Screen.RefreshType.COMPLETE);
        byte[] png = renderChart(chart, columns, imageRows);
        if (screen instanceof TerminalScreen terminalScreen) {
            terminalScreen.getTerminal().flush();
        }
        rawOutput.write(terminalImages.removeImages());
        // The image is drawn at the cursor: the first row below the header
        rawOutput.write("\u001b[2;1H".getBytes(StandardCharsets.US_ASCII));
        rawOutput.write(terminalImages.drawImage(png, columns, imageRows));
        rawOutput.flush();
    }

    private void restoreScreen() {
        try {
            rawOutput.write(terminalImages.removeImages());
            rawOutput.flush();
            screen.clear();
            gui.updateScreen();
            // Every cell is written again, also the ones covered by the image
            screen.refresh(Screen.RefreshType.COMPLETE);
        } catch (IOException e) {
            // The next refresh of the screen draws it again
        }
    }

    private byte[] renderChart(Chart chart, int columns, int rows) throws IOException {
        int width = Math.max(480, Math.min(2800, columns * CELL_WIDTH_PX));
        int height = Math.max(320, Math.min(1800, rows * CELL_HEIGHT_PX));
        // The title is in the header of the view, it is not repeated on the image
        org.knowm.xchart.internal.chartpart.Chart<?, ?> image = switch (chart.getChartType()) {
            case PIE -> xChartCreator.createPieChart(chart, "", width, height, chartTheme);
            case LINE, POINTS, POINTS_OR_LINE -> xChartCreator.createXyChart(chart, "", width, height, chartTheme);
        };
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        BitmapEncoder.saveBitmap(image, png, BitmapEncoder.BitmapFormat.PNG);
        return png.toByteArray();
    }

    private Path writeChart(Chart chart) throws IOException {
        Path file = Files.createTempFile("gc-chart-", ".png");
        file.toFile().deleteOnExit();
        Files.write(file, renderChart(chart, 160, 45));
        return file;
    }

    private String openInViewer(Chart chart) {
        try {
            Path file = writeChart(chart);
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> command = os.contains("mac") ? List.of("open", file.toString())
                    : os.contains("win") ? List.of("cmd", "/c", "start", "", file.toString())
                    : List.of("xdg-open", file.toString());
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return "Opened " + file;
        } catch (IOException e) {
            return "Cannot open the chart: " + e.getMessage();
        }
    }

    private Page currentPage() {
        return jvmLogFile.getPages().get(pageList.getSelectedIndex());
    }

    private static String chartTitle(Chart chart) {
        return chart.getTitle() == null || chart.getTitle().isBlank() ? "chart" : chart.getTitle();
    }

    private void updateLabels() {
        int width = screen.getTerminalSize().getColumns();
        List<Page> pages = jvmLogFile.getPages();
        headerLabel.setText(Formats.pad(" GC log analyzer - " + currentPage().getFullName()
                + " (" + (pageList.getSelectedIndex() + 1) + " of " + pages.size() + ") | " + title, width));
        footerLabel.setText(Formats.pad(" " + KEY_HINTS, width));
    }

    private void showHelp() {
        MessageDialog.showMessageDialog(gui, "Shortcuts", """
                Pages    ↑↓ PgUp PgDn Home End   choose the page, it is shown at once
                         Enter or →              go to the page
                         Tab                     switch between the page list and the page

                Page     ↑↓ PgUp PgDn Home End   scroll (long tables)
                         ← →                     scroll wide tables, their first column stays
                                                 in place; ← at the left edge or Esc goes back
                                                 to the page list
                         Enter or c              show the chart, or choose one of the charts
                         1-9                     show the chart with this number

                Chart    ← →                     previous or next chart of the page
                         1-9                     chart with this number
                         o                       open the chart in the image viewer of the system
                         Esc or q                back to the page

                Other    ? or F1                 this help
                         q                       quit""", MessageDialogButton.OK);
    }
}

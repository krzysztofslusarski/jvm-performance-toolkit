package pl.ks.profiling.safepoint.analyzer.cli.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractInteractableComponent;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.InteractableRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import pl.ks.profiling.gui.commons.Chart;
import pl.ks.profiling.gui.commons.Page;

import java.util.List;

/**
 * A page of the report, scrolled vertically (long tables) and horizontally (wide tables).
 */
class PageView extends AbstractInteractableComponent<PageView> {
    private static final int HORIZONTAL_STEP = 8;

    private Page page;
    private PageDocument document;
    private int documentWidth = -1;
    private int scrollY;
    private int scrollX;
    private int visibleRows = 1;
    private int visibleColumns = 1;

    void setPage(Page page) {
        this.page = page;
        this.document = null;
        this.scrollY = 0;
        this.scrollX = 0;
        invalidate();
    }

    /**
     * Charts of the current page, in the order of their numbers.
     */
    List<Chart> getCharts() {
        return document().getCharts();
    }

    private PageDocument document() {
        int width = Math.max(20, visibleColumns - 2);
        if (document == null || documentWidth != width) {
            document = new PageDocument(page, width);
            documentWidth = width;
        }
        return document;
    }

    @Override
    protected InteractableRenderer<PageView> createDefaultRenderer() {
        return new InteractableRenderer<>() {
            @Override
            public TerminalPosition getCursorLocation(PageView component) {
                return null;
            }

            @Override
            public TerminalSize getPreferredSize(PageView component) {
                return new TerminalSize(80, 25);
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, PageView component) {
                component.paint(graphics);
            }
        };
    }

    @Override
    protected Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
        int lastLine = Math.max(0, document().getLines().size() - visibleRows);
        int lastColumn = maxScroll(document().getScrolledWidth());
        switch (keyStroke.getKeyType()) {
            case ArrowDown -> scrollY = Math.min(lastLine, scrollY + 1);
            case ArrowUp -> scrollY = Math.max(0, scrollY - 1);
            case PageDown -> scrollY = Math.min(lastLine, scrollY + visibleRows - 1);
            case PageUp -> scrollY = Math.max(0, scrollY - visibleRows + 1);
            case Home -> {
                scrollY = 0;
                scrollX = 0;
            }
            case End -> scrollY = lastLine;
            case ArrowRight -> scrollX = Math.min(lastColumn, scrollX + HORIZONTAL_STEP);
            case ArrowLeft -> {
                if (scrollX == 0) {
                    return Interactable.Result.MOVE_FOCUS_LEFT;
                }
                scrollX = Math.max(0, scrollX - HORIZONTAL_STEP);
            }
            default -> {
                return Interactable.Result.UNHANDLED;
            }
        }
        invalidate();
        return Interactable.Result.HANDLED;
    }

    private void paint(TextGUIGraphics graphics) {
        TerminalSize size = graphics.getSize();
        visibleColumns = size.getColumns();
        visibleRows = Math.max(1, size.getRows() - 1);
        graphics.setBackgroundColor(Colors.BACKGROUND);
        graphics.setForegroundColor(Colors.TEXT);
        graphics.fill(' ');
        if (page == null || size.getColumns() < 10 || size.getRows() < 3) {
            return;
        }

        List<PageDocument.Line> lines = document().getLines();
        scrollY = Math.min(scrollY, Math.max(0, lines.size() - visibleRows));
        for (int row = 0; row < visibleRows && scrollY + row < lines.size(); row++) {
            drawLine(graphics, lines.get(scrollY + row), row);
        }
        drawStatus(graphics, lines.size(), size.getRows() - 1);
    }

    private void drawLine(TextGUIGraphics graphics, PageDocument.Line line, int row) {
        graphics.clearModifiers();
        graphics.setBackgroundColor(Colors.BACKGROUND);
        switch (line.style()) {
            case PAGE_TITLE -> {
                graphics.setForegroundColor(Colors.TITLE);
                graphics.enableModifiers(SGR.BOLD);
            }
            case HEADING -> {
                graphics.setForegroundColor(Colors.HEADING);
                graphics.enableModifiers(SGR.BOLD);
            }
            case NOTE, TABLE_RULE -> graphics.setForegroundColor(Colors.MUTED);
            case TABLE_HEADER -> {
                graphics.setBackgroundColor(Colors.TABLE_HEADER);
                graphics.setForegroundColor(Colors.TITLE);
                graphics.enableModifiers(SGR.BOLD);
            }
            case TABLE_FOOTER -> {
                graphics.setForegroundColor(Colors.TITLE);
                graphics.enableModifiers(SGR.BOLD);
            }
            case CHART -> {
                graphics.setForegroundColor(Colors.CHART);
                graphics.enableModifiers(SGR.BOLD);
            }
            default -> graphics.setForegroundColor(Colors.TEXT);
        }
        // Only the tables are scrolled horizontally, as far as their end, their first column stays in place
        String text = line.text();
        int scroll = line.isScrolled() ? Math.min(scrollX, maxScroll(line.tableWidth())) : 0;
        if (scroll > 0) {
            int frozen = Math.min(line.frozen(), text.length());
            int from = frozen + scroll;
            text = text.substring(0, frozen) + (from < text.length() ? text.substring(from) : "");
        }
        int width = visibleColumns - 1;
        if (line.style() == PageDocument.Style.TABLE_HEADER) {
            graphics.fillRectangle(new TerminalPosition(1, row), new TerminalSize(Math.min(width, text.length()), 1), ' ');
        }
        graphics.putString(1, row, text.length() > width ? text.substring(0, width) : text);
    }

    /**
     * Number of columns a table of the given width is scrolled by to show its end: the text starts at the second
     * column and leaves the last one empty.
     */
    private int maxScroll(int tableWidth) {
        return Math.max(0, tableWidth - (visibleColumns - 2));
    }

    private void drawStatus(TextGUIGraphics graphics, int lineCount, int row) {
        graphics.clearModifiers();
        graphics.setBackgroundColor(Colors.STATUS);
        graphics.setForegroundColor(Colors.TEXT);
        graphics.fillRectangle(new TerminalPosition(0, row), new TerminalSize(visibleColumns, 1), ' ');

        StringBuilder status = new StringBuilder();
        int last = Math.min(lineCount, scrollY + visibleRows);
        status.append(" lines ").append(lineCount == 0 ? 0 : scrollY + 1).append('-').append(last).append(" of ").append(lineCount);
        if (maxScroll(document().getScrolledWidth()) > 0) {
            status.append(" | column ").append(scrollX + 1).append(" of ").append(document().getScrolledWidth()).append(" (← →)");
        }
        int charts = getCharts().size();
        if (charts == 1) {
            status.append(" | 1 chart: Enter or c shows it");
        } else if (charts > 1) {
            status.append(" | ").append(charts).append(" charts: Enter or c to choose, 1-9 shows one");
        }
        graphics.putString(0, row, Formats.pad(status.toString(), visibleColumns));
    }
}

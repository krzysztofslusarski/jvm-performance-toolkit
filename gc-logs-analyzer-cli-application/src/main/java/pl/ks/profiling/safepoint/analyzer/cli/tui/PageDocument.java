package pl.ks.profiling.safepoint.analyzer.cli.tui;

import pl.ks.profiling.gui.commons.Chart;
import pl.ks.profiling.gui.commons.Page;
import pl.ks.profiling.gui.commons.PageContent;
import pl.ks.profiling.gui.commons.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A page of the report as lines of text: the texts are wrapped to the width of the view, the tables are not - they
 * are scrolled horizontally, with their first column (the names) kept in place. The charts are listed by their
 * numbers, an image of the chosen one is shown on demand.
 */
final class PageDocument {
    private static final String COLUMN_SEPARATOR = "  ";

    enum Style {
        PAGE_TITLE,
        TEXT,
        HEADING,
        NOTE,
        TABLE_HEADER,
        TABLE_RULE,
        TABLE_ROW,
        TABLE_FOOTER,
        CHART,
        BLANK
    }

    /**
     * @param chartIndex index of the chart in {@link #getCharts()} for the chart lines, -1 for the other ones
     * @param frozen     length of the beginning of a table line not scrolled horizontally (the first column)
     * @param tableWidth width of the table of the line - all its lines are scrolled by the same number of columns,
     *                   until the end of the table is visible; -1 for the lines which are not scrolled
     */
    record Line(String text, Style style, int chartIndex, int frozen, int tableWidth) {
        static Line of(String text, Style style) {
            return new Line(text, style, -1, 0, -1);
        }

        static Line ofTable(String text, Style style, int frozen, int tableWidth) {
            return new Line(text, style, -1, frozen, tableWidth);
        }

        boolean isScrolled() {
            return tableWidth >= 0;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Chart> charts = new ArrayList<>();
    private int width;
    private int scrolledWidth;

    PageDocument(Page page, int textWidth) {
        int wrapWidth = Math.max(20, textWidth);
        lines.add(Line.of(page.getFullName(), Style.PAGE_TITLE));
        addWrapped(page.getInfo(), Style.TEXT, wrapWidth);
        lines.add(Line.of("", Style.BLANK));

        for (PageContent content : page.getPageContents()) {
            boolean titled = content.getTitle() != null && !content.getTitle().isBlank();
            if (content instanceof Chart chart) {
                // The title of the chart is on its line, not repeated as a heading
                charts.add(chart);
                String title = titled ? content.getTitle() : "chart";
                lines.add(new Line("[" + charts.size() + "] Chart: " + title, Style.CHART, charts.size() - 1, 0, -1));
                addWrapped(content.getInfo(), Style.NOTE, wrapWidth);
            } else if (content instanceof Table table) {
                if (titled) {
                    lines.add(Line.of(content.getTitle(), Style.HEADING));
                }
                addWrapped(content.getInfo(), Style.NOTE, wrapWidth);
                addTable(table);
            }
            lines.add(Line.of("", Style.BLANK));
        }
        for (Line line : lines) {
            width = Math.max(width, line.text().length());
            if (line.isScrolled()) {
                scrolledWidth = Math.max(scrolledWidth, line.tableWidth());
            }
        }
    }

    List<Line> getLines() {
        return lines;
    }

    List<Chart> getCharts() {
        return charts;
    }

    /**
     * Length of the longest line - the tables can be wider than the view.
     */
    int getWidth() {
        return width;
    }

    /**
     * Width of the widest table - the horizontal scrolling ends when its end is visible.
     */
    int getScrolledWidth() {
        return scrolledWidth;
    }

    private void addTable(Table table) {
        List<String> header = table.getHeader() == null ? List.of() : table.getHeader();
        List<List<String>> rows = table.getTable() == null ? List.of() : table.getTable();
        List<String> footer = table.getFooter();

        int columns = header.size();
        for (List<String> row : rows) {
            columns = Math.max(columns, row.size());
        }
        int[] widths = new int[columns];
        measure(widths, header);
        rows.forEach(row -> measure(widths, row));
        if (footer != null) {
            measure(widths, footer);
        }
        int tableWidth = 0;
        for (int columnWidth : widths) {
            tableWidth += columnWidth;
        }
        tableWidth += Math.max(0, columns - 1) * COLUMN_SEPARATOR.length();
        int frozen = columns > 1 ? widths[0] + COLUMN_SEPARATOR.length() : 0;

        if (!header.isEmpty()) {
            lines.add(Line.ofTable(format(header, widths), Style.TABLE_HEADER, frozen, tableWidth));
            lines.add(Line.ofTable("─".repeat(tableWidth), Style.TABLE_RULE, frozen, tableWidth));
        }
        for (List<String> row : rows) {
            lines.add(Line.ofTable(format(row, widths), Style.TABLE_ROW, frozen, tableWidth));
        }
        if (footer != null && !footer.isEmpty()) {
            lines.add(Line.ofTable("─".repeat(tableWidth), Style.TABLE_RULE, frozen, tableWidth));
            lines.add(Line.ofTable(format(footer, widths), Style.TABLE_FOOTER, frozen, tableWidth));
        }
    }

    private static void measure(int[] widths, List<String> cells) {
        for (int i = 0; i < cells.size() && i < widths.length; i++) {
            widths[i] = Math.max(widths[i], cell(cells, i).length());
        }
    }

    /**
     * The first column (names) is aligned to the left, the other ones (numbers) to the right - as in the standalone
     * application.
     */
    private static String format(List<String> cells, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                line.append(COLUMN_SEPARATOR);
            }
            String value = cell(cells, i);
            String padding = " ".repeat(widths[i] - value.length());
            line.append(i == 0 ? value + padding : padding + value);
        }
        return line.toString().stripTrailing();
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size() || cells.get(index) == null) {
            return "";
        }
        // A cell is a single line of the table
        return cells.get(index).strip().replaceAll("\\s+", " ");
    }

    private void addWrapped(String text, Style style, int wrapWidth) {
        if (text == null || text.isBlank()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > wrapWidth) {
                lines.add(Line.of(line.toString(), style));
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(Line.of(line.toString(), style));
        }
    }
}

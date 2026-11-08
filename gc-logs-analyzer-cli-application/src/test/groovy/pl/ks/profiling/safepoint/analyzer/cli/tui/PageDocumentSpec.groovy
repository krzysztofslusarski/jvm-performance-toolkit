package pl.ks.profiling.safepoint.analyzer.cli.tui

import pl.ks.profiling.gui.commons.Chart
import pl.ks.profiling.gui.commons.Page
import pl.ks.profiling.gui.commons.Table
import spock.lang.Specification

import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.CHART
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.HEADING
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.NOTE
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.PAGE_TITLE
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.TABLE_FOOTER
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.TABLE_HEADER
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.TABLE_ROW
import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocument.Style.TEXT

class PageDocumentSpec extends Specification {

    def "aligns the first column of a table to the left and the other ones to the right"() {
        given:
        Table table = Table.builder()
                .title('Pauses')
                .header(['Name', 'Count', 'Time'])
                .table([['Young', '120', '1.50'], ['Full\n GC', '3', '302.11']])
                .footer(['Total', '123', '303.61'])
                .build()

        when:
        PageDocument document = new PageDocument(page([table]), 80)
        List<PageDocument.Line> tableLines = document.lines.findAll { it.isScrolled() }

        then:
        document.lines.find { it.style() == HEADING }.text() == 'Pauses'
        tableLines*.text() == [
                'Name     Count    Time',
                '──────────────────────',
                'Young      120    1.50',
                'Full GC      3  302.11',
                '──────────────────────',
                'Total      123  303.61']
        tableLines*.style() == [TABLE_HEADER, PageDocument.Style.TABLE_RULE, TABLE_ROW, TABLE_ROW,
                                PageDocument.Style.TABLE_RULE, TABLE_FOOTER]
        // The first column stays in place when the table is scrolled horizontally
        tableLines.every { it.frozen() == 'Full GC  '.length() && it.tableWidth() == 22 }
        document.scrolledWidth == 22
        document.charts.isEmpty()
    }

    def "numbers the charts of the page and wraps the texts"() {
        given:
        Chart first = chart('Heap', 'Heap after GC')
        Chart second = chart('', null)

        when:
        PageDocument document = new PageDocument(page([first, second]), 20)

        then:
        document.charts == [first, second]
        document.lines.find { it.style() == PAGE_TITLE }.text() == 'Full name'
        document.lines.findAll { it.style() == TEXT }*.text() == ['Information about', 'the page']
        document.lines.findAll { it.style() == CHART }*.text() == ['[1] Chart: Heap', '[2] Chart: chart']
        document.lines.findAll { it.style() == CHART }*.chartIndex() == [0, 1]
        document.lines.findAll { it.style() == NOTE }*.text() == ['Heap after GC']
        !document.lines.any { it.style() == HEADING }
        document.lines.every { !it.isScrolled() }
    }

    static Page page(List contents) {
        Page.builder()
                .menuName('Menu')
                .fullName('Full name')
                .info('Information about the page')
                .pageContents(contents)
                .build()
    }

    static Chart chart(String title, String info) {
        Chart.builder()
                .chartType(Chart.ChartType.LINE)
                .title(title)
                .info(info)
                .xAxisLabel('Time')
                .yAxisLabel('MB')
                .data([['Time', 'Heap'], [1, 10], [2, 30], [3, 20]] as Object[][])
                .build()
    }
}

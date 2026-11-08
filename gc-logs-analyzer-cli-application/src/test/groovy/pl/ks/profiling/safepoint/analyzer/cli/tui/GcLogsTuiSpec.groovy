package pl.ks.profiling.safepoint.analyzer.cli.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import pl.ks.profiling.gui.commons.Page
import pl.ks.profiling.gui.commons.Table
import pl.ks.profiling.safepoint.analyzer.cli.PresentationFontProviderCli
import pl.ks.profiling.safepoint.analyzer.commons.shared.report.JvmLogFile
import pl.ks.profiling.xchart.commons.ChartTheme
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.charset.StandardCharsets

import static pl.ks.profiling.safepoint.analyzer.cli.tui.PageDocumentSpec.chart

/**
 * Drives the viewer on a virtual terminal, the images are written to a buffer.
 */
@Timeout(60)
class GcLogsTuiSpec extends Specification {
    DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(120, 30))
    ByteArrayOutputStream rawOutput = new ByteArrayOutputStream()
    Thread thread

    def setupSpec() {
        // The charts are drawn without a window system
        System.setProperty('java.awt.headless', 'true')
    }

    def cleanup() {
        if (thread?.isAlive()) {
            terminal.addInput(new KeyStroke('q' as char, false, false))
            thread.join(5000)
        }
    }

    def "shows the pages, scrolls a wide table and shows the chosen chart as an image"() {
        given:
        start(TerminalImages.Protocol.ITERM2)

        expect: 'the first page and the list of the pages'
        waitForScreen { it.contains('GC log analyzer - Summary page (1 of 2)') && it.contains('Charts page') }

        when: 'the second page is chosen and its wide table is scrolled'
        key(KeyType.ArrowDown)
        key(KeyType.ArrowRight)
        key(KeyType.ArrowRight)

        then:
        waitForScreen { it.contains('(2 of 2)') && it.contains('column 9 of') }
        // The first column is not scrolled
        screen().contains('Name         Column 3')

        when: 'the second chart is chosen in the dialog'
        character('c')
        waitForScreen { it.contains('Choose the chart to show') }
        key(KeyType.ArrowDown)
        key(KeyType.Enter)

        then:
        waitForScreen { it.contains('Chart 2 of 2: Old gen') }
        waitFor { images() == 1 }

        when: 'the previous chart is shown'
        key(KeyType.ArrowLeft)

        then:
        waitForScreen { it.contains('Chart 1 of 2: Heap') }
        waitFor { images() == 2 }

        when: 'the chart is closed'
        key(KeyType.Escape)

        then:
        waitForScreen { it.contains('[2] Chart: Old gen') }

        when:
        character('q')

        then:
        waitFor { !thread.isAlive() }
    }

    def "saves the chart to a file when the terminal cannot draw images"() {
        given:
        start(TerminalImages.Protocol.NONE)
        waitForScreen { it.contains('(1 of 2)') }

        when:
        key(KeyType.ArrowDown)
        character('1')

        then:
        waitForScreen { it.contains('Chart 1 of 2: Heap') && it.contains('The chart is saved to') }
        rawOutput.size() == 0
    }

    private void start(TerminalImages.Protocol protocol) {
        JvmLogFile jvmLogFile = new JvmLogFile()
        jvmLogFile.pages = [
                Page.builder().menuName('Summary page').fullName('Summary page').pageContents([]).build(),
                Page.builder().menuName('Charts page').fullName('Charts page').info('Two charts and a table').pageContents([
                        chart('Heap', null),
                        chart('Old gen', null),
                        wideTable()]).build()]
        GcLogsTui tui = new GcLogsTui(jvmLogFile, 'test.log', new TerminalImages(protocol, false), ChartTheme.DARK,
                new PresentationFontProviderCli())
        thread = Thread.start { tui.run(new TerminalScreen(terminal), rawOutput) }
    }

    private static Table wideTable() {
        List<String> header = ['Name'] + (2..20).collect { "Column $it".toString() }
        List<String> row = ['First row'] + (2..20).collect { "value $it".toString() }
        Table.builder().title('Wide table').header(header).table([row]).build()
    }

    private void key(KeyType keyType) {
        terminal.addInput(new KeyStroke(keyType))
    }

    private void character(String character) {
        terminal.addInput(new KeyStroke(character as char, false, false))
    }

    private int images() {
        rawOutput.toString(StandardCharsets.US_ASCII).count(']1337;File=')
    }

    private String screen() {
        TerminalSize size = terminal.terminalSize
        (0..<size.rows).collect { row ->
            (0..<size.columns).collect { column -> terminal.getCharacter(column, row).characterString }.join()
        }.join('\n')
    }

    private void waitForScreen(Closure<Boolean> condition) {
        waitFor { condition(screen()) }
    }

    private static void waitFor(Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assert System.currentTimeMillis() < deadline: 'the condition is not met in 10 seconds'
            Thread.sleep(50)
        }
    }
}

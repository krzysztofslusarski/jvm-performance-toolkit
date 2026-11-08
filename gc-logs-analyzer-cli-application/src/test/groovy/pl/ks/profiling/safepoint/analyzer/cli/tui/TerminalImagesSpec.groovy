package pl.ks.profiling.safepoint.analyzer.cli.tui

import spock.lang.Specification

import java.nio.charset.StandardCharsets

import static pl.ks.profiling.safepoint.analyzer.cli.tui.TerminalImages.Protocol.ITERM2
import static pl.ks.profiling.safepoint.analyzer.cli.tui.TerminalImages.Protocol.KITTY
import static pl.ks.profiling.safepoint.analyzer.cli.tui.TerminalImages.Protocol.NONE

class TerminalImagesSpec extends Specification {
    static final String ESC = '\u001b'

    def "recognizes the image protocol of the terminal by its environment"() {
        expect:
        TerminalImages.detectProtocol(environment) == protocol

        where:
        environment                                     || protocol
        [TERM_PROGRAM: 'iTerm.app']                     || ITERM2
        [TERM_PROGRAM: 'WezTerm']                       || ITERM2
        [TERM_PROGRAM: 'vscode']                        || ITERM2
        [TERM_PROGRAM: 'tmux', LC_TERMINAL: 'iTerm2']   || ITERM2
        [TERM: 'xterm-kitty']                           || KITTY
        [KITTY_WINDOW_ID: '1']                          || KITTY
        [TERM_PROGRAM: 'ghostty']                       || KITTY
        [TERM: 'xterm-ghostty']                         || KITTY
        [TERM_PROGRAM: 'Apple_Terminal']                || NONE
        [:]                                             || NONE
    }

    def "draws an image with the iTerm2 protocol scaled to the cells"() {
        given:
        byte[] png = [1, 2, 3] as byte[]

        when:
        String sequence = text(new TerminalImages(ITERM2, false).drawImage(png, 100, 30))

        then:
        sequence == "${ESC}]1337;File=inline=1;size=3;width=100;height=30;preserveAspectRatio=1:AQID\u0007"
        new TerminalImages(ITERM2, false).removeImages().length == 0
    }

    def "sends an image with the kitty protocol in chunks"() {
        given:
        byte[] png = new byte[5000] // 6668 characters of base64: two chunks

        when:
        String sequence = text(new TerminalImages(KITTY, false).drawImage(png, 100, 30))
        List<String> chunks = sequence.split(ESC + '_G').findAll { !it.isEmpty() }

        then:
        chunks.size() == 2
        chunks[0].startsWith('a=T,f=100,c=100,r=30,C=1,q=2,m=1;')
        chunks[1].startsWith('m=0;')
        chunks.every { it.endsWith(ESC + '\\') }
        text(new TerminalImages(KITTY, false).removeImages()) == "${ESC}_Ga=d,q=2${ESC}\\"
    }

    def "passes the sequences through tmux"() {
        expect:
        text(new TerminalImages(KITTY, true).removeImages()) == "${ESC}Ptmux;${ESC}${ESC}_Ga=d,q=2${ESC}${ESC}\\${ESC}\\"
        text(TerminalImages.of(ITERM2, [TMUX: '/tmp/tmux-501/default']).drawImage([1] as byte[], 10, 5))
                .startsWith("${ESC}Ptmux;${ESC}${ESC}]1337;File=")
    }

    def "draws nothing without an image protocol"() {
        expect:
        !TerminalImages.of(NONE, [:]).isSupported()
        TerminalImages.of(NONE, [:]).drawImage([1] as byte[], 10, 5).length == 0
    }

    private static String text(byte[] bytes) {
        new String(bytes, StandardCharsets.US_ASCII)
    }
}

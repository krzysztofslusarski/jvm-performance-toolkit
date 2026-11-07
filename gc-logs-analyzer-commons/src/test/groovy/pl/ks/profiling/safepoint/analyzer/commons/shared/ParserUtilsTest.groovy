package pl.ks.profiling.safepoint.analyzer.commons.shared

import spock.lang.Specification

class ParserUtilsTest extends Specification {
    def "should get timestamp from line"() {
        given:
        String line = "[2020-12-21T01:04:47.091+0000][1778483.410s][info ][gc,start             ] GC(597760) Pause Young (Concurrent Start) (G1 Humongous Allocation)"

        when:
        BigDecimal timeStamp = ParserUtils.getTimeStamp(line)

        then:
        timeStamp == 1778483.410G
    }

    def "should get timestamp from line printed with other locale"() {
        given:
        String lineWithCommaInsteadOfDot = "[2020-12-21T01:04:47.091+0000][1778483,430s][info ][gc,start             ] GC(597760) Pause Young (Concurrent Start) (G1 Humongous Allocation)"

        when:
        BigDecimal timeStamp = ParserUtils.getTimeStamp(lineWithCommaInsteadOfDot)

        then:
        timeStamp == 1778483.430G
    }

    def "should order files without uptime in the first line as zero"() {
        expect:
        ParserUtils.getFileOrderTimeStamp(line) == expected

        where:
        line                                                                                 || expected
        "[2020-12-21T01:04:47.091+0000][17,430s][info ][gc,start             ] GC(5) Pause" || 17.430G
        "2026-11-07T10:36:59.775+0100: 0.635: Application time: 0.6106055 seconds"            || 0G
        "[Times: user=0,00 sys=0,03, real=0,00 secs]"                                         || 0G
        ""                                                                                    || 0G
        null                                                                                  || 0G
    }
}

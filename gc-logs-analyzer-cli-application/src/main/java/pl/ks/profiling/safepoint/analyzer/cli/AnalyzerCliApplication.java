package pl.ks.profiling.safepoint.analyzer.cli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import pl.ks.profiling.io.InputUtils;
import pl.ks.profiling.io.source.LogsSource;
import pl.ks.profiling.safepoint.analyzer.commons.shared.ParserUtils;
import pl.ks.profiling.safepoint.analyzer.commons.shared.ParsingMode;
import pl.ks.profiling.safepoint.analyzer.commons.shared.ParsingProgress;
import pl.ks.profiling.safepoint.analyzer.commons.shared.StatsService;
import pl.ks.profiling.safepoint.analyzer.commons.shared.report.JvmLogFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Parses JVM logs and writes the HTML report with all the charts and tables of the analyzer to the report directory.
 */
@SpringBootApplication
public class AnalyzerCliApplication {
    private static final Path REPORT_DIR = Path.of("report");
    private static final String PARSER_OPTION = "--parser=";

    @Autowired
    private StatsService statsService;

    private long lastProgressPrint;

    void run(File logFile, ParsingMode parsingMode) throws Exception {
        System.out.println("Parsing " + logFile + " (parser: " + parsingMode + ")");
        LogsSource logsSource = InputUtils.getLogsSource(List.of(logFile), ParserUtils::getFileOrderTimeStamp);
        JvmLogFile jvmLogFile = statsService.createAllStats(parsingMode, logsSource, this::printProgress, parsed -> {
        });
        System.out.println();

        HtmlReport.Summary summary = new HtmlReport(REPORT_DIR, logFile.getName(), parsingMode).write(jvmLogFile);
        System.out.printf("Report written to %s - %d pages, %d charts, %d tables%n",
                REPORT_DIR.resolve(HtmlReport.REPORT_FILE), summary.pages(), summary.charts(), summary.tables());
    }

    private void printProgress(ParsingProgress progress) {
        long now = System.currentTimeMillis();
        if (now - lastProgressPrint >= 1000 || progress.isCompleted()) {
            lastProgressPrint = now;
            System.out.printf("\rProcessed %,d lines (file %d of %d)", progress.getProcessedLines(),
                    progress.getCurrentFileNumber(), progress.getTotalFiles());
        }
    }

    private static void printUsage() {
        System.out.println("""
                Analyzer of the JVM unified logs (GC, safepoints, class loading, threads, JIT compilation) and of
                JDK 8 GC and safepoint logs. Writes an HTML report with all the charts and tables to the report directory.

                Usage:
                  java -jar gc-logs-analyzer-cli.jar [--parser=<parser>] <log file>

                The log file can be compressed (zip, gz, xz, 7z), an archive can contain many (e.g. rotated) logs.

                Parsers:
                  G1_AND_PARALLEL   unified logs (JDK 9+) of G1 or Parallel GC - default
                  SHENANDOAH        unified logs (JDK 9+) of Shenandoah GC
                  ZGC               unified logs (JDK 9+) of ZGC
                  JDK8              JDK 8 GC logs (G1 or Parallel GC) and safepoint statistics""");
    }

    public static void main(String[] args) throws Exception {
        ParsingMode parsingMode = ParsingMode.G1_AND_PARALLEL;
        File logFile = null;
        for (String arg : args) {
            if (arg.startsWith(PARSER_OPTION)) {
                String parser = arg.substring(PARSER_OPTION.length()).toUpperCase(Locale.ROOT).replace('-', '_');
                try {
                    parsingMode = ParsingMode.valueOf(parser);
                } catch (IllegalArgumentException e) {
                    exit("Unknown parser: " + arg.substring(PARSER_OPTION.length()) + ", available: "
                            + Arrays.stream(ParsingMode.values()).map(Enum::name).collect(Collectors.joining(", ")));
                }
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                return;
            } else if (arg.startsWith("-") || logFile != null) {
                printUsage();
                exit("Unexpected argument: " + arg);
            } else {
                logFile = new File(arg);
            }
        }
        if (logFile == null) {
            printUsage();
            return;
        }
        if (!logFile.isFile()) {
            exit("Not a file: " + logFile);
        }
        if (Files.exists(REPORT_DIR)) {
            exit("The report directory already exists, remove it or run in another directory: " + REPORT_DIR.toAbsolutePath());
        }

        // The arguments are parsed above, Spring does not get them
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AnalyzerCliApplication.class)
                .logStartupInfo(false)
                .run()) {
            context.getBean(AnalyzerCliApplication.class).run(logFile, parsingMode);
        }
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }
}

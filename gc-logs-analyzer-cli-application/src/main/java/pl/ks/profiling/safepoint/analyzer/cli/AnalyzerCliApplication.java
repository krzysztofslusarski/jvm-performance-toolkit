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
import pl.ks.profiling.safepoint.analyzer.cli.tui.GcLogsTui;
import pl.ks.profiling.safepoint.analyzer.cli.tui.TerminalImages;
import pl.ks.profiling.xchart.commons.ChartTheme;

import java.io.Console;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses JVM logs and shows all the charts and tables of the analyzer: as an HTML report written to the report
 * directory, or in a full screen console viewer.
 */
@SpringBootApplication
public class AnalyzerCliApplication {
    private static final Path REPORT_DIR = Path.of("report");
    private static final String PARSER_OPTION = "--parser=";
    private static final String IMAGES_OPTION = "--images=";
    private static final String CHART_THEME_OPTION = "--chart-theme=";

    enum Mode {
        REPORT,
        UI
    }

    @Autowired
    private StatsService statsService;

    private long lastProgressPrint;

    private JvmLogFile parse(File logFile, ParsingMode parsingMode) throws Exception {
        System.out.println("Parsing " + logFile + " (parser: " + parsingMode + ")");
        LogsSource logsSource = InputUtils.getLogsSource(List.of(logFile), ParserUtils::getFileOrderTimeStamp);
        JvmLogFile jvmLogFile = statsService.createAllStats(parsingMode, logsSource, this::printProgress, parsed -> {
        });
        System.out.println();
        return jvmLogFile;
    }

    void report(File logFile, ParsingMode parsingMode) throws Exception {
        JvmLogFile jvmLogFile = parse(logFile, parsingMode);
        HtmlReport.Summary summary = new HtmlReport(REPORT_DIR, logFile.getName(), parsingMode).write(jvmLogFile);
        System.out.printf("Report written to %s - %d pages, %d charts, %d tables%n",
                REPORT_DIR.resolve(HtmlReport.REPORT_FILE), summary.pages(), summary.charts(), summary.tables());
    }

    void ui(File logFile, ParsingMode parsingMode, TerminalImages terminalImages, ChartTheme chartTheme) throws Exception {
        JvmLogFile jvmLogFile = parse(logFile, parsingMode);
        new GcLogsTui(jvmLogFile, logFile.getName() + " | parser: " + parsingMode, terminalImages, chartTheme,
                new PresentationFontProviderCli()).run();
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
                JDK 8 GC and safepoint logs.

                Usage:
                  java -jar gc-logs-analyzer-cli.jar [report] [--parser=<parser>] <log file>
                  java -jar gc-logs-analyzer-cli.jar ui [--parser=<parser>] [--images=<protocol>] [--chart-theme=<theme>] <log file>

                Modes:
                  report   writes an HTML report with all the charts and tables to the report directory (default)
                  ui       full screen console viewer: the pages, scrollable tables and the charts as images

                The log file can be compressed (zip, gz, xz, 7z), an archive can contain many (e.g. rotated) logs.

                Parsers:
                  G1_AND_PARALLEL   unified logs (JDK 9+) of G1 or Parallel GC - default
                  SHENANDOAH        unified logs (JDK 9+) of Shenandoah GC
                  ZGC               unified logs (JDK 9+) of ZGC
                  JDK8              JDK 8 GC logs (G1 or Parallel GC) and safepoint statistics

                Options of the ui mode:
                  --images=auto|iterm2|kitty|none   image protocol of the terminal, recognized by default: iterm2
                                                    for iTerm2, WezTerm, VS Code; kitty for kitty, Ghostty. With none
                                                    the charts are saved to files and opened in the image viewer.
                  --chart-theme=auto|dark|light     colors of the charts, by default dark unless the terminal
                                                    reports a light background (COLORFGBG)""");
    }

    public static void main(String[] args) throws Exception {
        Mode mode = Mode.REPORT;
        ParsingMode parsingMode = ParsingMode.G1_AND_PARALLEL;
        String images = "auto";
        String chartTheme = "auto";
        File logFile = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (i == 0 && (arg.equals("report") || arg.equals("ui"))) {
                mode = Mode.valueOf(arg.toUpperCase(Locale.ROOT));
            } else if (arg.startsWith(PARSER_OPTION)) {
                String parser = arg.substring(PARSER_OPTION.length()).toUpperCase(Locale.ROOT).replace('-', '_');
                try {
                    parsingMode = ParsingMode.valueOf(parser);
                } catch (IllegalArgumentException e) {
                    exit("Unknown parser: " + arg.substring(PARSER_OPTION.length()) + ", available: "
                            + Arrays.stream(ParsingMode.values()).map(Enum::name).collect(Collectors.joining(", ")));
                }
            } else if (arg.startsWith(IMAGES_OPTION)) {
                images = option(arg, IMAGES_OPTION, "auto", "iterm2", "kitty", "none");
            } else if (arg.startsWith(CHART_THEME_OPTION)) {
                chartTheme = option(arg, CHART_THEME_OPTION, "auto", "dark", "light");
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
        if (mode == Mode.REPORT && Files.exists(REPORT_DIR)) {
            exit("The report directory already exists, remove it or run in another directory: " + REPORT_DIR.toAbsolutePath());
        }
        if (mode == Mode.UI && !isInteractive()) {
            exit("The ui mode needs an interactive terminal");
        }

        // The arguments are parsed above, Spring does not get them
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AnalyzerCliApplication.class)
                .logStartupInfo(false)
                .run()) {
            AnalyzerCliApplication application = context.getBean(AnalyzerCliApplication.class);
            if (mode == Mode.REPORT) {
                application.report(logFile, parsingMode);
            } else {
                Map<String, String> environment = System.getenv();
                application.ui(logFile, parsingMode, terminalImages(images, environment), chartTheme(chartTheme, environment));
            }
        }
    }

    private static boolean isInteractive() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            // Since JDK 22 the console exists also without a terminal, isTerminal() tells whether there is one
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            // Before JDK 22 the console exists only for a terminal
            return true;
        }
    }

    private static TerminalImages terminalImages(String images, Map<String, String> environment) {
        return switch (images) {
            case "iterm2" -> TerminalImages.of(TerminalImages.Protocol.ITERM2, environment);
            case "kitty" -> TerminalImages.of(TerminalImages.Protocol.KITTY, environment);
            case "none" -> TerminalImages.of(TerminalImages.Protocol.NONE, environment);
            default -> TerminalImages.detect(environment);
        };
    }

    /**
     * COLORFGBG ("foreground;background" colors of the terminal, set e.g. by iTerm2, Konsole, rxvt) tells whether
     * the background is light: white (15) or light gray (7).
     */
    private static ChartTheme chartTheme(String chartTheme, Map<String, String> environment) {
        return switch (chartTheme) {
            case "dark" -> ChartTheme.DARK;
            case "light" -> ChartTheme.LIGHT;
            default -> {
                String colors = environment.getOrDefault("COLORFGBG", "");
                String background = colors.substring(colors.lastIndexOf(';') + 1);
                yield background.equals("15") || background.equals("7") ? ChartTheme.LIGHT : ChartTheme.DARK;
            }
        };
    }

    private static String option(String arg, String prefix, String... allowed) {
        String value = arg.substring(prefix.length()).toLowerCase(Locale.ROOT);
        if (!List.of(allowed).contains(value)) {
            exit("Unknown value of " + prefix.substring(0, prefix.length() - 1) + ": " + value + ", available: "
                    + String.join(", ", allowed));
        }
        return value;
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }
}

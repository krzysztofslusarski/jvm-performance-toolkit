package pl.ks.jfr.web;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import pl.ks.collapsed.CollapsedStack;
import pl.ks.jfr.parser.JfrSpanInfo;
import pl.ks.jfr.parser.JfrParsedAllocationEvent;
import pl.ks.jfr.parser.JfrParsedCommonStackTraceEvent;
import pl.ks.jfr.parser.JfrParsedCpuUsageEvent;
import pl.ks.jfr.parser.JfrParsedEventWithThread;
import pl.ks.jfr.parser.JfrParsedEventWithTime;
import pl.ks.jfr.parser.JfrParsedExecutionSampleEvent;
import pl.ks.jfr.parser.JfrParsedFile;
import pl.ks.jfr.parser.JfrParsedLockEvent;
import pl.ks.jfr.parser.JfrParseFilter;
import pl.ks.jfr.parser.JfrParseFilter.Scope;
import pl.ks.jfr.parser.JfrParser;
import pl.ks.jfr.web.flamegraph.FlameGraphExecutor;

@Slf4j
@RequiredArgsConstructor
public class StatefulJfrViewerService {
    private final Map<UUID, JfrParsedFile> parsedFiles = new ConcurrentHashMap<>();
    private final Map<UUID, String> customNames = new ConcurrentHashMap<>();
    // Descriptions of the filters applied while the files were parsed, only for the files that were filtered
    private final Map<UUID, List<String>> loadFilters = new ConcurrentHashMap<>();

    private final JfrParser jfrParser;
    private final FlameGraphExecutor flameGraphExecutor;

    List<StatefulJfrFile> getFiles() {
        return parsedFiles.entrySet().stream()
                .sorted(Comparator.comparing(o -> o.getValue().getParseStartDate()))
                .map(entry -> StatefulJfrFile.builder()
                        .id(entry.getKey())
                        .customName(customNames.get(entry.getKey()))
                        .parseStartDate(entry.getValue().getParseStartDate())
                        .filenames(entry.getValue().getFilenames())
                        .loadFilters(getLoadFilters(entry.getKey()))
                        .build()
                )
                .toList();
    }

    void remove(UUID uuid) {
        parsedFiles.remove(uuid);
        customNames.remove(uuid);
        loadFilters.remove(uuid);
    }

    String getCustomName(UUID uuid) {
        return customNames.get(uuid);
    }

    List<String> getLoadFilters(UUID uuid) {
        return loadFilters.getOrDefault(uuid, List.of());
    }

    private String flameGraphTitle(UUID uuid, String title) {
        String customName = getCustomName(uuid);
        return customName == null ? title : customName + " - " + title;
    }

    void setCustomName(UUID uuid, String customName) {
        if (customName == null || customName.isBlank()) {
            customNames.remove(uuid);
        } else {
            customNames.put(uuid, customName.strip());
        }
    }

    public JfrParsedFile getFile(UUID uuid) {
        return parsedFiles.get(uuid);
    }

    StatefulJfrHighChartCpuStats getCpuStats(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        List<Predicate<JfrParsedCpuUsageEvent>> filters = createFilters(config, jfrParsedFile, JfrParsedCpuUsageEvent.class);

        Stream<JfrParsedCpuUsageEvent> samples = jfrParsedFile.getCpuUsageSamples().stream();
        for (Predicate<JfrParsedCpuUsageEvent> filter : filters) {
            samples = samples.filter(filter);
        }
        List<JfrParsedCpuUsageEvent> cpuUsageEvents = samples.sorted(Comparator.comparing(JfrParsedCpuUsageEvent::getEventTime)).toList();
        Map<String, List<JfrParsedCpuUsageEvent>> cpuUsageEventsPerFile = new HashMap<>();
        for (JfrParsedCpuUsageEvent event : cpuUsageEvents) {
            cpuUsageEventsPerFile.computeIfAbsent(event.getFilename(), name -> new ArrayList<>()).add(event);
        }

        return StatefulJfrHighChartCpuStats.builder()
                .filenames(jfrParsedFile.getFilenames())
                .cpuUsageEventsPerFile(cpuUsageEventsPerFile)
                .build();
    }

    public List<JfrSpanInfo> getSpanStats(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = getFile(uuid);
        List<Predicate<JfrSpanInfo>> filters = createFilters(config, jfrParsedFile, JfrSpanInfo.class);

        Stream<JfrSpanInfo> spans = jfrParsedFile.getSpans().stream();
        for (Predicate<JfrSpanInfo> filter : filters) {
            spans = spans.filter(filter);
        }

        return spans
                .sorted(Comparator.comparing(JfrSpanInfo::getDuration).reversed())
                .limit(config.getTableLimit())
                .toList();
    }

    public CollapsedStack getExecutionSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredExecutionSamples(config, jfrParsedFile),
                config.getAdditionalLevels(), JfrParsedExecutionSampleEvent::getFullStackTrace, JfrParsedExecutionSampleEvent::getSamples);
    }

    public CollapsedStack getWallClockSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredWallClockSamples(config, jfrParsedFile),
                config.getAdditionalLevels(), JfrParsedExecutionSampleEvent::getFullStackTrace, JfrParsedExecutionSampleEvent::getSamples);
    }

    public byte[] getExecutionSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getExecutionSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Execution samples"), config.isReverseOn());
    }

    public byte[] getWallClockSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getWallClockSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Wall-clock samples"), config.isReverseOn());
    }

    public TimeTable getExecutionSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return TimeTableCreator.create(generateTimeStats(getFilteredExecutionSamples(config, jfrParsedFile)), type, config.getTableLimit(), uuid);
    }

    public TimeTable getWallClockSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return TimeTableCreator.create(generateTimeStats(getFilteredWallClockSamples(config, jfrParsedFile)), type, config.getTableLimit(), uuid);
    }

    private List<JfrParsedExecutionSampleEvent> getFilteredExecutionSamples(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile) {
        List<Predicate<JfrParsedExecutionSampleEvent>> filters = createFilters(config, jfrParsedFile, JfrParsedExecutionSampleEvent.class);

        Stream<JfrParsedExecutionSampleEvent> samples = jfrParsedFile.getExecutionSamples().stream();
        for (Predicate<JfrParsedExecutionSampleEvent> filter : filters) {
            samples = samples.filter(filter);
        }
        return samples.toList();
    }

    private List<JfrParsedExecutionSampleEvent> getFilteredWallClockSamples(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile) {
        List<Predicate<JfrParsedExecutionSampleEvent>> filters = createFilters(config, jfrParsedFile, JfrParsedExecutionSampleEvent.class);

        Stream<JfrParsedExecutionSampleEvent> samples = jfrParsedFile.getWallClockSamples().stream();
        for (Predicate<JfrParsedExecutionSampleEvent> filter : filters) {
            samples = samples.filter(filter);
        }
        return samples.toList();
    }

    public CollapsedStack getAllocationCountSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredAllocationSamples(config, jfrParsedFile), config.getAdditionalLevels(), JfrParsedAllocationEvent::getFullStackTrace);
    }

    public CollapsedStack getAllocationSizeSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredAllocationSamples(config, jfrParsedFile), config.getAdditionalLevels(), JfrParsedAllocationEvent::getFullStackTrace, JfrParsedAllocationEvent::getSize);
    }

    public byte[] getAllocationCountSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getAllocationCountSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Allocation samples (count)"), config.isReverseOn());
    }

    public byte[] getAllocationSizeSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getAllocationSizeSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Allocation samples (size)"), config.isReverseOn());
    }

    public TimeTable getAllocationCountSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        SelfAndTotalTimeStats stats = generateTimeStats(getFilteredAllocationSamples(config, jfrParsedFile), JfrParsedAllocationEvent::getObjectClass);
        return TimeTableCreator.create(stats, type, config.getTableLimit(), uuid);
    }

    public TimeTable getAllocationSizeSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        SelfAndTotalTimeStats stats = generateTimeStats(getFilteredAllocationSamples(config, jfrParsedFile), JfrParsedAllocationEvent::getSize, JfrParsedAllocationEvent::getObjectClass);
        return TimeTableCreator.create(stats, type, config.getTableLimit(), uuid);
    }

    private List<JfrParsedAllocationEvent> getFilteredAllocationSamples(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile) {
        List<Predicate<JfrParsedAllocationEvent>> filters = createFilters(config, jfrParsedFile, JfrParsedAllocationEvent.class);

        Stream<JfrParsedAllocationEvent> samples = jfrParsedFile.getAllocationSamples().stream();
        for (Predicate<JfrParsedAllocationEvent> filter : filters) {
            samples = samples.filter(filter);
        }
        return samples.toList();
    }

    public CollapsedStack getLockCountSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredLockSamples(config, jfrParsedFile), config.getAdditionalLevels(), JfrParsedLockEvent::getFullStackTrace);
    }

    public CollapsedStack getLockTimeSamplesCollapsed(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        return jfrParsedFile.asCollapsed(getFilteredLockSamples(config, jfrParsedFile), config.getAdditionalLevels(), JfrParsedLockEvent::getFullStackTrace, JfrParsedLockEvent::getDuration);
    }

    public byte[] getLockCountSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getLockCountSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Lock samples"), config.isReverseOn());
    }

    public byte[] getLockTimeSamplesFlameGraph(UUID uuid, JfrViewerFilterAndLevelConfig config) {
        return flameGraphExecutor.generateFlameGraphHtml5(getLockTimeSamplesCollapsed(uuid, config), flameGraphTitle(uuid, "Lock samples"), config.isReverseOn());
    }

    public TimeTable getLockCountSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        SelfAndTotalTimeStats stats = generateTimeStats(getFilteredLockSamples(config, jfrParsedFile), JfrParsedLockEvent::getMonitorClass);
        return TimeTableCreator.create(stats, type, config.getTableLimit(), uuid);
    }

    public TimeTable getLockTimeSamplesTimeStats(UUID uuid, JfrViewerFilterAndLevelConfig config, TimeTable.Type type) {
        JfrParsedFile jfrParsedFile = parsedFiles.get(uuid);
        SelfAndTotalTimeStats stats = generateTimeStats(getFilteredLockSamples(config, jfrParsedFile), JfrParsedLockEvent::getDuration, JfrParsedLockEvent::getMonitorClass);
        return TimeTableCreator.create(stats, type, config.getTableLimit(), uuid);
    }

    private List<JfrParsedLockEvent> getFilteredLockSamples(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile) {
        List<Predicate<JfrParsedLockEvent>> filters = createFilters(config, jfrParsedFile, JfrParsedLockEvent.class);

        Stream<JfrParsedLockEvent> samples = jfrParsedFile.getLockSamples().stream();
        for (Predicate<JfrParsedLockEvent> filter : filters) {
            samples = samples.filter(filter);
        }

        return samples.toList();
    }

    public UUID parseNewFiles(List<String> files, boolean oldAsyncProfiler, boolean wallClockExactTime, boolean unifyLambdas, boolean throwOnErroredFile, boolean crossFileSpanMatching) {
        return parseNewFiles(files, oldAsyncProfiler, wallClockExactTime, unifyLambdas, throwOnErroredFile, crossFileSpanMatching, null);
    }

    /**
     * @param loadFilters filters (the same as in the viewer) applied while the files are parsed, so the rejected events
     *                    do not take memory; <code>null</code> to keep every event
     */
    public UUID parseNewFiles(List<String> files, boolean oldAsyncProfiler, boolean wallClockExactTime, boolean unifyLambdas, boolean throwOnErroredFile, boolean crossFileSpanMatching,
                              JfrViewerFilterAndLevelConfig loadFilters) {
        UUID uuid = UUID.randomUUID();
        List<Path> paths = files.stream()
                .map(Paths::get)
                .toList();

        // Every applied filter is described, so an empty description means there is nothing to filter
        List<String> loadFiltersDescription = loadFilters == null ? List.of() : loadFilters.describeFilters();
        JfrParseFilter parseFilter = loadFiltersDescription.isEmpty() ? JfrParseFilter.NONE : createParseFilter(loadFilters);
        JfrParsedFile parsedFile = jfrParser.parse(paths, oldAsyncProfiler, wallClockExactTime, unifyLambdas, throwOnErroredFile, crossFileSpanMatching, parseFilter);
        addNewFile(uuid, parsedFile);
        setLoadFilters(uuid, loadFiltersDescription);
        return uuid;
    }

    private void setLoadFilters(UUID uuid, List<String> description) {
        if (description.isEmpty()) {
            loadFilters.remove(uuid);
        } else {
            loadFilters.put(uuid, List.copyOf(description));
        }
    }

    private JfrParseFilter createParseFilter(JfrViewerFilterAndLevelConfig config) {
        return new JfrParseFilter() {
            @Override
            public <T> Predicate<T> create(Class<T> eventClass, Scope scope, JfrParsedFile parsedFile) {
                return createFilters(config, parsedFile, eventClass, EnumSet.of(scope)).stream()
                        .reduce(event -> true, Predicate::and);
            }
        };
    }

    void addNewFile(UUID uuid, JfrParsedFile parsedFile) {
        parsedFiles.put(uuid, parsedFile);
    }

    private <T extends JfrParsedCommonStackTraceEvent> SelfAndTotalTimeStats generateTimeStats(List<T> events) {
        return generateTimeStats(events, ignored -> 1L, null);
    }

    private <T extends JfrParsedCommonStackTraceEvent> SelfAndTotalTimeStats generateTimeStats(
            List<T> events,
            Function<T, String> additionalConsumingResourceFunction
    ) {
        return generateTimeStats(events, ignored -> 1L, additionalConsumingResourceFunction);
    }

    private <T extends JfrParsedCommonStackTraceEvent> SelfAndTotalTimeStats generateTimeStats(
            List<T> events,
            Function<T, Long> countFunction,
            Function<T, String> additionalConsumingResourceFunction
    ) {
        SelfAndTotalTimeStats selfAndTotalTimeStats = new SelfAndTotalTimeStats();
        events.stream().parallel()
                .forEach(event -> {
                    String[] stackTrace = event.getStackTrace();
                    Set<String> visited = new HashSet<>();
                    Long count = countFunction.apply(event);
                    selfAndTotalTimeStats.newStackTrace(count);
                    for (int i = stackTrace.length - 1; i >= 0; i--) {
                        if (visited.contains(stackTrace[i])) {
                            continue;
                        }
                        visited.add(stackTrace[i]);
                        boolean consumingResource = additionalConsumingResourceFunction == null && i == stackTrace.length - 1;
                        selfAndTotalTimeStats.methodSample(stackTrace[i], consumingResource, count);
                    }
                    if (additionalConsumingResourceFunction != null) {
                        selfAndTotalTimeStats.methodSample(additionalConsumingResourceFunction.apply(event), true, count);
                    }
                });
        return selfAndTotalTimeStats;
    }

    /**
     * Accepts events recorded on the thread of one of the tag matching spans, between the span start and its end.
     */
    private static boolean recordedInsideSpan(Object event, Predicate<String> tagMatches) {
        Set<JfrSpanInfo> spans = ((JfrParsedCommonStackTraceEvent) event).getSpans();
        if (spans == null) {
            return false;
        }
        for (JfrSpanInfo span : spans) {
            if (tagMatches.test(span.getTag())) {
                return true;
            }
        }
        return false;
    }

    private static boolean tagContainsAny(String tag, List<String> parts) {
        for (String part : parts) {
            if (tag.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private <T> List<Predicate<T>> createFilters(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile, Class<T> clazz) {
        return createFilters(config, jfrParsedFile, clazz, EnumSet.allOf(Scope.class));
    }

    /**
     * @param scopes only the filters with these scopes are created - while the files are parsed not everything that the
     *               filters need is known yet
     */
    @SneakyThrows
    private <T> List<Predicate<T>> createFilters(JfrViewerFilterAndLevelConfig config, JfrParsedFile jfrParsedFile, Class<T> clazz, Set<Scope> scopes) {
        List<Predicate<T>> filters = new ArrayList<>(2);
        boolean eventScope = scopes.contains(Scope.EVENT);
        boolean spansScope = scopes.contains(Scope.SPANS);
        boolean recordingScope = scopes.contains(Scope.RECORDING);

        if (eventScope && JfrParsedExecutionSampleEvent.class.isAssignableFrom(clazz)) {
            if (config.isConsumeCpuOn()) {
                filters.add(t -> ((JfrParsedExecutionSampleEvent) t).isConsumesCpu());
            }
        }

        if (eventScope && JfrParsedEventWithThread.class.isAssignableFrom(clazz)) {
            if (config.isThreadFilterOn()) {
                filters.add(t -> ((JfrParsedEventWithThread) t).getThreadName().equalsIgnoreCase(config.getThreadFilter()));
            }
            if (config.isThreadFilterContainsOn()) {
                filters.add(t -> ((JfrParsedEventWithThread) t).getThreadName().toLowerCase().contains(config.getThreadFilterContains().toLowerCase()));
            }
        }

        if (JfrParsedCommonStackTraceEvent.class.isAssignableFrom(clazz)) {
            if (spansScope && config.isSpanFilterEqualsOn()) {
                filters.add(t -> recordedInsideSpan(t, tag -> tag.equals(config.getSpanFilterEquals())));
            }
            if (spansScope && config.isSpanFilterContainsOn()) {
                filters.add(t -> recordedInsideSpan(t, tag -> tagContainsAny(tag, config.getSpanFilterContains())));
            }
            if (eventScope && config.isStackTraceFilterOn()) {
                for (String filter : config.getStackTraceFilters()) {
                    filters.add(t -> ((JfrParsedCommonStackTraceEvent) t).stackTraceContains(filter));
                }
            }
            if (eventScope && config.isStackTraceNotContainsFilterOn()) {
                for (String filter : config.getStackTraceNotContainsFilters()) {
                    filters.add(t -> ((JfrParsedCommonStackTraceEvent) t).stackTraceNotContains(filter));
                }
            }
        }

        // Only one time filter is applied - the warmup ones are relative to the bounds of the recording
        if (JfrParsedEventWithTime.class.isAssignableFrom(clazz)) {
            if (config.isEndDurationOn()) {
                if (eventScope) {
                    Locale locale = new Locale(config.getLocaleLanguage());
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat(config.getEndDateDateTimeFormat(), locale);
                    Date parsedDate = simpleDateFormat.parse(config.getEndDate());
                    Instant endDateTmp = parsedDate.toInstant();
                    Instant startDateTmp = endDateTmp.minus(config.getDuration(), ChronoUnit.MILLIS);
                    Instant endDate = endDateTmp.with(ChronoField.MILLI_OF_SECOND, 999);
                    Instant startDate = startDateTmp;
                    filters.add(t -> {
                        JfrParsedEventWithTime event = (JfrParsedEventWithTime) t;
                        return !(event.getEventTime().isBefore(startDate) || event.getEventTime().isAfter(endDate));
                    });
                }
            } else if (config.isWarmupCooldownOn()) {
                if (recordingScope) {
                    Instant startDate = jfrParsedFile.getMinEventDate().plus(config.getWarmup(), ChronoUnit.SECONDS);
                    Instant endDate = jfrParsedFile.getMaxEventDate().minus(config.getCooldown(), ChronoUnit.SECONDS);
                    filters.add(t -> {
                        JfrParsedEventWithTime event = (JfrParsedEventWithTime) t;
                        return !(event.getEventTime().isBefore(startDate) || event.getEventTime().isAfter(endDate));
                    });
                }
            } else if (config.isWarmupDurationOn()) {
                if (recordingScope) {
                    Instant startDate = jfrParsedFile.getMinEventDate().plus(config.getWdWarmup(), ChronoUnit.SECONDS);
                    Instant endDate = startDate.plus(config.getWdDuration(), ChronoUnit.SECONDS);
                    filters.add(t -> {
                        JfrParsedEventWithTime event = (JfrParsedEventWithTime) t;
                        return !(event.getEventTime().isBefore(startDate) || event.getEventTime().isAfter(endDate));
                    });
                }
            } else if (config.isStartEndTimestampOn()) {
                if (eventScope) {
                    filters.add(t -> {
                        JfrParsedEventWithTime event = (JfrParsedEventWithTime) t;
                        long startTs = config.getStartTs() * 1000;
                        long endTs = (config.getEndTs() * 1000) + 999;
                        return !(event.getEventTime().toEpochMilli() < startTs || event.getEventTime().toEpochMilli() > endTs);
                    });
                }
            }
        }

        return filters;
    }

    UUID trimToMethod(UUID parentUuid, String methodName, JfrParsedFile.Direction direction) {
        UUID childUuid = UUID.randomUUID();
        JfrParsedFile childFile = jfrParser.trim(getFile(parentUuid), methodName, direction);
        addNewFile(childUuid, childFile);
        setCustomName(childUuid, getCustomName(parentUuid));
        setLoadFilters(childUuid, getLoadFilters(parentUuid));
        return childUuid;
    }
}

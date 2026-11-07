package pl.ks.jfr.parser;


import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCMethod;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.unit.ITypedQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.internal.EventArray;
import org.openjdk.jmc.flightrecorder.internal.EventArrays;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import pl.ks.jfr.parser.JfrParseFilter.Scope;

import static pl.ks.jfr.parser.JfrParserHelper.isAsyncAllocNewTLABEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isAsyncAllocOutsideTLABEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isCpuLoadEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isExecutionSampleEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isLockEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isSpanEvent;
import static pl.ks.jfr.parser.JfrParserHelper.isWallClockSampleEvent;
import static pl.ks.jfr.parser.JfrParserHelper.replaceCharacter;
import static pl.ks.jfr.parser.JftFrameType.*;
import static pl.ks.jfr.parser.ParserUtil.getFlightRecording;

@Slf4j
class JfrParserImpl implements JfrParser {
    private static final Map<Class, Field> FIELD_MAP = new ConcurrentHashMap<>();

    @Override
    public JfrParsedFile parse(List<Path> jfrFiles, boolean oldAsyncProfiler, boolean wallClockExactTime, boolean unifyLambdas, boolean throwOnErroredFile, boolean crossFileSpanMatching, JfrParseFilter parseFilter) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        JfrParsedFile jfrParsedFile = new JfrParsedFile(oldAsyncProfiler, wallClockExactTime, unifyLambdas, throwOnErroredFile, crossFileSpanMatching);
        ParseFilters parseFilters = ParseFilters.of(parseFilter, jfrParsedFile);

        jfrFiles.forEach(path -> parseFile(path, jfrParsedFile, parseFilters));
        if (crossFileSpanMatching) {
            fillSpans(jfrParsedFile, AlreadyParsed.NOTHING);
        }
        // Without cross file span matching already applied to every file, unless it failed to parse - checking the
        // remaining events again is cheap
        applySpanFilters(jfrParsedFile, parseFilters, AlreadyParsed.NOTHING);
        jfrParsedFile.calculateAggregatedDates();
        applyRecordingFilters(jfrParsedFile, parseFilter);
        stopWatch.stop();
        log.info("Parsing took: {}ms", stopWatch.getLastTaskTimeMillis());
        return jfrParsedFile;
    }

    @Override
    public JfrParsedFile trim(JfrParsedFile parent, String method, JfrParsedFile.Direction direction) {
        JfrParsedFile child = new JfrParsedFile(parent.isOldAsyncProfiler(), parent.isWallClockExactTime(), parent.isUnifyLambdas(), parent.isThrowOnErroredFile(), parent.isCrossFileSpanMatching());
        parent.filenames.forEach(child::addFilename);
        parent.wallClockSamples.stream()
                .parallel()
                .forEach(event -> {
                    var trimmedEvent = createTrimmedEvent(event, method, direction);
                    if (trimmedEvent != null) {
                        child.addProcessedWallClockSampleEvent(trimmedEvent);
                    }
                });
        parent.executionSamples.stream()
                .parallel()
                .forEach(event -> {
                    var trimmedEvent = createTrimmedEvent(event, method, direction);
                    if (trimmedEvent != null) {
                        child.addExecutionSampleEvent(trimmedEvent);
                    }
                });
        parent.allocationSamples.stream()
                .parallel()
                .forEach(event -> {
                    var trimmedEvent = createTrimmedEvent(event, method, direction);
                    if (trimmedEvent != null) {
                        child.addAllocationSampleEvent(trimmedEvent);
                    }
                });
        parent.lockSamples.stream()
                .parallel()
                .forEach(event -> {
                    var trimmedEvent = createTrimmedEvent(event, method, direction);
                    if (trimmedEvent != null) {
                        child.addLockSampleEvent(trimmedEvent);
                    }
                });
        parent.cpuUsageSamples.stream()
                .parallel()
                .forEach(child::addCpuUsageEvent);
        parent.spans.stream()
                .parallel()
                .forEach(child::addSpan);
        child.calculateAggregatedDates();
        return child;
    }

    private JfrParsedExecutionSampleEvent createTrimmedEvent(JfrParsedExecutionSampleEvent event, String method, JfrParsedFile.Direction direction) {
        var stackTrace = getTrimmedStackTrace(event, method, direction, null);
        if (stackTrace == null) {
            return null;
        }

        var lineNumbers = event.getLineNumbers();

        switch (direction) {
            case UP -> {
                lineNumbers = Arrays.copyOfRange(lineNumbers,
                        event.getStackTrace().length - stackTrace.length, event.getStackTrace().length);
            }
            case DOWN -> {
                lineNumbers = Arrays.copyOfRange(lineNumbers, 0, stackTrace.length);
            }
        }
        ;

        return JfrParsedExecutionSampleEvent.builder()
                .consumesCpu(event.isConsumesCpu())
                .threadName(event.getThreadName())
                .filename(event.getFilename())
                .eventTime(event.getEventTime())
                .stackTrace(stackTrace)
                .lineNumbers(lineNumbers)
                .samples(event.getSamples())
                .spans(event.getSpans())
                .build();
    }

    private JfrParsedAllocationEvent createTrimmedEvent(JfrParsedAllocationEvent event, String method, JfrParsedFile.Direction direction) {
        var stackTrace = getTrimmedStackTrace(event, method, direction, JfrParsedAllocationEvent::getObjectClass);
        if (stackTrace == null) {
            return null;
        }

        return JfrParsedAllocationEvent.builder()
                .threadName(event.getThreadName())
                .filename(event.getFilename())
                .eventTime(event.getEventTime())
                .stackTrace(stackTrace)
                .objectClass(event.getObjectClass())
                .size(event.getSize())
                .outsideTLAB(event.isOutsideTLAB())
                .spans(event.getSpans())
                .build();
    }

    private JfrParsedLockEvent createTrimmedEvent(JfrParsedLockEvent event, String method, JfrParsedFile.Direction direction) {
        var stackTrace = getTrimmedStackTrace(event, method, direction, JfrParsedLockEvent::getMonitorClass);
        if (stackTrace == null) {
            return null;
        }

        return JfrParsedLockEvent.builder()
                .threadName(event.getThreadName())
                .filename(event.getFilename())
                .eventTime(event.getEventTime())
                .stackTrace(stackTrace)
                .monitorClass(event.getMonitorClass())
                .duration(event.getDuration())
                .spans(event.getSpans())
                .build();
    }

    private <T extends JfrParsedCommonStackTraceEvent> String[] getTrimmedStackTrace(
            T event,
            String method,
            JfrParsedFile.Direction direction,
            Function<T, String> additionalEntryFunction
    ) {
        int pos = findMethodPosition(event, method);
        if (pos == -1) {
            if (additionalEntryFunction != null && additionalEntryFunction.apply(event).equals(method)) {
                switch (direction) {
                    case UP -> {
                        return new String[0];
                    }
                    case DOWN -> {
                        return Arrays.copyOf(event.getStackTrace(), event.getStackTrace().length);
                    }
                }
            }

            return null;
        }

        switch (direction) {
            case UP -> {
                return Arrays.copyOfRange(event.getStackTrace(), pos, event.getStackTrace().length);
            }
            case DOWN -> {
                return Arrays.copyOfRange(event.getStackTrace(), 0, pos + 1);
            }
        }
        throw new IllegalArgumentException();
    }

    private int findMethodPosition(JfrParsedCommonStackTraceEvent event, String method) {
        int i = 0;
        for (i = 0; i < event.getStackTrace().length; i++) {
            String frame = event.getStackTrace()[i];
            if (frame.equals(method)) {
                break;
            }
        }
        if (i == event.getStackTrace().length) {
            return -1;
        }
        return i;
    }

    private static void parseFile(Path file, JfrParsedFile jfrParsedFile, ParseFilters parseFilters) {
        String filename = file.getFileName().toString();

        log.info("Input file: " + filename);
        log.info("Parsing JFR");

        try {
            jfrParsedFile.addFilename(filename);
            AlreadyParsed alreadyParsed = AlreadyParsed.of(jfrParsedFile);
            EventArrays flightRecording = getFlightRecording(file);

            for (EventArray eventArray : flightRecording.getArrays()) {
                if (isExecutionSampleEvent(eventArray)) {
                    processExecutionSample(jfrParsedFile, eventArray, filename, parseFilters.samples());
                } else if (isWallClockSampleEvent(eventArray)) {
                    processWallClockSample(jfrParsedFile, eventArray, filename, parseFilters.samples());
                } else if (isLockEvent(eventArray)) {
                    processLockEvent(jfrParsedFile, eventArray, filename, parseFilters.locks());
                } else if (isAsyncAllocNewTLABEvent(eventArray)) {
                    processAllocEvent(jfrParsedFile, eventArray, filename, false, parseFilters.allocations());
                } else if (isAsyncAllocOutsideTLABEvent(eventArray)) {
                    processAllocEvent(jfrParsedFile, eventArray, filename, true, parseFilters.allocations());
                } else if (isCpuLoadEvent(eventArray)) {
                    processCpuEvent(jfrParsedFile, eventArray, filename, parseFilters.cpuUsage());
                } else if (isSpanEvent(eventArray)) {
                    processSpanEvent(jfrParsedFile, eventArray, filename);
                }
            }

            if (jfrParsedFile.isWallClockExactTime() && !jfrParsedFile.getWallClockSamplesToProcess().isEmpty()) {
                extractExactTime(jfrParsedFile, parseFilters.samples());
                jfrParsedFile.getWallClockSamplesToProcess().clear();
            }

            if (!jfrParsedFile.isCrossFileSpanMatching()) {
                fillSpans(jfrParsedFile, alreadyParsed);
                applySpanFilters(jfrParsedFile, parseFilters, alreadyParsed);
            }
        } catch (Exception e) {
            log.error("Fatal error", e);
            if (jfrParsedFile.isThrowOnErroredFile()) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Sizes of the event lists before a file is parsed - only the events that the file added have to be matched with
     * the spans that the same file added. With cross file span matching every event is matched with every span, so
     * {@link AlreadyParsed#NOTHING} is used instead.
     */
    private record AlreadyParsed(int executionSamples, int wallClockSamples, int allocationSamples, int lockSamples,
                                 int spans) {
        private static final AlreadyParsed NOTHING = new AlreadyParsed(0, 0, 0, 0, 0);

        private static AlreadyParsed of(JfrParsedFile jfrParsedFile) {
            return new AlreadyParsed(
                    jfrParsedFile.executionSamples.size(),
                    jfrParsedFile.wallClockSamples.size(),
                    jfrParsedFile.allocationSamples.size(),
                    jfrParsedFile.lockSamples.size(),
                    jfrParsedFile.spans.size()
            );
        }
    }

    /**
     * Parts of the {@link JfrParseFilter} checked while the files are parsed - created once, as they are checked for
     * every event.
     */
    private record ParseFilters(Predicate<JfrParsedExecutionSampleEvent> samples,
                                Predicate<JfrParsedAllocationEvent> allocations,
                                Predicate<JfrParsedLockEvent> locks,
                                Predicate<JfrParsedCpuUsageEvent> cpuUsage,
                                Predicate<JfrParsedExecutionSampleEvent> samplesInSpans,
                                Predicate<JfrParsedAllocationEvent> allocationsInSpans,
                                Predicate<JfrParsedLockEvent> locksInSpans) {
        private static ParseFilters of(JfrParseFilter parseFilter, JfrParsedFile jfrParsedFile) {
            return new ParseFilters(
                    parseFilter.create(JfrParsedExecutionSampleEvent.class, Scope.EVENT, jfrParsedFile),
                    parseFilter.create(JfrParsedAllocationEvent.class, Scope.EVENT, jfrParsedFile),
                    parseFilter.create(JfrParsedLockEvent.class, Scope.EVENT, jfrParsedFile),
                    parseFilter.create(JfrParsedCpuUsageEvent.class, Scope.EVENT, jfrParsedFile),
                    parseFilter.create(JfrParsedExecutionSampleEvent.class, Scope.SPANS, jfrParsedFile),
                    parseFilter.create(JfrParsedAllocationEvent.class, Scope.SPANS, jfrParsedFile),
                    parseFilter.create(JfrParsedLockEvent.class, Scope.SPANS, jfrParsedFile)
            );
        }
    }

    /**
     * Checks a parsed event with the filter. A rejected event is not kept, but its time still counts for the bounds of
     * the recording.
     */
    private static <T extends JfrParsedEventWithTime> boolean accepted(JfrParsedFile jfrParsedFile, T event, Predicate<T> filter) {
        if (filter.test(event)) {
            return true;
        }
        jfrParsedFile.eventDropped(event.getEventTime());
        return false;
    }

    /**
     * Drops the events (parsed after the given sizes) rejected by the spans part of the parse filter - it can be
     * checked only once the spans are assigned to the events.
     */
    private static void applySpanFilters(JfrParsedFile jfrParsedFile, ParseFilters parseFilters, AlreadyParsed alreadyParsed) {
        retainAccepted(jfrParsedFile, newlyParsed(jfrParsedFile.executionSamples, alreadyParsed.executionSamples()), parseFilters.samplesInSpans());
        retainAccepted(jfrParsedFile, newlyParsed(jfrParsedFile.wallClockSamples, alreadyParsed.wallClockSamples()), parseFilters.samplesInSpans());
        retainAccepted(jfrParsedFile, newlyParsed(jfrParsedFile.allocationSamples, alreadyParsed.allocationSamples()), parseFilters.allocationsInSpans());
        retainAccepted(jfrParsedFile, newlyParsed(jfrParsedFile.lockSamples, alreadyParsed.lockSamples()), parseFilters.locksInSpans());
    }

    private static <T extends JfrParsedEventWithTime> void retainAccepted(JfrParsedFile jfrParsedFile, List<T> events, Predicate<T> filter) {
        events.removeIf(event -> !accepted(jfrParsedFile, event, filter));
    }

    /**
     * Drops the events rejected by the recording part of the parse filter - it can be checked only once the bounds of
     * the recording are known, so these events no longer count for them. The spans are needed to assign them to the
     * events, so they are filtered only here, with every part of the filter.
     */
    private static void applyRecordingFilters(JfrParsedFile jfrParsedFile, JfrParseFilter parseFilter) {
        Predicate<JfrParsedExecutionSampleEvent> samples = parseFilter.create(JfrParsedExecutionSampleEvent.class, Scope.RECORDING, jfrParsedFile);
        jfrParsedFile.executionSamples.removeIf(samples.negate());
        jfrParsedFile.wallClockSamples.removeIf(samples.negate());
        jfrParsedFile.allocationSamples.removeIf(parseFilter.create(JfrParsedAllocationEvent.class, Scope.RECORDING, jfrParsedFile).negate());
        jfrParsedFile.lockSamples.removeIf(parseFilter.create(JfrParsedLockEvent.class, Scope.RECORDING, jfrParsedFile).negate());
        jfrParsedFile.cpuUsageSamples.removeIf(parseFilter.create(JfrParsedCpuUsageEvent.class, Scope.RECORDING, jfrParsedFile).negate());

        Predicate<JfrSpanInfo> spans = Arrays.stream(Scope.values())
                .map(scope -> parseFilter.create(JfrSpanInfo.class, scope, jfrParsedFile))
                .reduce(span -> true, Predicate::and);
        jfrParsedFile.spans.removeIf(spans.negate());
    }

    private static void fillSpans(JfrParsedFile jfrParsedFile, AlreadyParsed alreadyParsed) {
        List<JfrSpanInfo> spans = newlyParsed(jfrParsedFile.spans, alreadyParsed.spans());
        if (spans.isEmpty()) {
            return;
        }

        JfrSpanIndex spanIndex = JfrSpanIndex.of(spans);
        fillSpans(newlyParsed(jfrParsedFile.executionSamples, alreadyParsed.executionSamples()), spanIndex, JfrParsedExecutionSampleEvent::withSpans);
        fillSpans(newlyParsed(jfrParsedFile.wallClockSamples, alreadyParsed.wallClockSamples()), spanIndex, JfrParsedExecutionSampleEvent::withSpans);
        fillSpans(newlyParsed(jfrParsedFile.allocationSamples, alreadyParsed.allocationSamples()), spanIndex, JfrParsedAllocationEvent::withSpans);
        fillSpans(newlyParsed(jfrParsedFile.lockSamples, alreadyParsed.lockSamples()), spanIndex, JfrParsedLockEvent::withSpans);
    }

    private static <T extends JfrParsedCommonStackTraceEvent> void fillSpans(
            List<T> events,
            JfrSpanIndex spanIndex,
            BiFunction<T, Set<JfrSpanInfo>, T> withSpans
    ) {
        IntStream.range(0, events.size()).parallel().forEach(i -> {
            T event = events.get(i);
            Set<JfrSpanInfo> spans = spanIndex.spansAt(event.getThreadName(), event.getEventTime());
            if (spans != null) {
                events.set(i, withSpans.apply(event, spans));
            }
        });
    }

    private static <T> List<T> newlyParsed(List<T> events, int alreadyParsed) {
        return events.subList(alreadyParsed, events.size());
    }

    /**
     * The exact time is calculated from the following samples of the thread, so the samples are filtered only once it
     * is known.
     */
    private static void extractExactTime(JfrParsedFile jfrParsedFile, Predicate<JfrParsedExecutionSampleEvent> filter) {
        List<JfrParsedExecutionSampleEvent> toProcess = jfrParsedFile.getWallClockSamplesToProcess();
        Map<String, List<JfrParsedExecutionSampleEvent>> threadToSamples = toProcess.stream()
                .parallel()
                .collect(Collectors.groupingBy(JfrParsedExecutionSampleEvent::getThreadName));
        List<ArrayList<JfrParsedExecutionSampleEvent>> threadSamples = threadToSamples.entrySet().stream()
                .parallel()
                .map(entry -> new ArrayList<>(entry.getValue()))
                .toList();
        threadSamples.stream()
                .parallel()
                .forEach(samples -> extractExactTimeForThread(jfrParsedFile, samples, filter));
    }

    private static void extractExactTimeForThread(JfrParsedFile jfrParsedFile, List<JfrParsedExecutionSampleEvent> samples, Predicate<JfrParsedExecutionSampleEvent> filter) {
        if (samples.isEmpty()) {
            return;
        }
        samples.sort(Comparator.comparing(JfrParsedExecutionSampleEvent::getEventTime));
        JfrParsedExecutionSampleEvent currentSample = samples.get(0);
        long sumDiff = 0;
        long countDiff = 0;
        for (int i = 1; i < samples.size(); i++) {
            JfrParsedExecutionSampleEvent nextSample = samples.get(i);
            if (currentSample.getSamples() == 1) {
                addProcessedWallClockSample(jfrParsedFile, currentSample, filter);
                currentSample = nextSample;
                continue;
            }

            long diff = ChronoUnit.MILLIS.between(currentSample.getEventTime(), nextSample.getEventTime());
            diff /= currentSample.getSamples();
            sumDiff += diff;
            countDiff++;
            JfrParsedExecutionSampleEvent currentSampleWithOneSample = currentSample.withSamples(1);
            for (int j = 0; j < currentSample.getSamples(); j++) {
                addProcessedWallClockSample(jfrParsedFile,
                        currentSampleWithOneSample.withEventTime(currentSample.getEventTime().plus(diff * j, ChronoUnit.MILLIS)),
                        filter
                );
            }
            currentSample = nextSample;
        }
        if (countDiff == 0) {
            addProcessedWallClockSample(jfrParsedFile, currentSample, filter);
        } else {
            long diff = sumDiff / countDiff;
            JfrParsedExecutionSampleEvent currentSampleWithOneSample = currentSample.withSamples(1);
            for (int j = 0; j < currentSample.getSamples(); j++) {
                addProcessedWallClockSample(jfrParsedFile,
                        currentSampleWithOneSample.withEventTime(currentSample.getEventTime().plus(diff * j, ChronoUnit.MILLIS)),
                        filter
                );
            }
        }
    }

    private static void addProcessedWallClockSample(JfrParsedFile jfrParsedFile, JfrParsedExecutionSampleEvent event, Predicate<JfrParsedExecutionSampleEvent> filter) {
        if (accepted(jfrParsedFile, event, filter)) {
            jfrParsedFile.addProcessedWallClockSampleEvent(event);
        }
    }

    private static void processCpuEvent(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename, Predicate<JfrParsedCpuUsageEvent> filter) {
        JfrAccessors accessors = JfrAccessors.builder()
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .jvmSystemAccessor(JfrParserHelper.findCpuJvmSystemAccessor(eventArray))
                .jvmUserAccessor(JfrParserHelper.findCpuJvmUserAccessor(eventArray))
                .machineTotalAccessor(JfrParserHelper.findMachineTotalAccessor(eventArray))
                .build();
        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            ITypedQuantity jvmUser = accessors.getJvmUserAccessor().getMember(event);
            ITypedQuantity jvmSystem = accessors.getJvmSystemAccessor().getMember(event);
            ITypedQuantity machineTotal = accessors.getMachineTotalAccessor().getMember(event);

            JfrParsedCpuUsageEvent cpuUsageEvent = JfrParsedCpuUsageEvent.builder()
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .filename(filename)
                    .machineTotal(BigDecimal.valueOf(machineTotal.doubleValue()).setScale(2, RoundingMode.HALF_EVEN))
                    .jvmUser(BigDecimal.valueOf(jvmUser.doubleValue()).setScale(2, RoundingMode.HALF_EVEN))
                    .jvmSystem(BigDecimal.valueOf(jvmSystem.doubleValue()).setScale(2, RoundingMode.HALF_EVEN))
                    .build();
            // CPU usage events do not count for the bounds of the recording
            if (filter.test(cpuUsageEvent)) {
                jfrParsedFile.addCpuUsageEvent(cpuUsageEvent);
            }
        });

    }

    private static void processSpanEvent(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename) {
        JfrAccessors accessors = JfrAccessors.builder()
                .threadAccessor(JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType()))
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .durationAccessor(JfrParserHelper.findDurationAccessor(eventArray))
                .tagAccessor(JfrParserHelper.findTagAccessor(eventArray))
                .build();

        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            var thread = accessors.getThreadAccessor() == null ? null : accessors.getThreadAccessor().getMember(event);
            String tag = accessors.getTagAccessor() == null ? null : accessors.getTagAccessor().getMember(event);
            jfrParsedFile.addSpan(JfrSpanInfo.builder()
                    .tag(tag == null ? "" : jfrParsedFile.getCanonicalString(jfrParsedFile, tag))
                    .threadName(thread == null ? "" : jfrParsedFile.getCanonicalString(jfrParsedFile, thread.getThreadName()))
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .duration(accessors.getDurationAccessor() != null ? accessors.getDurationAccessor().getMember(event).in(UnitLookup.NANOSECOND).longValue() : 0L)
                    .filename(filename)
                    .build()
            );
        });
    }

    private static void processLockEvent(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename, Predicate<JfrParsedLockEvent> filter) {
        JfrAccessors accessors = JfrAccessors.builder()
                .stackTraceAccessor(JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType()))
                .threadAccessor(JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType()))
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .monitorClassAccessor(JfrParserHelper.findMonitorClassAccessor(eventArray))
                .durationAccessor(JfrParserHelper.findDurationAccessor(eventArray))
                .build();

        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            List<? extends IMCFrame> frames = accessors.getStackTraceAccessor().getMember(event).getFrames();
            JfrParsedLockEvent lockEvent = JfrParsedLockEvent.builder()
                    .duration(accessors.getDurationAccessor() != null ? accessors.getDurationAccessor().getMember(event).in(UnitLookup.NANOSECOND).longValue() : 0L)
                    .filename(filename)
                    .threadName(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getThreadAccessor().getMember(event).getThreadName()))
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .stackTrace(getStackTrace(jfrParsedFile, frames))
                    .lineNumbers(getLineNumbers(jfrParsedFile, frames))
                    .monitorClass(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getMonitorClassAccessor().getMember(event).getFullName()))
                    .build();
            if (accepted(jfrParsedFile, lockEvent, filter)) {
                jfrParsedFile.addLockSampleEvent(lockEvent);
            }
        });
    }

    private static void processAllocEvent(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename, boolean outsideTLAB, Predicate<JfrParsedAllocationEvent> filter) {
        JfrAccessors accessors = JfrAccessors.builder()
                .stackTraceAccessor(JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType()))
                .threadAccessor(JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType()))
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .allocationSizeAccessor(JfrParserHelper.findAllocSizeAccessor(eventArray))
                .objectClassAccessor(JfrParserHelper.findObjectClassAccessor(eventArray))
                .build();

        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            IMCStackTrace stackTrace = accessors.getStackTraceAccessor().getMember(event);
            if (stackTrace == null) {
                return;
            }
            List<? extends IMCFrame> frames = stackTrace.getFrames();
            JfrParsedAllocationEvent allocationEvent = JfrParsedAllocationEvent.builder()
                    .filename(filename)
                    .threadName(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getThreadAccessor().getMember(event).getThreadName()))
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .stackTrace(getStackTrace(jfrParsedFile, frames))
                    .lineNumbers(getLineNumbers(jfrParsedFile, frames))
                    .objectClass(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getObjectClassAccessor().getMember(event).getFullName()))
                    .size(accessors.getAllocationSizeAccessor().getMember(event).longValue())
                    .outsideTLAB(outsideTLAB)
                    .build();
            if (accepted(jfrParsedFile, allocationEvent, filter)) {
                jfrParsedFile.addAllocationSampleEvent(allocationEvent);
            }
        });

    }

    private static void processWallClockSample(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename, Predicate<JfrParsedExecutionSampleEvent> filter) {
        JfrAccessors accessors = JfrAccessors.builder()
                .stackTraceAccessor(JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType()))
                .threadAccessor(JfrParserHelper.findThreadAccessor(eventArray))
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .stateAccessor(JfrParserHelper.findStateAccessor(eventArray))
                .samplesAccessor(JfrParserHelper.findSamplesAccessor(eventArray))
                .build();

        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            IMCStackTrace stackTrace = accessors.getStackTraceAccessor().getMember(event);
            if (stackTrace == null) {
                return;
            }
            List<? extends IMCFrame> frames = stackTrace.getFrames();
            final JfrParsedExecutionSampleEvent sampleEvent = JfrParsedExecutionSampleEvent.builder()
                    .consumesCpu(accessors.getStateAccessor() != null && JfrParserHelper.isConsumingCpu(accessors.getStateAccessor().getMember(event)))
                    .filename(filename)
                    .threadName(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getThreadAccessor().getMember(event).getThreadName()))
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .stackTrace(getStackTrace(jfrParsedFile, frames))
                    .lineNumbers(getLineNumbers(jfrParsedFile, frames))
                    .samples(accessors.getSamplesAccessor() != null ? accessors.getSamplesAccessor().getMember(event).longValue() : 0L)
                    .build();
            // With the exact time extraction the samples are filtered once their exact time is known
            if (jfrParsedFile.isWallClockExactTime() || accepted(jfrParsedFile, sampleEvent, filter)) {
                jfrParsedFile.addWallClockSampleEvent(sampleEvent);
            }
        });
    }

    private static void processExecutionSample(JfrParsedFile jfrParsedFile, EventArray eventArray, String filename, Predicate<JfrParsedExecutionSampleEvent> filter) {
        JfrAccessors accessors = JfrAccessors.builder()
                .stackTraceAccessor(JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType()))
                .threadAccessor(JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType()))
                .startTimeAccessor(JfrAttributes.START_TIME.getAccessor(eventArray.getType()))
                .stateAccessor(JfrParserHelper.findStateAccessor(eventArray))
                .build();

        Arrays.stream(eventArray.getEvents()).parallel().forEach(event -> {
            List<? extends IMCFrame> frames = accessors.getStackTraceAccessor().getMember(event).getFrames();
            JfrParsedExecutionSampleEvent sampleEvent = JfrParsedExecutionSampleEvent.builder()
                    .consumesCpu(accessors.getStateAccessor() != null && JfrParserHelper.isConsumingCpu(accessors.getStateAccessor().getMember(event)))
                    .filename(filename)
                    .threadName(jfrParsedFile.getCanonicalString(jfrParsedFile, accessors.getThreadAccessor().getMember(event).getThreadName()))
                    .eventTime(new Date(accessors.getStartTimeAccessor().getMember(event).longValue() / 1000000).toInstant())
                    .stackTrace(getStackTrace(jfrParsedFile, frames))
                    .lineNumbers(getLineNumbers(jfrParsedFile, frames))
                    .samples(1)
                    .build();
            if (accepted(jfrParsedFile, sampleEvent, filter)) {
                jfrParsedFile.addExecutionSampleEvent(sampleEvent);
            }
        });
    }

    private static int[] getLineNumbers(JfrParsedFile jfrParsedFile, List<? extends IMCFrame> frames) {
        int[] lineNumbers = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            IMCFrame frame = frames.get(i);
            Integer lineNumber = frame.getFrameLineNumber();
            lineNumbers[frames.size() - i - 1] = lineNumber == null ? -1 : lineNumber;
        }
        return lineNumbers;
    }

    private static String[] getStackTrace(JfrParsedFile jfrParsedFile, List<? extends IMCFrame> frames) {
        String[] stackTrace = new String[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            StringBuilder stackTraceBuilder = new StringBuilder();
            IMCFrame frame = frames.get(i);

            JftFrameType type = getType(frame);

            IMCMethod method = frame.getMethod();
            String packageName = method.getType().getPackage().getName() == null ? "" : replaceCharacter(method.getType().getPackage().getName(), '/', '.');
            if (packageName.length() > 0) {
                stackTraceBuilder.append(packageName);
                stackTraceBuilder.append("/");
            }
            if (!method.getFormalDescriptor().equals("()L;")) {
                String className = replaceCharacter(method.getType().getTypeName(), '/', '.');
                if (className.length() > 0) {
                    stackTraceBuilder.append(className);
                    stackTraceBuilder.append(".");
                }
            }
            stackTraceBuilder.append(method.getMethodName());
            if (method.getFormalDescriptor().equals("(Lk;)L;")) {
                stackTraceBuilder.append("_[k]");
            } else if (!jfrParsedFile.isOldAsyncProfiler()) {
                switch (type) {
                    case TYPE_INTERPRETED -> {
                        stackTraceBuilder.append("_[0]");
                    }
                    case TYPE_JIT_COMPILED -> {
                        stackTraceBuilder.append("_[j]");
                    }
                    case TYPE_INLINED -> {
                        stackTraceBuilder.append("_[i]");
                    }
                    case TYPE_KERNEL -> {
                        stackTraceBuilder.append("_[k]");
                    }
                    case TYPE_C1_COMPILED -> {
                        stackTraceBuilder.append("_[1]");
                    }
                }
            }
            stackTrace[frames.size() - i - 1] = jfrParsedFile.getCanonicalString(jfrParsedFile, stackTraceBuilder.toString());
        }
        return stackTrace;
    }

    private static JftFrameType getType(IMCFrame frame) {
        Field field = FIELD_MAP.get(frame.getClass());
        if (field == null) {
            synchronized (FIELD_MAP) {
                field = FIELD_MAP.get(frame.getClass());
                if (field == null) {
                    field = ReflectionUtils.findField(frame.getClass(), "type");
                    if (field == null) {
                        return TYPE_JIT_COMPILED;
                    }
                    field.setAccessible(true);
                    FIELD_MAP.put(frame.getClass(), field);
                }
            }
        }
        Object type = ReflectionUtils.getField(field, frame);
        if (type == null) {
            return TYPE_JIT_COMPILED;
        }
        if (type instanceof String) {
            if ("Native".equals(type)) {
                return TYPE_NATIVE;
            } else if ("Kernel".equals(type)) {
                return TYPE_KERNEL;
            } else if ("Inlined".equals(type)) {
                return TYPE_INLINED;
            } else if ("JIT compiled".equals(type)) {
                return TYPE_JIT_COMPILED;
            } else if ("C++".equals(type)) {
                return TYPE_CPP;
            } else if ("C1 compiled".equals(type)) {
                return TYPE_C1_COMPILED;
            } else if ("Interpreted".equals(type)) {
                return TYPE_INTERPRETED;
            }
        }
        return TYPE_JIT_COMPILED;
    }
}

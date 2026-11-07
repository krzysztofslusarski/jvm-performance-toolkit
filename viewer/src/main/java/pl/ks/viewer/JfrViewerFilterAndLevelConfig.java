/*
 * Copyright 2022 Krzysztof Slusarski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.ks.viewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;
import pl.ks.jfr.parser.tuning.AdditionalLevel;

@Value
@Builder
public class JfrViewerFilterAndLevelConfig {
    boolean threadFilterOn;
    String threadFilter;
    boolean threadFilterContainsOn;
    String threadFilterContains;
    boolean endDurationOn;
    String endDate;
    String endDateDateTimeFormat;
    long duration;
    boolean warmupCooldownOn;
    int cooldown;
    int warmup;
    boolean warmupDurationOn;
    int wdWarmup;
    long wdDuration;
    boolean spanFilterEqualsOn;
    String spanFilterEquals;
    boolean spanFilterContainsOn;
    List<String> spanFilterContains;
    boolean startEndTimestampOn;
    long startTs;
    long endTs;
    boolean consumeCpuOn;
    boolean reverseOn;
    Set<AdditionalLevel> additionalLevels;
    int tableLimit;
    String localeLanguage;
    boolean stackTraceFilterOn;
    List<String> stackTraceFilters;
    boolean stackTraceNotContainsFilterOn;
    List<String> stackTraceNotContainsFilters;

    /**
     * Human readable list of the filters that are applied, in the order they are listed in the viewer - empty when
     * nothing is filtered.
     */
    public List<String> describeFilters() {
        List<String> description = new ArrayList<>();
        if (threadFilterOn) {
            description.add("Thread equals " + quoted(threadFilter));
        }
        if (threadFilterContainsOn) {
            description.add("Thread contains " + quoted(threadFilterContains));
        }
        if (stackTraceFilterOn) {
            stackTraceFilters.forEach(filter -> description.add("Stack trace contains " + quoted(filter)));
        }
        if (stackTraceNotContainsFilterOn) {
            stackTraceNotContainsFilters.forEach(filter -> description.add("Stack trace not contains " + quoted(filter)));
        }
        if (spanFilterEqualsOn) {
            description.add("Span equals " + quoted(spanFilterEquals));
        }
        if (spanFilterContainsOn) {
            description.add("Span contains " + spanFilterContains.stream().map(JfrViewerFilterAndLevelConfig::quoted).collect(Collectors.joining(" or ")));
        }
        // Only one time filter is applied - the first one in this order
        if (endDurationOn) {
            description.add("Access log: " + duration + " ms ending at " + quoted(endDate));
        } else if (warmupCooldownOn) {
            description.add("Warmup " + warmup + " s, cooldown " + cooldown + " s");
        } else if (warmupDurationOn) {
            description.add("Warmup " + wdWarmup + " s, duration " + wdDuration + " s");
        } else if (startEndTimestampOn) {
            description.add("Timestamps from " + startTs + " to " + endTs + " s");
        }
        if (consumeCpuOn) {
            description.add("Consume CPU only");
        }
        return description;
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }
}

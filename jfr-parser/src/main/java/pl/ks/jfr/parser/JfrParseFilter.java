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
package pl.ks.jfr.parser;

import java.util.function.Predicate;

/**
 * Filter applied while JFR files are parsed - the events it rejects are not kept in the {@link JfrParsedFile}, so
 * they do not take memory once it is known that only some of the events will be analysed.
 * <p>
 * Events rejected by the filter still count for the bounds of the recording ({@link JfrParsedFile#getMinEventDate()}
 * and {@link JfrParsedFile#getMaxEventDate()}), so filters relative to the start or the end of the recording give
 * the same result as when nothing was dropped.
 */
public interface JfrParseFilter {
    JfrParseFilter NONE = new JfrParseFilter() {
        @Override
        public <T> Predicate<T> create(Class<T> eventClass, Scope scope, JfrParsedFile parsedFile) {
            return event -> true;
        }
    };

    /**
     * What a filter has to know about an event, which decides how early during the parsing it can be checked.
     */
    enum Scope {
        /**
         * Nothing but the event itself - checked as soon as the event is parsed.
         */
        EVENT,
        /**
         * The spans the event was recorded in - checked once the spans of the file are assigned to its events (or of
         * all the files with cross file span matching).
         */
        SPANS,
        /**
         * The bounds of the whole recording - checked once every file is parsed.
         */
        RECORDING
    }

    /**
     * Creates the part of the filter with the given scope for the events of the given class. The spans
     * ({@link JfrSpanInfo}) are needed to assign them to the events, so all the parts are applied to them once every
     * file is parsed.
     *
     * @param parsedFile the file being parsed; its bounds are known only for the {@link Scope#RECORDING} scope
     */
    <T> Predicate<T> create(Class<T> eventClass, Scope scope, JfrParsedFile parsedFile);
}

/*
 * Copyright 2020 Krzysztof Slusarski
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
package pl.ks.profiling.safepoint.analyzer.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import pl.ks.profiling.safepoint.analyzer.commons.shared.KernelConfiguration;

/**
 * Everything the GC logs analyzer needs in a web application - imported by the application that hosts it.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan
@EnableConfigurationProperties(ParsingProperties.class)
@Import({
        KernelConfiguration.class
})
public class GcLogsAnalyzerWebConfiguration {
    /**
     * Profile of the gclogs.com site: the GC logs analyzer is the only tool there, it owns the root page and the
     * uploaded logs are stored on the author's server.
     */
    public static final String GCLOGS_COM_SITE_PROFILE = "gclogs-com-site";
}

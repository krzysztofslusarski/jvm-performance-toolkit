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
package pl.ks.toolkit;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import pl.ks.profiling.safepoint.analyzer.web.GcLogsAnalyzerWebConfiguration;

import static pl.ks.profiling.safepoint.analyzer.web.GcLogsAnalyzerWebConfiguration.GCLOGS_COM_SITE_PROFILE;

/**
 * Registers the tools hosted by the application. Only the GC logs analyzer is available on the gclogs.com site
 * (the gclogs-com-site profile).
 */
@Configuration(proxyBeanMethods = false)
@Import({
        GcLogsAnalyzerWebConfiguration.class,
})
class ToolkitApplicationConfiguration {
    @Profile("!" + GCLOGS_COM_SITE_PROFILE)
    @Configuration(proxyBeanMethods = false)
    @ComponentScan({
            "pl.ks.jfr",
    })
    static class JfrViewerConfiguration {
    }
}

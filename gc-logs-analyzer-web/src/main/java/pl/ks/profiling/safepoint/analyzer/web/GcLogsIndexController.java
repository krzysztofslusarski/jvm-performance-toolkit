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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import static pl.ks.profiling.safepoint.analyzer.web.GcLogsAnalyzerWebConfiguration.GCLOGS_COM_SITE_PROFILE;

/**
 * Root page of the gclogs.com site, where the GC logs analyzer is the only tool - in every other profile the root
 * page belongs to the application hosting the tools.
 */
@Controller
@Profile(GCLOGS_COM_SITE_PROFILE)
class GcLogsIndexController {
    @Value("${indexPageAvailable}")
    private boolean indexPageAvailable;

    @Value("${dockerImage}")
    private String dockerImage;

    @GetMapping("/")
    String index(Model model) {
        if (indexPageAvailable) {
            model.addAttribute("dockerImage", dockerImage);
            return "gc-logs/index";
        }
        return "forward:/upload";
    }
}

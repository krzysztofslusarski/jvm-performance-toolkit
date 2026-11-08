# 2.0
## JVM logs analyzer:
- Initial integration - merged from the separate JvmGcLogsAnalyzer project: the web analyzer is a part of the toolkit
  web application (`/upload`, a tile on the landing page), the standalone and the command line applications are
  modules of this repository
- Dark mode for JVM logs analyzer - web pages and the standalone application (the choice is remembered, on macOS
  the window title bars follow the theme)
- Better support for JDK 8, 11, 17, 25, 27 logs: Parallel and G1 (JDK 8+), Shenandoah (JDK 11+), ZGC (JDK 17+), also
  logs written with a decimal comma (e.g. `pl_PL` locale) - tested on the sample logs in `gc-logs-samples`
- CLI application:
  - HTML report with every chart and table of the standalone application, in the look of the toolkit (light and
    dark theme)
  - `--parser` option - `G1_AND_PARALLEL` (default), `SHENANDOAH`, `ZGC`, `JDK8`
  - `ui` mode - full screen viewer in the terminal: pages, scrollable tables, charts as images (terminals with
    the iTerm2 or kitty image protocol: iTerm2, WezTerm, VS Code, kitty, Ghostty)
- `gclogs-com-site` profile (formerly `prod`) for the public site: only the JVM logs analyzer, without the JFR viewer
  and the landing page; the terms of service are shown only with this profile
## JFR viewer:
- Support for filtering while loading JFRs - the rejected events are not kept in memory, reducing heap usage
## Toolkit:
- Spring Boot 3.4.5 (was 2.6.3)
- Dockerfile of the toolkit web application, configured with environment variables
- Modules renamed, the jars moved: `toolkit-web-application/target/toolkit-web-application.jar`
  (was `viewer-application/target/viewer-application.jar`), `jfr-viewer-cli-application/target/jfr-cli.jar`
  (was `cli-application/target/jfr-cli.jar`)
- Guava 33.5.0 (was 30.1) - fixes CVE-2020-8908 and CVE-2023-2976

# 1.0
- Collapsed stack viewer (web)
- JFR viewer (web)
- JFR viewer (CLI)
- Advanced filtering

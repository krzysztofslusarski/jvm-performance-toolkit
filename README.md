# JVM profiling toolkit
Viewer for collapsed stack and JFR outputs of profiles. Dedicated to Async-profiler, but works also
with JDK Flight Recorder. It also contains the
[GC logs analyzer](#gc-logs-analyzer) - analyzer of the JVM unified logs (GC, safepoints, class loading,
threads, JIT compilation).

JAVA_HOME should point to JDK 17.

## How to install - from sources
```shell script
git clone --depth 1 https://github.com/krzysztofslusarski/java-profiling-toolkit.git
cd java-profiling-toolkit/
mvn clean package
```

## How to run
`java -jar toolkit-web-application/target/toolkit-web-application.jar`

Java should point to JDK 17. After you run it, the toolkit is available on `http://localhost:8079/` - the main
page leads to the JFR viewer, the collapsed stack viewer and the GC logs analyzer.

The `gclogs-com-site` profile (`--spring.profiles.active=gclogs-com-site`) is the [gclogs.com](https://gclogs.com) site: only
the GC logs analyzer is available there and it owns the main page.

## How to configure
Viewer is Spring Boot application, you can create `application.yml` with:

```yaml
server:
  port: 8079 
```

## Console viewer
If you cannot (or do not want to) open a browser, there is a full screen console viewer for JFR files:

```shell script
java -jar jfr-viewer-cli-application/target/jfr-cli.jar [options] <file.jfr> [<file.jfr> ...]
```

It gives you the same four pages as the web viewer - flame graph, total time table, self time table and
span stats - computed by exactly the same code. Only JFR files are supported, collapsed stack files are not.

Parsing options, the counterparts of the checkboxes on the upload page:

* `--old-async-profiler` - files were recorded with Async-profiler older than 2.9
* `--wall-clock-exact-time` - use the exact time of wall-clock samples
* `--unify-lambdas` - merge lambda classes differing only by their generated suffix
* `--cross-file-span-matching` - match spans with events recorded in the other files
* `--throw-on-errored-file` - fail instead of skipping a file that cannot be parsed
* `--table-limit=<rows>` - maximum number of rows in the tables, `10000` by default

A console is too narrow for a sidebar, so every filter and option lives behind a shortcut:

| Key           | Action                                                                    |
|---------------|---------------------------------------------------------------------------|
| `1` .. `4`    | flame graph / total time / self time / span stats                          |
| `e`           | event: execution, wall-clock, allocation (count, size), lock (count, time)  |
| `f`           | filters: thread, stack trace, span, time, consumes CPU                      |
| `l`           | additional levels of the flame graph                                        |
| `o`           | options: table limit, reverse flame graph                                   |
| `r` or `F5`   | reload the page with the current settings                                   |
| `/`           | highlight frames (flame graph) or filter rows (tables)                      |
| `x`           | export the current flame graph as an interactive HTML file                  |
| `?` or `F1`   | list of all shortcuts                                                       |
| `q`           | quit                                                                        |

The flame graph is drawn like the HTML one - it grows upwards from `all` at the bottom, and turns into an
icicle hanging from the top when the stacks are reversed. Arrows move between frames in the direction they
are drawn, `Enter` zooms into the selected frame, `Backspace` zooms out and `Home` resets the zoom. Frames
are coloured exactly like in the HTML flame graphs.

## Example usage of Async-profiler

Collapsed stack file for the collapsed stack viewer:
```shell script
asprof -t -d 30 -e cpu -o collapsed -f output.txt <pid>
```

* `-t` - gives you output divided by thread
* `-d 30` - 30s duration
* `-e cpu` - profiled event, viewer should work with every event
* `-o collapsed` - as name suggest, this is collapsed stack viewer, so this is mandatory output
* `-f output.txt ` - output file
* `<pid>` - pid of your JVM

JFR file for the JFR viewer, with CPU, wall-clock, allocation and lock samples at once:
```shell script
asprof --all -d 30 -f profile.jfr <pid>
```

## Features of viewer

Every page has a light and a dark theme, the choice is remembered by the browser.

### Flame graphs

Both viewers present the stacks as interactive HTML flame graphs:
* click a frame to zoom into it, `0` resets the zoom, `Ctrl`/`Alt` + click removes the stack from the graph
* `I` inverts the graph, `T` toggles the tree view, `D` toggles the dark mode
* `Ctrl+F` searches frames by a regular expression, `N` / `Shift+N` move between the matches
* frames are coloured by their type (JIT compiled, inlined, interpreted, native, kernel, ...), the legend is
  under the info button

### Analysis of collapsed stack file

The upload page (`/upload-collapsed`) accepts a collapsed stack file, also compressed (`*.zip`, `*.gz`, `*.xz`,
`*.7z`), with:
* a title, visible on every page
* total time and self time thresholds - methods with a smaller fraction of the samples are not listed in the tables
* a filter - only the stacks containing the given text are loaded

The report has three pages:
* **Flame graphs**:
  * flame graph of the whole file
  * flame graph with the first 1 - 4 levels skipped (e.g. the thread level of `asprof -t` output)
  * hotspot flame graph - the stacks are reversed, so the graph starts (from the top) with the methods that
    were on the top of the stacks, also limited to the depth of 10 / 20 / 30 frames
* **Method total time** - number of stacks that have the method anywhere on the stack
* **Method self time** - number of stacks that have the method at the top of the stack

Both tables can be filtered by the method name. For every method there are:
* callee flame graph - what the method does, aggregated over every call of the method
* callers flame graph - which methods called it, aggregated over every call of the method
* a new report (flame graphs and tables) of the stacks cut at the method, in both directions

### Analysis of JFR files

The JFR viewer reads these events:
* execution samples (`jdk.ExecutionSample`) - Async-profiler `cpu`/`itimer` modes, JDK Flight Recorder
* wall-clock samples (`profiler.WallClockSample`) - Async-profiler `wall` mode
* allocations (`jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB`) - Async-profiler `alloc` mode
* locks (`jdk.JavaMonitorEnter`) - Async-profiler `lock` mode
* CPU load (`jdk.CPULoad`)
* spans (`profiler.Span`) - created with the Async-profiler span API

#### Upload

On the upload page (`/upload-stateful-jfr`) you can upload many JFR files at once (also gzipped, `*.jfr.gz`),
they are analysed together. The files stay in memory - they are listed on the upload page, where they can be
opened again or removed. Options:
* a custom name of the files, visible in the viewer
* JFR not from Async-profiler or from Async-profiler < 3.0
* exact time of the wall-clock samples - Async-profiler aggregates wall-clock samples, the exact time of each
  one is calculated from the following samples of the thread
* unified lambda names - merges lambda classes that differ only by the generated suffix (useful when the files
  come from many JVMs)
* cross-file span matching - matches spans with the samples from the other files
* fail if any file cannot be parsed - by default such a file is skipped
* filter while loading - exactly the same filters as in the viewer (below), applied while the files are parsed.
  The rejected events are not kept in memory, so huge recordings fit in memory when you know that only a part of
  them will be analysed. The rejected events cannot be brought back later; the filters relative to the start
  of the recording (warmup) are counted from the bounds of the whole recording.

#### Viewer

The viewer shows the data of the selected event - execution samples, wall-clock samples, allocations (count or
size) or locks (count or time) - as:
* **flame graph**
* **total time table** - methods with the number of samples that have the method anywhere on the stack
* **self time table** - methods with the number of samples that have the method at the top of the stack
* **span stats** - spans sorted by their duration, with the thread and the start time
* **CPU stats** - charts of the CPU load: machine total, JVM total, not JVM total, JVM user and JVM system

In the tables every method can be used to trim the stacks - a new file appears in memory with only the stacks
containing the method, cut at it: from the method up (what the method does) or from the thread start down to
the method (how it was called).

Filters (they can be combined, only one time filter at a time):
* thread name - equals or contains
* stack trace - contains or does not contain the given texts
* span - stacks recorded inside a span with the given tag (equals or contains)
* time - access log style (end date and duration), start/end timestamp, warmup/cooldown or warmup/duration
* samples consuming CPU only

Additional levels of the flame graph: thread, timestamp (10s, 1s or 100ms), file name, spans and line numbers.

Options: limit of the table rows and a reversed flame graph. The current view can be opened in a new browser
tab, also as a new viewer with all the current settings.

## GC logs analyzer

Analyzer of the JVM unified logs (JDK 9+) and of JDK 8 GC and safepoint logs.
**The logs have to be in a proper format with proper decorators**, check the sections at the end for the
limitations and a working configuration.

It is available as:
* a part of the web toolkit (`toolkit-web-application`) - `http://localhost:8079/upload`
* a standalone Swing application, much faster for big logs:
  `java -jar gc-logs-analyzer-standalone-application/target/gc-logs-analyzer-standalone.jar`
* a command line application that writes an HTML report with charts to the `report` directory:
  `java -jar gc-logs-analyzer-cli-application/target/gc-logs-analyzer-cli.jar <log file>`

The web analyzer can be configured with environment variables (handy in Docker - the image is built with the
`spring-boot:build-image` goal of `toolkit-web-application`):

| Variable                   | Default        | Description                                                      |
|----------------------------|----------------|------------------------------------------------------------------|
| `SERVER_PORT`              | `8079`         | HTTP port                                                        |
| `UPLOAD_DIR`               | temp directory | where the uploaded logs are stored                               |
| `MAX_REQUEST_SIZE`         | `20480MB`      | maximum size of an uploaded file and request                     |
| `NUMBER_OF_WORKER_THREADS` | `4`            | threads parsing the uploaded logs, more requests are queued      |
| `REPORT_REMOVE_AFTER_READ` | `false`        | report is removed from memory once it is read                    |
| `REPORT_EXPIRATION`        | `10m`          | time after which a report is removed from memory                 |

`gc-log-samples` contains logs of every collector from several JDKs, generated by `gc-log-generator`. All of
them are parsed by the tests of `gc-logs-analyzer-commons`.

### Garbage collector logs analyzer

```
gc*,
gc+stringdedup=debug,
gc+ergo=trace,
gc+age=trace,
gc+phases=trace,
gc+humongous=trace
```

There are multiple charts and tables generated by this tool, only the most important ones are shown here.

#### Garbage collector table statistics

![](images/gc-logs/gc1.png)
![](images/gc-logs/gc2.png)

#### Heap before/after garbage collection

![](images/gc-logs/gc7.png)

![](images/gc-logs/gc3.png)

#### G1GC max number of regions

![](images/gc-logs/gc4.png)

#### Garbage collector phase time

![](images/gc-logs/gc5.png)

#### Allocation rate

![](images/gc-logs/gc6.png)

### Safepoint logs analyzer

```
-Xlog:safepoint
```

From safepoint logs this tool will create tables and charts:

#### Safepoint operation statistics

![](images/gc-logs/so1.png)

In this table you can find aggregated statistics of:
* each safepoint operation
* time to safepoint

#### Total time in phases

![](images/gc-logs/so2.png)

This chart shows how much time your JVM spends in safepoint operation, time to safepoint and in your application.

#### Application time (in time)

![](images/gc-logs/so3.png)

This charts show how much time your JVM spends running your application in 2/5/15 seconds windows.

#### Safepoint operation count

![](images/gc-logs/so4.png)

This chart shows distribution of safepoint operation count.

#### Safepoint operation time

![](images/gc-logs/so5.png)

This chart shows distribution of time between each safepoint operation.

#### Safepoint operation (in time)

![](images/gc-logs/so6.png)

This chart shows distribution of safepoint operation in time.

### Classloader logs analyzer

```
-Xlog:class+unload,class+load
```

From classloader logs this tool will create charts:

#### Current class count and class loading activity

![](images/gc-logs/cl1.png)

![](images/gc-logs/cl2.png)

### Thread logs analyzer

```
-Xlog:os+thread
```

From OS thread logs this tool will create charts:

#### Current thread count and thread creation activity

![](images/gc-logs/th1.png)

![](images/gc-logs/th2.png)

### Compilation logs analyzer

```
-Xlog:jit+compilation=debug
```

From JIT compilation logs this tool will create charts:

#### Current compilation count with and without tier distinguish

![](images/gc-logs/jit1.png)

![](images/gc-logs/jit2.png)

### Code cache and sweeper logs analyzer

```
-Xlog:codecache+sweep*=trace
```

From code cache and sweeper logs this tool will create charts:

#### Current code cache size

![](images/gc-logs/cc1.png)

#### Sweeper activity

![](images/gc-logs/cc2.png)

### JVM Xlog configuration

I recommend to use following Xlog configuration
```
-Xlog:codecache=info,
codecache+sweep*=trace,
class+unload,
class+load,
os+thread,
safepoint,
gc*,
gc+stringdedup=debug,
stringdedup*=debug,
gc+ergo=trace,
gc+age=trace,
gc+phases=trace,
gc+humongous=trace,
gc+heap=trace,
gc+tlab=trace,
jit+compilation=debug
:file=/tmp/app.log
:level,tags,time,uptime
:filesize=104857600,filecount=5
```

If you want to change Xlog configuration on runtime, you can do it with:
```
sudo -u JVM_USER jcmd `pgrep -x java` VM.log
output="file=/PATH/TO/gc.log"
output_options="filesize=104857600,filecount=5"
what="codecache=info,codecache+sweep*=trace,class+unload,class+load,os+thread,safepoint,gc*,gc+stringdedup=debug,stringdedup*=debug,gc+ergo=trace,gc+age=trace,gc+phases=trace,gc+humongous=trace,gc+heap=trace,gc+tlab=trace,jit+compilation=debug"
decorators="level,tags,time,uptime,pid"
```

The configuration works on every JDK 11+: `gc+stringdedup` is used up to JDK 16 and `stringdedup*` since JDK 17,
`codecache+sweep*` matches nothing since JDK 20 (the sweeper was removed).

For JDK 8 use:
```
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCTimeStamps
-XX:+PrintTenuringDistribution
-XX:+PrintGCApplicationStoppedTime
-XX:+PrintGCApplicationConcurrentTime
-XX:+PrintSafepointStatistics
-XX:PrintSafepointStatisticsCount=1
```

JDK 8 prints the GC log and the safepoint statistics to the standard output, save it to a file
(`java ... > /tmp/app.log`). Do not add `-Xloggc` (the GC log would be separated from the safepoint statistics)
nor `-XX:+LogVMOutput` (it escapes the output as XML).

### Current limitations

* Logs from JDK 11, 17, 25 and 27 - work with the configuration above, tested with Parallel, G1, ZGC (JDK 17+)
  and Shenandoah (also generational on JDK 25+) on Linux and macOS, with both dot and comma decimal separators
  (e.g. the `pl_PL` locale). The samples are in `gc-log-samples` and are checked by `SampleLogsSpec`.
* Logs from JDK 9, 10 and the versions between the tested ones - should work.
* Logs from JDK 8 - GC logs of Parallel and G1 and safepoint statistics, with the JDK 8 configuration above.
  Choose the JDK 8 parser (the upload form, `gcCollector=JDK8` with cURL, or the menu of the standalone application).
* CMS is not supported (JDK 8 CMS logs do not work, JDK 9-13 CMS logs have not been tested).
* The parser has to be chosen when the logs are uploaded (G1 / Parallel, Shenandoah or ZGC for JDK 9+, or JDK 8);
  the command line application parses JDK 9+ G1 / Parallel logs only.
* For JDK 9+ decorators: level,tags,time,uptime are needed, check sample Xlog configuration above.

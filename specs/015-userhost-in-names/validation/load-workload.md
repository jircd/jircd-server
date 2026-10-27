# Compiled load workload (not executed)

`UserhostInNamesLoadTest` is tagged `load`, parameterized at 10/100/1,000 members, with one warmup and five measured full NAMES/PING-barrier requests per invocation. Default candidate mode enables and negotiates both capabilities. The source uses only existing baseline fixture/protocol APIs; copy this exact test-only source to the baseline worktree, without capability implementation, and set `JIRCD_NAMES_BASELINE=true` in the environment for the baseline invocation. This omits userhost config/request and expects bare nicknames. An environment switch is used because Gradle does not automatically forward arbitrary command-line JVM system properties to forked test workers.

Both modes use the same members/rate limits/2 MiB SendQ and check exact membership, duplicates, 512-byte wire lines and final 366. Names are nine characters to fit the default nickname limit. Reports use Locale.ROOT and contain p50/p99/max and requests/sec. The coordinator supplies >=5 complete matching runs per revision plus CPU/allocation profiling and existing load comparisons for T022.

Only `compileTestJava` and static checks are authorized in this round. No timing results or performance acceptance are claimed. Ordinary `test`/`build` excludes the load tag. Do not overlap measurement runs with other machine measurements.

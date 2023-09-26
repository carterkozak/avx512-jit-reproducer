# AVX-512 Incorrect Results Reproducer

### Environment:

Linux x86 64-bit systems with AVX-512 support appear to have regressed after
https://github.com/openjdk/jdk/pull/9037 merged, impacting both JDK-20 and
JDK-21. This benchmark succeeds on older JDK builds. Jdk-20 and jdk-21 GA
releases are impacted, but only when `UseAVX=3` is set (the default for
many modern systems).

Explicitly setting `-XX:UseAVX=2` (or lower) leads to successful termination.
Setting `-XX:+UseKNLSetting` with `-XX:UseAVX=3` also resolves issues,
as does `-XX:ArrayCopyLoadStoreMaxElem=1`.

### Running this Reproducer:

```bash
./gradlew distZip
```

Will produce `build/distributions/avx512-jit-reproducer.zip`
This can be unpacked, and executed using `./bin/avx512-jit-reproducer` with standard JMH options.

Alternatively, `./gradlew run` or an IDE may be used to execute the repro benchmark, however
you must ensure hte expected JDK is being used at runtime.

### Expected:

The benchmark should complete successfully after ~15 seconds.
```
# JMH version: 1.37
# VM version: JDK 19.0.1, OpenJDK 64-Bit Server VM, 19.0.1+10-FR
# VM invoker: /corretto-19.0.1/bin/java
# VM options: -XX:UseAVX=3
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: <none>
# Measurement: 1 iterations, 15 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time
# Benchmark: net.ckozak.Repro.attempt

# Run progress: 0.00% complete, ETA 00:00:15
# Fork: 1 of 1
Iteration   1: 12465666.373 ops/s

Result "net.ckozak.Repro.attempt":
  12465666.373 ops/s

# Run complete. Total time: 00:00:18

Benchmark       Mode  Cnt         Score   Error  Units
Repro.attempt  thrpt       12465666.373          ops/s

Process finished with exit code 0
```

### What happens instead?

This snippet throws due to unexpected results:
https://github.com/carterkozak/avx512-jit-reproducer/blob/1687295a06d63cc1c1d2fd9a4b60f7c093d9d714/src/main/java/net/ckozak/Repro.java#L46-L54

```asciidoc
java.lang.RuntimeException: Expected Value{hash=3373707, name=name, offset=0} but found Value{hash=3373707, name=    , offset=0}
	at net.ckozak.Repro.attempt(Repro.java:52)
	at net.ckozak.jmh_generated.Repro_attempt_jmhTest.attempt_thrpt_jmhStub(Repro_attempt_jmhTest.java:144)
	at net.ckozak.jmh_generated.Repro_attempt_jmhTest.attempt_Throughput(Repro_attempt_jmhTest.java:84)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.openjdk.jmh.runner.BenchmarkHandler$BenchmarkTask.call(BenchmarkHandler.java:527)
	at org.openjdk.jmh.runner.BenchmarkHandler$BenchmarkTask.call(BenchmarkHandler.java:504)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
	at java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:572)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
```
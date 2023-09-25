# AVX-512 Incorrect Results Reproducer

### Environment:

Linux x86 64-bit systems with AVX-512 support appear to have regressed after
https://github.com/openjdk/jdk/pull/9037 merged. This benchmark succeeds on
older JDK builds. Jdk-20 and jdk-21 GA releases are impacted, but only when
`UseAVX=3` is set (the default for many modern systems).

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

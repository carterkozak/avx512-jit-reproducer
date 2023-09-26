package net.ckozak;

import com.codahale.metrics.MetricRegistry;
import com.google.common.primitives.Longs;
import com.palantir.atlasdb.config.ImmutableAtlasDbConfig;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.factory.TransactionManagers;
import com.palantir.atlasdb.memory.InMemoryAtlasDbConfig;
import com.palantir.atlasdb.ptobject.EncodingUtils;
import com.palantir.common.persist.Persistable;
import com.palantir.conjure.java.api.config.service.UserAgent;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Objects;

@Measurement(iterations = 1, time = 15)
@Warmup(iterations = 0)
@Threads(1)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {
        // Failure only occurs with AVX-512.
        // This option is not required, however it is often helpful due to
        // JDK defaults which opt out of AVX-512 for performance reasons
        // on some platforms.
        "-XX:UseAVX=3",
})
// The above line may be updated thusly:
// }, jvm = "/path/to/jdk-21/bin/java")
public class Repro {

    @Setup
    public void setup() {
        for (int i = 0; i < 100; i++) {
            runAtlasInit();
        }
    }

    @Benchmark
    public void attempt() {
        Value value = Value.of("name", 0);
        byte[] bytes = value.persistToBytes();
        Value hydrated = Value.parse(bytes);
        if (!value.equals(hydrated)) {
            throw new RuntimeException("Expected " + value + " but found " + hydrated);
        }
    }

    public static final class Value implements Persistable {
        private final long hash;
        private final String name;
        private final long offset;

        public static Value of(String series, long offset) {
            long hash = series.hashCode();
            return new Value(hash, series, offset);
        }

        private Value(long hash, String name, long offset) {
            this.hash = hash;
            this.name = name;
            this.offset = offset;
        }

        @Override
        public byte[] persistToBytes() {
            byte[] hashBytes = Longs.toByteArray(Long.MIN_VALUE ^ hash);
            byte[] seriesBytes = EncodingUtils.encodeVarString(name);
            byte[] offsetBytes = Longs.toByteArray(Long.MIN_VALUE ^ offset);
            return com.google.common.primitives.Bytes.concat(hashBytes, seriesBytes, offsetBytes);
        }

        static Value parse(byte[] bytes) {
            int index = 0;
            long hash = Long.MIN_VALUE ^ PtBytes.toLong(bytes, index);
            index += 8;
            String series = EncodingUtils.decodeVarString(bytes, index);
            index += EncodingUtils.sizeOfVarString(series);
            long offset = Long.MIN_VALUE ^ PtBytes.toLong(bytes, index);
            return new Value(hash, series, offset);
        }

        @Override
        public String toString() {
            return "Value{hash=" + hash + ", name=" + name + ", offset=" + offset +'}';
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            return obj instanceof Value other && Objects.equals(hash, other.hash)
                    && Objects.equals(name, other.name)
                    && Objects.equals(offset, other.offset);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Long.hashCode(hash) + name.hashCode()) + Long.hashCode(offset);
        }
    }

    private static void runAtlasInit() {
        TransactionManagers.builder()
                .config(CONFIG)
                .userAgent(USER_AGENT)
                .globalMetricsRegistry(METRIC_REGISTRY)
                .globalTaggedMetricRegistry(TAGGED_METRIC_REGISTRY)
                .build()
                .serializable()
                .close();
    }

    private static final DefaultTaggedMetricRegistry TAGGED_METRIC_REGISTRY = new DefaultTaggedMetricRegistry();
    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();
    private static final UserAgent USER_AGENT = UserAgent.of(UserAgent.Agent.of("unknown", "0.0.0"));
    private static final ImmutableAtlasDbConfig CONFIG = ImmutableAtlasDbConfig.builder()
            .keyValueService(new InMemoryAtlasDbConfig())
            .backgroundScrubReadThreads(1)
            .backgroundScrubThreads(1)
            .collectThreadDumpOnTimestampServiceInit(false)
            .build();
}

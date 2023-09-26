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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Objects;

@Measurement(iterations = 3, time = 10)
@Warmup(iterations = 0)
@Threads(Threads.MAX)
@Fork(value = 1, jvmArgsAppend = {
        "-XX:+UnlockDiagnosticVMOptions",
        // no tiered compilation and relatively low threshold
        // to tease out failures more quickly/more deterministic.
        "-XX:-TieredCompilation",
        "-XX:CompileThreshold=300",
        // Failure only occurs with AVX-512.
        "-XX:UseAVX=3",
        "-XX:CompileCommand=compileonly,net.ckozak.Repro$Value::persistToBytes",
        "-XX:CompileCommand=inline,com.google.common.primitives.Bytes::concat",
        "-XX:CompileCommand=inline,com.google.common.primitives.Longs::toByteArray",
        "-XX:CompileCommand=inline,com.google.protobuf.CodedOutputStream::computeUInt64SizeNoTag",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.encoding.PtBytes::toBytes",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::add",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeSizedBytes",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeVarLong",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeVarString",
})
// The above line may be updated thusly:
// }, jvm = "/path/to/jdk-21/bin/java")
public class Repro {

    @Benchmark
    public void attempt() {
        runAtlasInit();
        for (int i = 0; i < 10; i++) {
            Value value = Value.of("name", i);
            byte[] bytes = value.persistToBytes();
            Value hydrated = Value.parse(bytes);
            if (!value.equals(hydrated)) {
                throw new RuntimeException("Expected " + value + " but found " + hydrated);
            }
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

package net.ckozak;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ComparisonChain;
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

import java.util.Arrays;
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
        "-XX:CompileCommand=compileonly,net.ckozak.Repro$OrderedRow::persistToBytes",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.encoding.PtBytes::toBytes",
        "-XX:CompileCommand=inline,com.google.common.primitives.Longs::toByteArray",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeVarString",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeSizedBytes",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::encodeVarLong",
        "-XX:CompileCommand=inline,com.google.protobuf.CodedOutputStream::computeUInt64SizeNoTag",
        "-XX:CompileCommand=inline,com.google.common.primitives.Bytes::concat",
        "-XX:CompileCommand=inline,com.palantir.atlasdb.ptobject.EncodingUtils::add",
})
// The above line may be updated thusly:
// }, jvm = "/path/to/jdk-21/bin/java")
public class Repro {

    @Benchmark
    public void attempt() {
        runAtlasInit();
        for (int i = 0; i < 10; i++) {
            OrderedRow row = OrderedRow.of("name", i);
            byte[] val = row.persistToBytes();
            OrderedRow hydrated = OrderedRow.parse(val);
            if (!row.equals(hydrated)) {
                throw new RuntimeException("Expected " + row + " but found " + hydrated);
            }
        }
    }

    public static final class OrderedRow implements Persistable {
        private final long hashOfRowComponents;
        private final String series;
        private final long offset;

        public static OrderedRow of(String series, long offset) {
            long hashOfRowComponents = series.hashCode();
            return new OrderedRow(hashOfRowComponents, series, offset);
        }

        private OrderedRow(long hashOfRowComponents, String series, long offset) {
            this.hashOfRowComponents = hashOfRowComponents;
            this.series = series;
            this.offset = offset;
        }

        @Override
        public byte[] persistToBytes() {
            byte[] hashOfRowComponentsBytes = Longs.toByteArray(Long.MIN_VALUE ^ hashOfRowComponents);
            byte[] seriesBytes = EncodingUtils.encodeVarString(series);
            byte[] offsetBytes = Longs.toByteArray(Long.MIN_VALUE ^ offset);
            return com.google.common.primitives.Bytes.concat(hashOfRowComponentsBytes, seriesBytes, offsetBytes);
        }

        static OrderedRow parse(byte[] __input) {
            int __index = 0;
            Long hashOfRowComponents = Long.MIN_VALUE ^ PtBytes.toLong(__input, __index);
            __index += 8;
            String series = EncodingUtils.decodeVarString(__input, __index);
            __index += EncodingUtils.sizeOfVarString(series);
            Long offset = Long.MIN_VALUE ^ PtBytes.toLong(__input, __index);
            __index += 8;
            return new OrderedRow(hashOfRowComponents, series, offset);
        }

        @Override
        public String toString() {
            return "OrderedRow{hashOfRowComponents="
                    + hashOfRowComponents + ", series=" + series + ", offset=" + offset +'}';
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            OrderedRow other = (OrderedRow) obj;
            return Objects.equals(hashOfRowComponents, other.hashOfRowComponents)
                    && Objects.equals(series, other.series)
                    && Objects.equals(offset, other.offset);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(new Object[] {hashOfRowComponents, series, offset});
        }
    }

    private static void runAtlasInit() {
        TransactionManagers.builder()
                .config(ImmutableAtlasDbConfig.builder()
                        .keyValueService(new InMemoryAtlasDbConfig())
                        .backgroundScrubReadThreads(1)
                        .backgroundScrubThreads(1)
                        .collectThreadDumpOnTimestampServiceInit(false)
                        .build())
                .userAgent(UserAgent.of(UserAgent.Agent.of("unknown", "0.0.0")))
                .globalMetricsRegistry(new MetricRegistry())
                .globalTaggedMetricRegistry(new DefaultTaggedMetricRegistry())
                .build()
                .serializable()
                .close();
    }
}

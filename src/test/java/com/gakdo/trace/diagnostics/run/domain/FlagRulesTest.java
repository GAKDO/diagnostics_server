package com.gakdo.trace.diagnostics.run.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gakdo.trace.diagnostics.common.infra.PublicIds;
import com.gakdo.trace.diagnostics.common.infra.Times;
import com.gakdo.trace.diagnostics.support.Examples;

/** flag-rules-v1: 같은 렌탈 앞 진단과 비교해 부품 변경·용량 급감·벤치 하락을 붙인다(FR-DIA-06). */
class FlagRulesTest {

    private static DiagnosticRun run(Consumer<ObjectNode> change) throws Exception {
        byte[] body = Examples.modified(Examples.MACOS, change);
        DiagnosticUpload upload = Examples.JSON.findAndRegisterModules().readValue(body, DiagnosticUpload.class);
        return DiagnosticRun.of(PublicIds.next(), PublicIds.next(), PublicIds.next(), "k", upload,
                new DiagnosticRun.RawBody(new String(body, StandardCharsets.UTF_8), "0".repeat(64)), Times.now());
    }

    private static ObjectNode component(ObjectNode node, String kind) {
        for (var c : (ArrayNode) node.get("components")) {
            if (kind.equals(c.get("kind").asText())) {
                return (ObjectNode) c;
            }
        }
        throw new IllegalArgumentException(kind);
    }

    private static void benchMedian(ObjectNode node, String metric, double median) {
        for (var b : (ArrayNode) node.get("benchmarks")) {
            if (metric.equals(b.get("metric").asText())) {
                ((ObjectNode) b).put("median", median);
                ArrayNode trials = ((ObjectNode) b).putArray("trials");
                trials.add(median).add(median).add(median);
            }
        }
    }

    @Test
    @DisplayName("같은 기기를 다시 진단하면 플래그가 없다")
    void sameDeviceNoFlags() throws Exception {
        assertThat(FlagRules.compare(run(n -> { }), run(n -> { }))).isEmpty();
    }

    @Test
    @DisplayName("SSD 시리얼이 바뀌면 COMPONENT_CHANGED 하나가 붙고 근거에 앞뒤 값이 있다")
    void componentChanged() throws Exception {
        DiagnosticRun base = run(n -> { });
        DiagnosticRun now = run(n -> ((ObjectNode) component(n, "SSD").get("detail")).put("serial", "SSDOTHER9"));

        List<FlagRules.Draft> flags = FlagRules.compare(base, now);

        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo("COMPONENT_CHANGED");
            assertThat(f.detail()).containsEntry("componentKind", "SSD")
                    .containsEntry("before", List.of("APPLE SSD AP0256Z#SSDTEST01"))
                    .containsEntry("after", List.of("APPLE SSD AP0256Z#SSDOTHER9"));
        });
    }

    @Test
    @DisplayName("배터리 최대 용량이 5%p 이상 떨어지면 CAPACITY_DROP, 그보다 적으면 붙지 않는다")
    void capacityDrop() throws Exception {
        DiagnosticRun base = run(n -> { }); // 3901/4382 = 89.02%
        DiagnosticRun small = run(n -> ((ObjectNode) n.get("battery").get("fullChargeCapacity")).put("value", 3800));
        DiagnosticRun big = run(n -> ((ObjectNode) n.get("battery").get("fullChargeCapacity")).put("value", 3600));

        assertThat(FlagRules.compare(base, small)).isEmpty();
        assertThat(FlagRules.compare(base, big)).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo("CAPACITY_DROP");
            assertThat((Double) f.detail().get("dropPct")).isGreaterThanOrEqualTo(5.0);
        });
    }

    @Test
    @DisplayName("벤치마크 중앙값이 15% 이상 떨어진 지표만 BENCH_DROP이 붙는다")
    void benchDrop() throws Exception {
        DiagnosticRun base = run(n -> { });
        DiagnosticRun now = run(n -> {
            benchMedian(n, "DISK_WRITE", 1500);   // 2105 → 1500, 28.7% 하락
            benchMedian(n, "CPU_SINGLE", 950);    // 1021.3 → 950, 7% 하락(편차 범위)
        });

        assertThat(FlagRules.compare(base, now)).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo("BENCH_DROP");
            assertThat(f.detail()).containsEntry("metric", "DISK_WRITE").containsEntry("thresholdPct", 15.0);
        });
    }

    @Test
    @DisplayName("한쪽이 벤치마크·배터리를 생략했으면 그 항목은 비교하지 않는다")
    void skippedValuesAreNotCompared() throws Exception {
        DiagnosticRun base = run(n -> { });
        DiagnosticRun now = run(n -> n.putArray("benchmarks"));
        assertThat(FlagRules.compare(base, now)).isEmpty();
    }

    @Test
    @DisplayName("활성화 잠금·디스크 암호화가 켜지고 SSD가 경고면 앞 진단 없이도 상태 플래그 3개가 붙는다")
    void lockedDeviceGetsStateFlags() throws Exception {
        DiagnosticRun now = run(n -> n.putObject("checks").put("activationLock", "ON").put("diskEncryption", "ON")
                .put("osDiskHealth", "WARNING"));
        assertThat(FlagRules.states(now)).extracting(FlagRules.Draft::kind).containsExactly(
                FlagRules.ACTIVATION_LOCK_ON, FlagRules.DISK_ENCRYPTION_ON, FlagRules.DISK_HEALTH_WARNING);
        assertThat(FlagRules.states(now).get(0).detail()).containsEntry("field", "activationLock")
                .containsEntry("value", "ON").containsEntry("os", "MACOS");
    }

    @Test
    @DisplayName("잠금이 꺼져 있거나 못 읽었거나 checks가 없는 옛 진단이면 상태 플래그가 없다")
    void unlockedOrUnknownNoStateFlags() throws Exception {
        assertThat(FlagRules.states(run(n -> n.putObject("checks").put("activationLock", "OFF")
                .put("diskEncryption", "OFF").put("osDiskHealth", "OK")))).isEmpty();
        assertThat(FlagRules.states(run(n -> n.putObject("checks").putNull("activationLock")
                .putNull("diskEncryption").putNull("osDiskHealth")))).isEmpty();
        assertThat(FlagRules.states(run(n -> { }))).isEmpty();
    }
}

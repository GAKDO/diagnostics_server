// 이 파일이 확인하는 것: 점검 플래그 규칙(flag-rules-v1). 같은 렌탈의 앞 진단(예: 출고)과 이번 진단(예: 반납)을 비교해
// 관리자가 눈여겨볼 변화에 표시(플래그)를 붙인다.
//   - COMPONENT_CHANGED: 부품(SSD 등) 시리얼이 바뀜, CAPACITY_DROP: 배터리 최대 용량 5%p 이상 하락
//   - BENCH_DROP: 성능 중앙값 15% 이상 하락, THREAT_DETECTED: 보안 위협 건수 증가
//   - 앞 진단 없이도 붙는 상태 플래그: 활성화 잠금 켜짐, 디스크 암호화 켜짐, 디스크 상태 경고, 보안 보호 꺼짐

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

    // 테스트 도구: 맥 예시 진단을 바탕으로 일부 값만 바꾼 진단 한 건을 만든다.
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

    // [상황] 같은 기기를 아무것도 바꾸지 않고 두 번 진단한다.
    // [기대] 플래그가 하나도 없다.
    @Test
    @DisplayName("같은 기기를 다시 진단하면 플래그가 없다")
    void sameDeviceNoFlags() throws Exception {
        assertThat(FlagRules.compare(run(n -> { }), run(n -> { }))).isEmpty();
    }

    // [상황] 반납 진단에서 SSD 시리얼만 다른 값으로 바뀌어 있다(부품 바꿔치기 의심).
    // [기대] COMPONENT_CHANGED 플래그 하나가 붙고, 근거에 바뀌기 전·후 SSD 시리얼이 적힌다.
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

    // [상황] 출고 때 배터리 최대 용량 89.02%. 반납 때 조금 떨어진 경우(3800)와 많이 떨어진 경우(3600)를 비교한다.
    // [기대] 5%p 미만 하락은 플래그 없음, 5%p 이상 하락은 CAPACITY_DROP이 붙는다.
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

    // [상황] 디스크 쓰기 성능은 약 29% 떨어지고, CPU 단일 성능은 7%(측정 편차 수준)만 떨어졌다.
    // [기대] 15% 넘게 떨어진 디스크 쓰기에만 BENCH_DROP이 붙는다.
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

    // [상황] 이번 진단은 벤치마크를 건너뛰어 값이 없다.
    // [기대] 비교할 값이 없으니 플래그를 붙이지 않는다(없는 값을 하락으로 오해하지 않음).
    @Test
    @DisplayName("한쪽이 벤치마크·배터리를 생략했으면 그 항목은 비교하지 않는다")
    void skippedValuesAreNotCompared() throws Exception {
        DiagnosticRun base = run(n -> { });
        DiagnosticRun now = run(n -> n.putArray("benchmarks"));
        assertThat(FlagRules.compare(base, now)).isEmpty();
    }

    // [상황] 활성화 잠금(도난 방지 잠금) 켜짐, 디스크 암호화 켜짐, SSD 상태 경고인 진단.
    // [기대] 앞 진단이 없어도 상태 플래그 3개가 붙고, 근거에 어떤 항목이 어떤 값인지와 운영체제가 적힌다.
    //        잠긴 채로 반납되면 다음 고객에게 빌려줄 수 없기 때문.
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

    // [상황] 잠금·암호화가 꺼져 있고 SSD 정상인 진단 / 세 값을 못 읽은 진단 / 점검 항목이 아예 없는 옛 진단.
    // [기대] 세 경우 모두 상태 플래그가 없다(못 읽은 값은 문제로 보지 않음).
    @Test
    @DisplayName("잠금이 꺼져 있거나 못 읽었거나 checks가 없는 옛 진단이면 상태 플래그가 없다")
    void unlockedOrUnknownNoStateFlags() throws Exception {
        assertThat(FlagRules.states(run(n -> n.putObject("checks").put("activationLock", "OFF")
                .put("diskEncryption", "OFF").put("osDiskHealth", "OK")))).isEmpty();
        assertThat(FlagRules.states(run(n -> n.putObject("checks").putNull("activationLock")
                .putNull("diskEncryption").putNull("osDiskHealth")))).isEmpty();
        assertThat(FlagRules.states(run(n -> { }))).isEmpty();
    }

    // 테스트 도구: 보안 위협 건수만 정한 진단을 만든다(null이면 "못 읽음").
    private static DiagnosticRun threats(Integer count) throws Exception {
        return run(n -> {
            ObjectNode c = n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                    .putNull("osDiskHealth");
            if (count == null) {
                c.putNull("threatCount");
            } else {
                c.put("threatCount", count);
            }
        });
    }

    // [상황] 보안 보호(백신·XProtect 등)가 꺼진 진단과 켜진 진단.
    // [기대] 꺼진 쪽만 SECURITY_PROTECTION_OFF가 붙는다.
    @Test
    @DisplayName("보안 보호가 꺼져 있으면 앞 진단 없이도 SECURITY_PROTECTION_OFF가 붙고 켜져 있으면 없다")
    void securityOffGetsStateFlag() throws Exception {
        DiagnosticRun off = run(n -> n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                .putNull("osDiskHealth").put("securityProtection", "OFF"));
        assertThat(FlagRules.states(off)).extracting(FlagRules.Draft::kind)
                .containsExactly(FlagRules.SECURITY_PROTECTION_OFF);
        DiagnosticRun on = run(n -> n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                .putNull("osDiskHealth").put("securityProtection", "ON"));
        assertThat(FlagRules.states(on)).isEmpty();
    }

    // [상황] 출고 때 위협 1건, 반납 때 3건.
    // [기대] THREAT_DETECTED가 붙고, 비교 기준은 앞 진단이며 근거에 1 → 3이 적힌다.
    @Test
    @DisplayName("위협 건수가 앞 진단보다 늘면 THREAT_DETECTED가 붙고 기준은 앞 진단이며 근거에 앞뒤 건수가 있다")
    void threatIncreaseIsFlagged() throws Exception {
        var flag = FlagRules.threat(threats(1), threats(3)).orElseThrow();
        assertThat(flag.compared()).isTrue();
        assertThat(flag.draft().kind()).isEqualTo(FlagRules.THREAT_DETECTED);
        assertThat(flag.draft().detail()).containsEntry("before", 1).containsEntry("after", 3);
    }

    // [상황] 빌려주기 전부터 위협 기록이 2건 있었고 반납 때도 2건이다.
    // [기대] 고객 탓이 아니므로 플래그를 붙이지 않는다.
    @Test
    @DisplayName("대여 전부터 있던 위협 기록이 그대로면 반납 때 다시 붙지 않는다")
    void sameThreatCountNotFlagged() throws Exception {
        assertThat(FlagRules.threat(threats(2), threats(2))).isEmpty();
    }

    // [상황] 앞 진단이 없거나 앞 건수를 못 읽은 상태에서 이번 위협 건수가 1·2·0·모름인 경우.
    // [기대] 이번에 1건 이상일 때만 붙고, 이때 비교 기준은 이번 진단 자신이다. 0건이나 모름이면 붙지 않는다.
    @Test
    @DisplayName("첫 진단이나 앞 건수를 못 읽었으면 위협이 1건 이상일 때만 붙고 기준은 이번 진단이다")
    void firstRunThreatsFlaggedWithoutBase() throws Exception {
        var first = FlagRules.threat(null, threats(1)).orElseThrow();
        assertThat(first.compared()).isFalse();
        assertThat(FlagRules.threat(threats(null), threats(2)).orElseThrow().compared()).isFalse();
        assertThat(FlagRules.threat(null, threats(0))).isEmpty();
        assertThat(FlagRules.threat(threats(0), threats(null))).isEmpty();
        assertThat(FlagRules.threat(null, run(n -> { }))).isEmpty();
    }
}

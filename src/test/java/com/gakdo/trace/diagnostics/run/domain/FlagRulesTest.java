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
        // 맥 예시에 부른 쪽이 준 고치기를 적용한 JSON 바이트를 만든다
        byte[] body = Examples.modified(Examples.MACOS, change);
        // 그 바이트를 서버가 받는 업로드 객체로 읽는다
        DiagnosticUpload upload = Examples.JSON.findAndRegisterModules().readValue(body, DiagnosticUpload.class);
        // 새 ID들과 원문·해시 자리값을 넣어 진단 한 건으로 만들어 돌려준다
        return DiagnosticRun.of(PublicIds.next(), PublicIds.next(), PublicIds.next(), "k", upload,
                new DiagnosticRun.RawBody(new String(body, StandardCharsets.UTF_8), "0".repeat(64)), Times.now());
    }

    // 테스트 도구: 진단 JSON의 부품 목록에서 그 종류(예: SSD) 부품을 찾는다
    private static ObjectNode component(ObjectNode node, String kind) {
        // 부품 목록을 하나씩 보며
        for (var c : (ArrayNode) node.get("components")) {
            // 종류가 같으면
            if (kind.equals(c.get("kind").asText())) {
                // 그 부품을 돌려준다
                return (ObjectNode) c;
            }
        }
        // 못 찾으면 바로 알리고 멈춘다
        throw new IllegalArgumentException(kind);
    }

    // 테스트 도구: 그 성능 지표의 중앙값과 측정값 3개를 모두 같은 값으로 바꾼다
    private static void benchMedian(ObjectNode node, String metric, double median) {
        // 벤치마크 목록을 하나씩 보며
        for (var b : (ArrayNode) node.get("benchmarks")) {
            // 지표 이름이 같으면
            if (metric.equals(b.get("metric").asText())) {
                // 중앙값을 바꾸고
                ((ObjectNode) b).put("median", median);
                // 측정값 목록을 새로 비우고
                ArrayNode trials = ((ObjectNode) b).putArray("trials");
                // 같은 값 3개로 채운다(중앙값 규칙에 맞게)
                trials.add(median).add(median).add(median);
            }
        }
    }

    // [상황] 같은 기기를 아무것도 바꾸지 않고 두 번 진단한다.
    // [기대] 플래그가 하나도 없다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("같은 기기를 다시 진단하면 플래그가 없다")
    void sameDeviceNoFlags() throws Exception {
        // (도구) assertThat(목록).isEmpty() = 목록이 비어 있어야 통과하는 AssertJ 검사. n -> { } = 아무것도 안 바꿈
        // 실행+확인: 바꾼 것 없는 두 진단을 비교하면 플래그가 없어야 한다
        assertThat(FlagRules.compare(run(n -> { }), run(n -> { }))).isEmpty();
    }

    // [상황] 반납 진단에서 SSD 시리얼만 다른 값으로 바뀌어 있다(부품 바꿔치기 의심).
    // [기대] COMPONENT_CHANGED 플래그 하나가 붙고, 근거에 바뀌기 전·후 SSD 시리얼이 적힌다.
    @Test
    @DisplayName("SSD 시리얼이 바뀌면 COMPONENT_CHANGED 하나가 붙고 근거에 앞뒤 값이 있다")
    void componentChanged() throws Exception {
        // 준비: 앞(출고) 진단
        DiagnosticRun base = run(n -> { });
        // 준비: SSD 시리얼만 SSDOTHER9로 바뀐 이번(반납) 진단
        DiagnosticRun now = run(n -> ((ObjectNode) component(n, "SSD").get("detail")).put("serial", "SSDOTHER9"));

        // 실행: 두 진단을 비교해 플래그 목록을 받는다
        List<FlagRules.Draft> flags = FlagRules.compare(base, now);

        // (도구) singleElement().satisfies(검사) = 딱 하나만 있고 그 하나가 검사를 통과해야 한다
        // 확인: 플래그가 하나이고
        assertThat(flags).singleElement().satisfies(f -> {
            // (도구) isEqualTo(값) = 그 값과 같아야 통과
            // 종류가 COMPONENT_CHANGED여야 하며
            assertThat(f.kind()).isEqualTo("COMPONENT_CHANGED");
            // (도구) containsEntry(열쇠, 값) = 지도에 그 열쇠·값 짝이 있어야 통과
            // 근거에 부품 종류 SSD, 바뀌기 전·후 시리얼이 적혀 있어야 한다
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
        // 준비: 앞 진단(최대 용량 89.02%)
        DiagnosticRun base = run(n -> { }); // 3901/4382 = 89.02%
        // 준비: 완충 용량이 3800으로 조금 떨어진 진단(약 2.3%p 하락)
        DiagnosticRun small = run(n -> ((ObjectNode) n.get("battery").get("fullChargeCapacity")).put("value", 3800));
        // 준비: 3600으로 많이 떨어진 진단(약 6.9%p 하락)
        DiagnosticRun big = run(n -> ((ObjectNode) n.get("battery").get("fullChargeCapacity")).put("value", 3600));

        // 실행+확인: 조금 떨어진 경우는 플래그가 없어야 한다
        assertThat(FlagRules.compare(base, small)).isEmpty();
        // 실행+확인: 많이 떨어진 경우는 플래그가 하나이고
        assertThat(FlagRules.compare(base, big)).singleElement().satisfies(f -> {
            // 종류가 CAPACITY_DROP이어야 하며
            assertThat(f.kind()).isEqualTo("CAPACITY_DROP");
            // (도구) isGreaterThanOrEqualTo(값) = 그 값 이상이어야 통과
            // 근거의 하락 폭이 5%p 이상이어야 한다
            assertThat((Double) f.detail().get("dropPct")).isGreaterThanOrEqualTo(5.0);
        });
    }

    // [상황] 디스크 쓰기 성능은 약 29% 떨어지고, CPU 단일 성능은 7%(측정 편차 수준)만 떨어졌다.
    // [기대] 15% 넘게 떨어진 디스크 쓰기에만 BENCH_DROP이 붙는다.
    @Test
    @DisplayName("벤치마크 중앙값이 15% 이상 떨어진 지표만 BENCH_DROP이 붙는다")
    void benchDrop() throws Exception {
        // 준비: 앞 진단
        DiagnosticRun base = run(n -> { });
        // 준비: 성능 값만 바꾼 이번 진단
        DiagnosticRun now = run(n -> {
            // 디스크 쓰기를 1500으로(약 29% 하락)
            benchMedian(n, "DISK_WRITE", 1500);   // 2105 → 1500, 28.7% 하락
            // CPU 단일을 950으로(약 7% 하락)
            benchMedian(n, "CPU_SINGLE", 950);    // 1021.3 → 950, 7% 하락(편차 범위)
        });

        // 실행+확인: 비교하면 플래그가 하나이고
        assertThat(FlagRules.compare(base, now)).singleElement().satisfies(f -> {
            // 종류가 BENCH_DROP이어야 하며
            assertThat(f.kind()).isEqualTo("BENCH_DROP");
            // 근거에 지표 DISK_WRITE와 기준 15%가 적혀 있어야 한다(CPU는 편차 범위라 없음)
            assertThat(f.detail()).containsEntry("metric", "DISK_WRITE").containsEntry("thresholdPct", 15.0);
        });
    }

    // [상황] 이번 진단은 벤치마크를 건너뛰어 값이 없다.
    // [기대] 비교할 값이 없으니 플래그를 붙이지 않는다(없는 값을 하락으로 오해하지 않음).
    @Test
    @DisplayName("한쪽이 벤치마크·배터리를 생략했으면 그 항목은 비교하지 않는다")
    void skippedValuesAreNotCompared() throws Exception {
        // 준비: 앞 진단
        DiagnosticRun base = run(n -> { });
        // 준비: 벤치마크 목록이 빈(건너뛴) 이번 진단
        DiagnosticRun now = run(n -> n.putArray("benchmarks"));
        // 실행+확인: 비교하면 플래그가 없어야 한다 = 없는 값을 하락으로 보지 않는다
        assertThat(FlagRules.compare(base, now)).isEmpty();
    }

    // [상황] 활성화 잠금(도난 방지 잠금) 켜짐, 디스크 암호화 켜짐, SSD 상태 경고인 진단.
    // [기대] 앞 진단이 없어도 상태 플래그 3개가 붙고, 근거에 어떤 항목이 어떤 값인지와 운영체제가 적힌다.
    //        잠긴 채로 반납되면 다음 고객에게 빌려줄 수 없기 때문.
    @Test
    @DisplayName("활성화 잠금·디스크 암호화가 켜지고 SSD가 경고면 앞 진단 없이도 상태 플래그 3개가 붙는다")
    void lockedDeviceGetsStateFlags() throws Exception {
        // 준비: 활성화 잠금·디스크 암호화 켜짐, 디스크 상태 경고인 진단
        DiagnosticRun now = run(n -> n.putObject("checks").put("activationLock", "ON").put("diskEncryption", "ON")
                .put("osDiskHealth", "WARNING"));
        // (도구) extracting(칸) = 목록에서 그 칸만 뽑는다. containsExactly = 정확히 그것만 그 순서로 있어야 통과
        // 실행+확인: 상태 플래그 종류가 잠금 켜짐·암호화 켜짐·디스크 경고 3개여야 한다
        assertThat(FlagRules.states(now)).extracting(FlagRules.Draft::kind).containsExactly(
                FlagRules.ACTIVATION_LOCK_ON, FlagRules.DISK_ENCRYPTION_ON, FlagRules.DISK_HEALTH_WARNING);
        // 확인: 첫 플래그 근거에 항목(activationLock)·값(ON)·운영체제(MACOS)가 적혀 있어야 한다
        assertThat(FlagRules.states(now).get(0).detail()).containsEntry("field", "activationLock")
                .containsEntry("value", "ON").containsEntry("os", "MACOS");
    }

    // [상황] 잠금·암호화가 꺼져 있고 SSD 정상인 진단 / 세 값을 못 읽은 진단 / 점검 항목이 아예 없는 옛 진단.
    // [기대] 세 경우 모두 상태 플래그가 없다(못 읽은 값은 문제로 보지 않음).
    @Test
    @DisplayName("잠금이 꺼져 있거나 못 읽었거나 checks가 없는 옛 진단이면 상태 플래그가 없다")
    void unlockedOrUnknownNoStateFlags() throws Exception {
        // 실행+확인: 잠금·암호화 꺼짐, 디스크 정상이면 상태 플래그가 없어야 한다
        assertThat(FlagRules.states(run(n -> n.putObject("checks").put("activationLock", "OFF")
                .put("diskEncryption", "OFF").put("osDiskHealth", "OK")))).isEmpty();
        // 실행+확인: 세 값을 못 읽었으면(null) 상태 플래그가 없어야 한다
        assertThat(FlagRules.states(run(n -> n.putObject("checks").putNull("activationLock")
                .putNull("diskEncryption").putNull("osDiskHealth")))).isEmpty();
        // 실행+확인: 점검 항목이 아예 없는 옛 진단도 상태 플래그가 없어야 한다
        assertThat(FlagRules.states(run(n -> { }))).isEmpty();
    }

    // 테스트 도구: 보안 위협 건수만 정한 진단을 만든다(null이면 "못 읽음").
    private static DiagnosticRun threats(Integer count) throws Exception {
        // 점검 항목을 새로 만들고
        return run(n -> {
            // 잠금·암호화·디스크 상태는 "못 읽음"으로 두고
            ObjectNode c = n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                    .putNull("osDiskHealth");
            // 건수가 null이면
            if (count == null) {
                // 위협 건수도 "못 읽음"으로
                c.putNull("threatCount");
            } else {
                // 아니면 그 건수를 넣는다
                c.put("threatCount", count);
            }
        });
    }

    // [상황] 보안 보호(백신·XProtect 등)가 꺼진 진단과 켜진 진단.
    // [기대] 꺼진 쪽만 SECURITY_PROTECTION_OFF가 붙는다.
    @Test
    @DisplayName("보안 보호가 꺼져 있으면 앞 진단 없이도 SECURITY_PROTECTION_OFF가 붙고 켜져 있으면 없다")
    void securityOffGetsStateFlag() throws Exception {
        // 준비: 보안 보호가 꺼진(OFF) 진단
        DiagnosticRun off = run(n -> n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                .putNull("osDiskHealth").put("securityProtection", "OFF"));
        // 실행+확인: 상태 플래그 종류가
        assertThat(FlagRules.states(off)).extracting(FlagRules.Draft::kind)
                // SECURITY_PROTECTION_OFF 하나여야 한다
                .containsExactly(FlagRules.SECURITY_PROTECTION_OFF);
        // 준비: 보안 보호가 켜진(ON) 진단
        DiagnosticRun on = run(n -> n.putObject("checks").putNull("activationLock").putNull("diskEncryption")
                .putNull("osDiskHealth").put("securityProtection", "ON"));
        // 실행+확인: 상태 플래그가 없어야 한다
        assertThat(FlagRules.states(on)).isEmpty();
    }

    // [상황] 출고 때 위협 1건, 반납 때 3건.
    // [기대] THREAT_DETECTED가 붙고, 비교 기준은 앞 진단이며 근거에 1 → 3이 적힌다.
    @Test
    @DisplayName("위협 건수가 앞 진단보다 늘면 THREAT_DETECTED가 붙고 기준은 앞 진단이며 근거에 앞뒤 건수가 있다")
    void threatIncreaseIsFlagged() throws Exception {
        // (도구) orElseThrow() = 값이 있으면 꺼내고 없으면 예외(= 시험 실패)
        // 실행: 앞 1건 → 이번 3건으로 위협 플래그를 계산해 꺼낸다(없으면 실패)
        var flag = FlagRules.threat(threats(1), threats(3)).orElseThrow();
        // (도구) isTrue() = 값이 true여야 통과
        // 확인: 앞 진단과 비교한 플래그여야 한다
        assertThat(flag.compared()).isTrue();
        // 확인: 종류가 THREAT_DETECTED여야 한다
        assertThat(flag.draft().kind()).isEqualTo(FlagRules.THREAT_DETECTED);
        // 확인: 근거에 앞 1건, 이번 3건이 적혀 있어야 한다
        assertThat(flag.draft().detail()).containsEntry("before", 1).containsEntry("after", 3);
    }

    // [상황] 빌려주기 전부터 위협 기록이 2건 있었고 반납 때도 2건이다.
    // [기대] 고객 탓이 아니므로 플래그를 붙이지 않는다.
    @Test
    @DisplayName("대여 전부터 있던 위협 기록이 그대로면 반납 때 다시 붙지 않는다")
    void sameThreatCountNotFlagged() throws Exception {
        // 실행+확인: 앞뒤 모두 2건이면 플래그가 없어야 한다 = 고객 탓이 아니다
        assertThat(FlagRules.threat(threats(2), threats(2))).isEmpty();
    }

    // [상황] 앞 진단이 없거나 앞 건수를 못 읽은 상태에서 이번 위협 건수가 1·2·0·모름인 경우.
    // [기대] 이번에 1건 이상일 때만 붙고, 이때 비교 기준은 이번 진단 자신이다. 0건이나 모름이면 붙지 않는다.
    @Test
    @DisplayName("첫 진단이나 앞 건수를 못 읽었으면 위협이 1건 이상일 때만 붙고 기준은 이번 진단이다")
    void firstRunThreatsFlaggedWithoutBase() throws Exception {
        // 실행: 앞 진단 없이 이번 1건으로 계산해 꺼낸다
        var first = FlagRules.threat(null, threats(1)).orElseThrow();
        // (도구) isFalse() = 값이 false여야 통과
        // 확인: 비교 기준이 이번 진단 자신이어야 한다(앞 진단과 비교 안 함)
        assertThat(first.compared()).isFalse();
        // 실행+확인: 앞 건수를 못 읽었고 이번 2건이면 플래그가 붙고, 기준은 이번 진단이어야 한다
        assertThat(FlagRules.threat(threats(null), threats(2)).orElseThrow().compared()).isFalse();
        // 실행+확인: 이번 0건이면 플래그가 없어야 한다
        assertThat(FlagRules.threat(null, threats(0))).isEmpty();
        // 실행+확인: 이번 건수를 못 읽었으면 플래그가 없어야 한다
        assertThat(FlagRules.threat(threats(0), threats(null))).isEmpty();
        // 실행+확인: 점검 항목이 없는 옛 진단도 플래그가 없어야 한다
        assertThat(FlagRules.threat(null, run(n -> { }))).isEmpty();
    }
}

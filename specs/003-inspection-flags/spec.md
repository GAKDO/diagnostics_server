# DI2 점검 플래그(부품 변경·용량 급감·벤치 하락)

백로그: DI2 · 요구사항: FR-DIA-06 · 계약: contracts `diagnostics-server.yaml` InspectionFlag, `trace.diagnostics.recorded` flags(변경 없음, detail 형식은 DI2가 정함)

## 규칙 flag-rules-v1

같은 렌탈의 바로 앞 진단(baseRunId)과 이번 진단을 비교한다. 첫 진단(출고)은 기준이 없어 빈 배열이다.

| 종류 | 붙는 조건 | detail |
| --- | --- | --- |
| COMPONENT_CHANGED | 부품 종류마다 "식별자#시리얼" 묶음이 다름(한쪽에서 그 종류를 못 읽었으면 넘김) | rule, componentKind, before[], after[] |
| CAPACITY_DROP | 서버 계산 최대 용량 %가 5%p 이상 떨어짐 | rule, beforePct, afterPct, dropPct, thresholdPct, before/afterCycleCount |
| BENCH_DROP | 지표 중앙값이 15% 이상 떨어짐(모든 지표가 클수록 좋음) | rule, metric, before, after, dropPct, thresholdPct |

임계값은 설계서대로 파일럿 1차 측정 편차로 다시 정한다. 지금 값은 agent 3회 측정 편차(수 %)보다 넉넉히 둔 출발값이다.

## 수용 기준과 테스트

- 같은 기기 재진단 → 플래그 없음 (FlagRulesTest.sameDeviceNoFlags)
- SSD 시리얼 바뀜 → COMPONENT_CHANGED 하나 (componentChanged)
- 용량 5%p 이상 하락만 CAPACITY_DROP (capacityDrop)
- 15% 이상 떨어진 지표만 BENCH_DROP (benchDrop)
- 생략한 항목은 비교하지 않음 (skippedValuesAreNotCompared)
- 플래그는 inspection_flag에 저장되고 업로드 응답·조회 flags·trace.diagnostics.recorded flags에 같이 실린다

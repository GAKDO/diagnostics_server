#!/usr/bin/env bash
# contracts 사본(진단 JSON Schema·코드 값 생성본)을 GAKDO/contracts 체크아웃에서 갱신하거나(--check면) 다른지 확인한다.
# 사용: scripts/sync-contracts.sh [--check] [contracts 경로(기본 ../contracts)]
# 서버는 실행 중에 contracts를 받으러 가지 않는다. contracts가 바뀌면 이 스크립트로 사본을 맞추고 테스트를 돌린다.
set -euo pipefail

check=false
if [[ "${1:-}" == "--check" ]]; then
  check=true
  shift
fi
src="${1:-$(dirname "$0")/../../contracts}"
root="$(dirname "$0")/.."
# 원본 경로 → 사본 경로. 스키마는 실행에 쓰고(main), 예시·이벤트 스키마는 테스트에만 쓴다(test)
declare -A copies=(
  [diagnostics/result.schema.json]=src/main/resources/contracts/diagnostics/result.schema.json
  [schemas/codes.gen.schema.json]=src/main/resources/contracts/schemas/codes.gen.schema.json
  [events/envelope.schema.json]=src/test/resources/contracts/events/envelope.schema.json
  [events/trace.diagnostics.recorded.schema.json]=src/test/resources/contracts/events/trace.diagnostics.recorded.schema.json
)
for f in "$src"/examples/diagnostics/valid/*.json "$src"/examples/diagnostics/invalid/*.json; do
  rel="${f#"$src"/}"
  copies[$rel]="src/test/resources/contracts/$rel"
done

for f in "${!copies[@]}"; do
  out="$root/${copies[$f]}"
  if [[ ! -f "$src/$f" ]]; then
    echo "contracts에 $f 가 없습니다: $src" >&2
    exit 2
  fi
  if $check; then
    if ! diff -q "$src/$f" "$out" >/dev/null 2>&1; then
      echo "다름: $f (scripts/sync-contracts.sh 로 갱신)" >&2
      exit 1
    fi
  else
    mkdir -p "$(dirname "$out")"
    cp "$src/$f" "$out"
    echo "갱신: $f"
  fi
done
$check && echo "contracts 사본이 같습니다."

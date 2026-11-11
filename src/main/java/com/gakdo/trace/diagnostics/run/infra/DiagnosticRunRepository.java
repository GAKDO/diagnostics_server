package com.gakdo.trace.diagnostics.run.infra;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;

public interface DiagnosticRunRepository extends JpaRepository<DiagnosticRun, Long> {

    /**
     * 같은 렌탈의 업로드를 트랜잭션 동안 직렬화한다(PostgreSQL advisory lock, 트랜잭션이 끝나면 풀림).
     * 서로 다른 시점의 첫 업로드가 동시에 와서 둘 다 "앞선 진단 없음"으로 읽고 시리얼 대조를 건너뛰지 않게 한다.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:rentalId))", nativeQuery = true)
    Integer lockRental(@Param("rentalId") String rentalId);

    Optional<DiagnosticRun> findByCheckpointId(String checkpointId);

    /** 공개 기기 목록(11/8): 여러 시점의 진단을 한 번에. 공개 응답에 쓰는 배터리·SSD·벤치마크만 함께 읽는다(부품은 읽지 않음). */
    @EntityGraph(attributePaths = {"battery", "ssd", "benchmarks"})
    List<DiagnosticRun> findByCheckpointIdIn(Collection<String> checkpointIds);

    /** 같은 렌탈의 가장 최근 실행(새로 올릴 때의 비교 기준). */
    Optional<DiagnosticRun> findFirstByRentalIdOrderByIdDesc(String rentalId);

    /** 같은 렌탈에서 이 실행 바로 앞의 실행(조회의 previous). id는 저장 순서다. */
    Optional<DiagnosticRun> findFirstByRentalIdAndIdLessThanOrderByIdDesc(String rentalId, Long id);
}

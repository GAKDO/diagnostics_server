package com.gakdo.trace.diagnostics.run.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;

public interface DiagnosticRunRepository extends JpaRepository<DiagnosticRun, Long> {

    Optional<DiagnosticRun> findByCheckpointId(String checkpointId);

    /** 같은 렌탈의 가장 최근 실행(새로 올릴 때의 비교 기준). */
    Optional<DiagnosticRun> findFirstByRentalIdOrderByIdDesc(String rentalId);

    /** 같은 렌탈에서 이 실행 바로 앞의 실행(조회의 previous). id는 저장 순서다. */
    Optional<DiagnosticRun> findFirstByRentalIdAndIdLessThanOrderByIdDesc(String rentalId, Long id);
}

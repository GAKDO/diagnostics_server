package com.gakdo.trace.diagnostics.run.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.diagnostics.run.domain.InspectionFlag;

public interface InspectionFlagRepository extends JpaRepository<InspectionFlag, Long> {

    List<InspectionFlag> findByRunIdOrderById(Long runId);
}

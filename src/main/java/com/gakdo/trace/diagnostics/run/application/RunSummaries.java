package com.gakdo.trace.diagnostics.run.application;

import java.util.List;

import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.BatterySummary;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.BenchmarkSummary;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.ComponentSummary;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.Flag;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.RunSummary;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.SsdSummary;
import com.gakdo.trace.diagnostics.run.domain.BatteryReading;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;
import com.gakdo.trace.diagnostics.run.domain.InspectionFlag;
import com.gakdo.trace.diagnostics.run.domain.SsdHealth;

/** 저장된 실행 → 리포트용 요약(배터리·SSD·부품·벤치마크). 트랜잭션 안에서 부른다(지연 로딩). */
final class RunSummaries {

    private RunSummaries() {
    }

    static RunSummary of(DiagnosticRun run) {
        return new RunSummary(run.publicId(), run.collectedAt(), run.os(), battery(run.battery()), ssd(run.ssd()),
                run.components().stream().map(c -> new ComponentSummary(c.kind(), c.identifier())).toList(),
                run.benchmarks().stream().map(b -> new BenchmarkSummary(b.metric(), b.median())).toList());
    }

    static List<Flag> flags(List<InspectionFlag> flags) {
        return flags.stream().map(f -> new Flag(f.kind(), f.baseRun().publicId(), f.detail())).toList();
    }

    private static BatterySummary battery(BatteryReading b) {
        if (b == null || BatteryReading.NO_BATTERY.equals(b.status())) {
            return null;
        }
        return new BatterySummary(b.cycleCount(), b.designCapacity(), b.fullChargeCapacity(), b.capacityUnit(),
                b.maxCapacityPct() == null ? null : b.maxCapacityPct().doubleValue(), b.condition());
    }

    private static SsdSummary ssd(SsdHealth s) {
        if (s == null) {
            return null;
        }
        return new SsdSummary(s.percentUsed(), s.availableSparePct(), s.criticalWarning(), s.powerOnHours(),
                s.mediaErrors(), s.skippedReason());
    }
}

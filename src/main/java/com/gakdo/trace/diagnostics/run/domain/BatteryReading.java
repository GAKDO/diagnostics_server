package com.gakdo.trace.diagnostics.run.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 배터리(FR-DIA-02). 용량은 받은 단위 그대로(환산 없음), 효율 %는 서버가 계산한다.
 * 읽지 못했으면(SKIPPED) 값은 모두 null이고 사유를 남긴다. 배터리가 없는 기기는 NO_BATTERY.
 */
@Entity
@Table(name = "battery_reading")
public class BatteryReading {

    public static final String NO_BATTERY = "NO_BATTERY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private DiagnosticRun run;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "skipped_reason", length = 16)
    private String skippedReason;

    @Column(name = "cycle_count")
    private Integer cycleCount;

    @Column(name = "design_capacity")
    private Integer designCapacity;

    @Column(name = "full_charge_capacity")
    private Integer fullChargeCapacity;

    @Column(name = "capacity_unit", length = 3)
    private String capacityUnit;

    @Column(name = "max_capacity_pct", precision = 5, scale = 2)
    private BigDecimal maxCapacityPct;

    @Column(name = "os_max_capacity_pct")
    private Short osMaxCapacityPct;

    @Column(name = "condition", length = 32)
    private String condition;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BatteryReading() {
    }

    BatteryReading(DiagnosticRun run, DiagnosticUpload.Battery battery, OffsetDateTime now) {
        this.run = run;
        this.status = battery.status();
        this.skippedReason = battery.skippedReason();
        this.cycleCount = battery.cycleCount();
        if (battery.designCapacity() != null) {
            this.designCapacity = battery.designCapacity().value();
            this.capacityUnit = battery.designCapacity().unit();
        }
        if (battery.fullChargeCapacity() != null) {
            this.fullChargeCapacity = battery.fullChargeCapacity().value();
            this.capacityUnit = battery.fullChargeCapacity().unit();
        }
        if (battery.designCapacity() != null && battery.fullChargeCapacity() != null) {
            this.maxCapacityPct = UploadRules.maxCapacityPct(battery);
        }
        this.osMaxCapacityPct = battery.osMaxCapacityPercent() == null ? null
                : battery.osMaxCapacityPercent().shortValue();
        this.condition = battery.osCondition();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String status() {
        return status;
    }

    public String skippedReason() {
        return skippedReason;
    }

    public Integer cycleCount() {
        return cycleCount;
    }

    public Integer designCapacity() {
        return designCapacity;
    }

    public Integer fullChargeCapacity() {
        return fullChargeCapacity;
    }

    public String capacityUnit() {
        return capacityUnit;
    }

    public BigDecimal maxCapacityPct() {
        return maxCapacityPct;
    }

    public String condition() {
        return condition;
    }
}

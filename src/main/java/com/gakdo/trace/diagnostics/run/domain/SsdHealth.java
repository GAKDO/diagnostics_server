package com.gakdo.trace.diagnostics.run.domain;

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

/** SSD 상태(FR-DIA-03). 사용률 하나로 판단하지 않도록 값을 따로 둔다. 권한이 없으면 SKIPPED + 사유, 값은 null. */
@Entity
@Table(name = "ssd_health")
public class SsdHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private DiagnosticRun run;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "protocol", length = 8)
    private String protocol;

    @Column(name = "percent_used")
    private Short percentUsed;

    @Column(name = "available_spare_pct")
    private Short availableSparePct;

    @Column(name = "critical_warning")
    private Short criticalWarning;

    @Column(name = "power_on_hours")
    private Integer powerOnHours;

    @Column(name = "media_errors")
    private Integer mediaErrors;

    @Column(name = "skipped_reason", length = 128)
    private String skippedReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SsdHealth() {
    }

    SsdHealth(DiagnosticRun run, DiagnosticUpload.Storage storage, OffsetDateTime now) {
        this.run = run;
        this.status = storage.status();
        this.protocol = storage.protocol();
        this.percentUsed = toShort(storage.percentageUsed());
        this.availableSparePct = toShort(storage.availableSpare());
        this.criticalWarning = toShort(storage.criticalWarning());
        this.powerOnHours = toInt(storage.powerOnHours()); // UploadRules가 int 범위를 확인했다
        this.mediaErrors = toInt(storage.mediaErrors());
        this.skippedReason = storage.skippedReason();
        this.createdAt = now;
        this.updatedAt = now;
    }

    private static Short toShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    private static Integer toInt(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }

    public String status() {
        return status;
    }

    public Integer percentUsed() {
        return percentUsed == null ? null : percentUsed.intValue();
    }

    public Integer availableSparePct() {
        return availableSparePct == null ? null : availableSparePct.intValue();
    }

    public Integer criticalWarning() {
        return criticalWarning == null ? null : criticalWarning.intValue();
    }

    public Integer powerOnHours() {
        return powerOnHours;
    }

    public Integer mediaErrors() {
        return mediaErrors;
    }

    public String skippedReason() {
        return skippedReason;
    }
}

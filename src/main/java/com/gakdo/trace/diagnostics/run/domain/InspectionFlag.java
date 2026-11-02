package com.gakdo.trace.diagnostics.run.domain;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 점검 플래그(FR-DIA-06). DI1은 읽기만 하고, 비교해 만드는 것은 DI2다. */
@Entity
@Table(name = "inspection_flag")
public class InspectionFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checkpoint_id", nullable = false, length = 21)
    private String checkpointId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private DiagnosticRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "base_run_id", nullable = false)
    private DiagnosticRun baseRun;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false)
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected InspectionFlag() {
    }

    public InspectionFlag(DiagnosticRun run, DiagnosticRun baseRun, String kind, Map<String, Object> detail,
            OffsetDateTime now) {
        this.checkpointId = run.checkpointId();
        this.run = run;
        this.baseRun = baseRun;
        this.kind = kind;
        this.detail = detail;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String kind() {
        return kind;
    }

    public DiagnosticRun baseRun() {
        return baseRun;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}

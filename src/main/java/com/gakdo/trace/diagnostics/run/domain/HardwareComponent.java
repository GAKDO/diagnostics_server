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

/** 부품 구성(FR-DIA-01). 시점 사이 부품 변경(COMPONENT_CHANGED, DI2) 비교에 쓴다. */
@Entity
@Table(name = "hardware_component")
public class HardwareComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private DiagnosticRun run;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "identifier", nullable = false, length = 128)
    private String identifier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected HardwareComponent() {
    }

    HardwareComponent(DiagnosticRun run, DiagnosticUpload.Component component, OffsetDateTime now) {
        this.run = run;
        this.kind = component.kind();
        this.identifier = component.identifier();
        this.detail = component.detail();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String kind() {
        return kind;
    }

    public String identifier() {
        return identifier;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}

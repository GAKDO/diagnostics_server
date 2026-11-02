package com.gakdo.trace.diagnostics.run.domain;

import java.time.OffsetDateTime;
import java.util.List;

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

/** 고정 벤치마크 3회와 중앙값(FR-DIA-04). 실행 안에서 지표마다 하나. */
@Entity
@Table(name = "benchmark_result")
public class BenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private DiagnosticRun run;

    @Column(name = "metric", nullable = false, length = 16)
    private String metric;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trial_values", nullable = false)
    private List<Double> trialValues;

    @Column(name = "median", nullable = false)
    private double median;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BenchmarkResult() {
    }

    BenchmarkResult(DiagnosticRun run, DiagnosticUpload.Benchmark benchmark, OffsetDateTime now) {
        this.run = run;
        this.metric = benchmark.metric();
        this.trialValues = List.copyOf(benchmark.trials());
        this.median = benchmark.median();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String metric() {
        return metric;
    }

    public List<Double> trialValues() {
        return trialValues;
    }

    public double median() {
        return median;
    }
}

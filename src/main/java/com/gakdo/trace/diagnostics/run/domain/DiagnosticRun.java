package com.gakdo.trace.diagnostics.run.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 진단 실행 하나(diagnostics.diagnostic_run). 시점당 하나이고 고치지 않는다(헌법 III: 덧붙이기만).
 * 부품·배터리·SSD·벤치마크는 같은 트랜잭션에 함께 저장된다.
 */
@Entity
@Table(name = "diagnostic_run")
public class DiagnosticRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 21)
    private String publicId;

    @Column(name = "checkpoint_id", nullable = false, length = 21)
    private String checkpointId;

    @Column(name = "rental_id", nullable = false, length = 21)
    private String rentalId;

    @Column(name = "device_serial", nullable = false, length = 64)
    private String deviceSerial;

    @Column(name = "os", nullable = false, length = 8)
    private String os;

    @Column(name = "agent_version", nullable = false, length = 32)
    private String agentVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false)
    private Map<String, Object> conditions;

    @Column(name = "raw_object_key", length = 512)
    private String rawObjectKey;

    @Column(name = "raw_body", nullable = false, columnDefinition = "text")
    private String rawBody;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "benchmark_skipped_reason", length = 16)
    private String benchmarkSkippedReason;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<HardwareComponent> components = new ArrayList<>();

    @OneToMany(mappedBy = "run", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<BenchmarkResult> benchmarks = new ArrayList<>();

    @OneToOne(mappedBy = "run", cascade = CascadeType.PERSIST)
    private BatteryReading battery;

    @OneToOne(mappedBy = "run", cascade = CascadeType.PERSIST)
    private SsdHealth ssd;

    protected DiagnosticRun() {
    }

    /** 저장할 원본 본문과 그 바이트의 SHA-256(소문자 hex). */
    public record RawBody(String text, String sha256) {
    }

    /** 검증을 마친 본문으로 실행을 만든다. 시점·렌탈은 토큰에서, 해시는 원본 바이트에서 온다. */
    public static DiagnosticRun of(String publicId, String checkpointId, String rentalId, String idempotencyKey,
            DiagnosticUpload upload, RawBody raw, OffsetDateTime now) {
        DiagnosticRun run = new DiagnosticRun();
        run.publicId = publicId;
        run.checkpointId = checkpointId;
        run.rentalId = rentalId;
        run.idempotencyKey = idempotencyKey;
        run.deviceSerial = upload.device().serial();
        run.os = upload.os().type();
        run.agentVersion = upload.agentVersion();
        run.conditions = upload.conditions();
        run.rawBody = raw.text();
        run.sha256 = raw.sha256();
        run.benchmarkSkippedReason = upload.benchmarkSkippedReason();
        run.collectedAt = upload.collectedAt();
        run.consentedAt = upload.consentedAt();
        run.createdAt = now;
        run.updatedAt = now;
        upload.components().forEach(c -> run.components.add(new HardwareComponent(run, c, now)));
        upload.benchmarks().forEach(b -> run.benchmarks.add(new BenchmarkResult(run, b, now)));
        run.battery = new BatteryReading(run, upload.battery(), now);
        run.ssd = new SsdHealth(run, upload.storage(), now);
        return run;
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public String checkpointId() {
        return checkpointId;
    }

    public String rentalId() {
        return rentalId;
    }

    public String deviceSerial() {
        return deviceSerial;
    }

    public String os() {
        return os;
    }

    public String rawBody() {
        return rawBody;
    }

    public String sha256() {
        return sha256;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String benchmarkSkippedReason() {
        return benchmarkSkippedReason;
    }

    public OffsetDateTime collectedAt() {
        return collectedAt;
    }

    public List<HardwareComponent> components() {
        return List.copyOf(components);
    }

    public List<BenchmarkResult> benchmarks() {
        return List.copyOf(benchmarks);
    }

    public BatteryReading battery() {
        return battery;
    }

    public SsdHealth ssd() {
        return ssd;
    }
}

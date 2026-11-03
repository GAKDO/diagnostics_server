package com.gakdo.trace.diagnostics.run.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.common.domain.DomainException;
import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;
import com.gakdo.trace.diagnostics.common.domain.ErrorCode;
import com.gakdo.trace.diagnostics.common.infra.Caller;
import com.gakdo.trace.diagnostics.common.infra.Hashes;
import com.gakdo.trace.diagnostics.common.infra.PublicIds;
import com.gakdo.trace.diagnostics.common.infra.Times;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.CheckpointDiagnostics;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.RunCreated;
import com.gakdo.trace.diagnostics.run.domain.BatteryReading;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticUpload;
import com.gakdo.trace.diagnostics.run.domain.UploadRules;
import com.gakdo.trace.diagnostics.run.infra.DiagnosticRunRepository;
import com.gakdo.trace.diagnostics.run.infra.InspectionFlagRepository;

/**
 * 진단 업로드(FR-DIA-01~05 서버 쪽)와 시점 진단 조회(FR-DIA-06 틀, 리포트의 배터리·SSD 표시).
 * 순서: 범위 → JSON 파싱(400) → JSON Schema(422) → 서버 규칙(422) → 시점 중복·멱등 → 시리얼 대조 → 저장 + 이벤트.
 */
@Service
public class DiagnosticRunService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticRunService.class);

    private final DiagnosticRunRepository runs;
    private final InspectionFlagRepository flags;
    private final DiagnosticSchema schema;
    private final DiagnosticsEventPublisher events;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DiagnosticRunService(DiagnosticRunRepository runs, InspectionFlagRepository flags, DiagnosticSchema schema,
            DiagnosticsEventPublisher events, ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.runs = runs;
        this.flags = flags;
        this.schema = schema;
        this.events = events;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 진단 JSON 하나를 시점에 붙여 저장한다. 같은 키·같은 본문이면 처음 응답을 그대로 돌려준다.
     * 트랜잭션은 이 안에서 연다: 동시에 같은 시점이 와서 유일 제약에 걸리면 새 트랜잭션으로 다시 보고 답한다.
     */
    public RunCreated upload(Caller caller, String idempotencyKey, byte[] body) {
        caller.requireAgent();
        DiagnosticUpload upload = parse(body);
        String sha256 = Hashes.sha256Hex(body);
        String rawBody = new String(body, StandardCharsets.UTF_8);
        try {
            return tx.execute(s -> store(caller, idempotencyKey, upload, rawBody, sha256));
        } catch (DataIntegrityViolationException race) {
            log.info("같은 시점 진단이 동시에 와서 먼저 저장된 것으로 답합니다: checkpoint={}", caller.scope());
            return tx.execute(s -> runs.findByCheckpointId(caller.scope())
                    .map(existing -> replayOrReject(existing, idempotencyKey, sha256))
                    .orElseThrow(() -> race));
        }
    }

    /** 시점의 진단 요약, 같은 렌탈 직전 시점 요약, 점검 플래그. */
    @Transactional(readOnly = true)
    public CheckpointDiagnostics checkpoint(Caller caller, String checkpointId) {
        DiagnosticRun run = runs.findByCheckpointId(checkpointId)
                .orElseThrow(() -> DomainException.notFound("이 시점의 진단"));
        caller.requireCanRead(run.rentalId());
        Optional<DiagnosticRun> previous = runs.findFirstByRentalIdAndIdLessThanOrderByIdDesc(run.rentalId(), run.id());
        return new CheckpointDiagnostics(checkpointId, RunSummaries.of(run),
                previous.map(RunSummaries::of).orElse(null),
                RunSummaries.flags(flags.findByRunIdOrderById(run.id())));
    }

    private DiagnosticUpload parse(byte[] body) {
        JsonNode document;
        try {
            document = objectMapper.readTree(body);
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "요청 본문이 JSON이 아닙니다.");
        }
        if (document == null || document.isMissingNode()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "요청 본문이 비었습니다.");
        }
        List<FieldError> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.DIAGNOSTIC_SCHEMA_INVALID, null, errors);
        }
        DiagnosticUpload upload;
        try {
            upload = objectMapper.treeToValue(document, DiagnosticUpload.class);
        } catch (JsonProcessingException e) {
            // 스키마를 통과했는데 바꾸지 못하면 사본과 record가 어긋난 것이다(422로 알리고 로그를 남긴다)
            log.warn("스키마를 통과한 진단 JSON을 읽지 못했습니다: {}", e.getOriginalMessage());
            throw new DomainException(ErrorCode.DIAGNOSTIC_SCHEMA_INVALID, "진단 결과를 읽을 수 없습니다.");
        }
        List<FieldError> ruleErrors = UploadRules.check(upload);
        if (!ruleErrors.isEmpty()) {
            throw new DomainException(ErrorCode.DIAGNOSTIC_SCHEMA_INVALID, null, ruleErrors);
        }
        return upload;
    }

    private RunCreated store(Caller caller, String idempotencyKey, DiagnosticUpload upload, String rawBody,
            String sha256) {
        String checkpointId = caller.scope();
        String rentalId = caller.rentalId();
        runs.lockRental(rentalId); // 같은 렌탈 업로드 직렬화: 앞선 진단 조회·시리얼 대조·저장이 한 번에 하나씩
        Optional<DiagnosticRun> existing = runs.findByCheckpointId(checkpointId);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), idempotencyKey, sha256);
        }
        // 기기 시리얼은 관리자가 페어링 코드를 만들 때 정한 등록 시리얼(AGENT JWT deviceSerial 클레임)과 대조한다.
        // 재전송(위)은 처음 요청이 이미 통과했으므로 먼저 판단하고, 새 저장만 여기서 막는다(contracts auth-server 0.2.0)
        if (!caller.deviceSerial().equals(upload.device().serial())) {
            throw new DomainException(ErrorCode.DEVICE_SERIAL_MISMATCH, "진단한 기기의 시리얼이 대여 기기와 다릅니다.");
        }
        Optional<DiagnosticRun> base = runs.findFirstByRentalIdOrderByIdDesc(rentalId);
        OffsetDateTime now = Times.now();
        DiagnosticRun run = runs.saveAndFlush(DiagnosticRun.of(PublicIds.next(), checkpointId, rentalId,
                idempotencyKey, upload, new DiagnosticRun.RawBody(rawBody, sha256), now));
        // 플래그 계산(부품 변경·용량 급감·벤치 하락)은 DI2. DI1은 비교 기준만 싣는다
        events.publishRecorded(new DiagnosticsRecordedEvent(DiagnosticsRecordedEvent.SCHEMA_VERSION,
                PublicIds.next(), now, rentalId, checkpointId, run.publicId(), sha256,
                base.map(DiagnosticRun::publicId).orElse(null), List.of(), maxCapacityPct(run)));
        return new RunCreated(run.publicId(), List.of());
    }

    /** 리포트용 배터리 최대 용량(서버 계산 값). 배터리가 없거나 용량을 못 읽었으면 null. */
    static Double maxCapacityPct(DiagnosticRun run) {
        BatteryReading b = run.battery();
        if (b == null || BatteryReading.NO_BATTERY.equals(b.status())) {
            return null;
        }
        return forEvent(b.maxCapacityPct());
    }

    /** 이벤트 상한(0~200%). 업로드는 999.99까지 받지만 200%를 넘는 값은 잘못 읽은 것이라 이벤트에는 null로 싣는다. */
    static final BigDecimal EVENT_MAX_CAPACITY_PCT = new BigDecimal("200");

    static Double forEvent(BigDecimal pct) {
        if (pct == null || pct.signum() < 0 || pct.compareTo(EVENT_MAX_CAPACITY_PCT) > 0) {
            return null;
        }
        return pct.doubleValue();
    }

    /** 같은 키·같은 본문이면 처음 응답, 같은 키·다른 본문이면 422, 다른 키면 409. */
    private RunCreated replayOrReject(DiagnosticRun existing, String idempotencyKey, String sha256) {
        if (!existing.idempotencyKey().equals(idempotencyKey)) {
            throw new DomainException(ErrorCode.DIAGNOSTIC_ALREADY_UPLOADED, null);
        }
        if (!existing.sha256().equals(sha256)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, null);
        }
        return new RunCreated(existing.publicId(), RunSummaries.flags(flags.findByRunIdOrderById(existing.id())));
    }
}

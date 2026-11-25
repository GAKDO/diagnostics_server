package com.gakdo.trace.diagnostics.run.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.diagnostics.common.infra.Caller;
import com.gakdo.trace.diagnostics.common.infra.PublicIds;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.CatalogDiagnostics;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.CheckpointDiagnostics;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.RunCreated;
import com.gakdo.trace.diagnostics.run.application.DiagnosticResults.RunRedone;
import com.gakdo.trace.diagnostics.run.application.DiagnosticRunService;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** contracts openapi/diagnostics-server.yaml: 진단 업로드(AGENT)와 시점 진단 조회(ADMIN·CUSTOMER). */
@RestController
@Validated
public class DiagnosticRunController {

    private final DiagnosticRunService service;

    public DiagnosticRunController(DiagnosticRunService service) {
        this.service = service;
    }

    /** 본문은 바이트 그대로 받는다: 원본 SHA-256(해시 체인 재료)을 다시 직렬화한 값이 아니라 받은 그대로로 계산한다. */
    @PostMapping(path = "/api/v1/diagnostics/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunCreated> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @RequestBody byte[] body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(Caller.from(jwt), idempotencyKey, body));
    }

    public record CatalogDiagnosticsList(List<CatalogDiagnostics> items) {
    }

    /** 공개 보유 기기 목록의 진단(로그인 없음, 11/8). checkpointIds는 쉼표로 이은 값이고 Spring이 목록으로 나눈다. */
    @GetMapping(path = "/api/v1/diagnostics/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public CatalogDiagnosticsList catalog(
            @RequestParam @Size(min = 1, max = 100) List<@Pattern(regexp = PublicIds.PATTERN) String> checkpointIds) {
        return new CatalogDiagnosticsList(service.catalog(checkpointIds));
    }

    @GetMapping(path = "/api/v1/diagnostics/checkpoints/{checkpointId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CheckpointDiagnostics checkpoint(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return service.checkpoint(Caller.from(jwt), checkpointId);
    }

    /** 진단 다시 하기(11/13, ADMIN): 봉인 전 시점의 지금 진단을 이전 진단으로 돌린다. 지우지 않는다. */
    @PostMapping(path = "/api/v1/diagnostics/checkpoints/{checkpointId}/redo", produces = MediaType.APPLICATION_JSON_VALUE)
    public RunRedone redo(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return service.redo(Caller.from(jwt), checkpointId);
    }
}

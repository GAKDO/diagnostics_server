package com.gakdo.trace.diagnostics.run.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * trace.diagnostics.recorded (contracts events/trace.diagnostics.recorded.schema.json 1.0).
 * report-server가 해시 체인에 sha256을 넣고 플래그를 타임라인에 보인다.
 */
public record DiagnosticsRecordedEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String runId,
        String sha256,
        String baseRunId,
        List<Flag> flags) {

    public static final String ROUTING_KEY = "trace.diagnostics.recorded";
    public static final String SCHEMA_VERSION = "1.0";

    public record Flag(String kind, Map<String, Object> detail) {
    }
}

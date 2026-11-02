package com.gakdo.trace.diagnostics.run.infra;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;
import com.gakdo.trace.diagnostics.run.application.DiagnosticSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * 레포에 둔 contracts 사본(src/main/resources/contracts, scripts/sync-contracts.sh)으로 검증한다.
 * 실행 중에 원격 스키마를 받으러 가지 않도록 contracts의 $id 주소를 클래스패스로 돌린다.
 */
@Component
public class NetworkntDiagnosticSchema implements DiagnosticSchema {

    static final String LOCATION = "classpath:contracts/diagnostics/result.schema.json";
    private static final String CONTRACTS_URL = "https://raw.githubusercontent.com/GAKDO/contracts/main/";
    /** 오류가 수백 개여도 응답은 앞의 몇 개만(에이전트 화면이 고칠 곳을 찾기에 충분) */
    private static final int MAX_ERRORS = 20;

    private final JsonSchema schema;

    public NetworkntDiagnosticSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(m -> m.mapPrefix(CONTRACTS_URL, "classpath:contracts/")));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .pathType(PathType.LEGACY)
                .formatAssertionsEnabled(true)
                .locale(Locale.KOREAN)
                .build();
        this.schema = factory.getSchema(SchemaLocation.of(LOCATION), config);
        this.schema.initializeValidators(); // $ref를 시작할 때 모두 풀어 잘못된 사본이면 기동에서 실패하게 한다
    }

    @Override
    public List<FieldError> validate(JsonNode document) {
        Set<ValidationMessage> messages = schema.validate(document);
        Set<FieldError> errors = new LinkedHashSet<>();
        for (ValidationMessage m : messages) {
            errors.add(new FieldError(field(m), message(m)));
            if (errors.size() >= MAX_ERRORS) {
                break;
            }
        }
        return new ArrayList<>(errors);
    }

    /** 메시지 앞의 "$.경로: "는 field와 겹치므로 뺀다. */
    static String message(ValidationMessage m) {
        String text = m.getMessage();
        int colon = text.indexOf(": ");
        return text.startsWith("$") && colon > 0 ? text.substring(colon + 2) : text;
    }

    /** "$.battery.cycleCount" → "battery.cycleCount". 빠진·남는 키는 그 키 이름까지 붙인다. */
    static String field(ValidationMessage m) {
        String path = m.getInstanceLocation().toString();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if ("$".equals(path)) {
            path = "";
        }
        String property = m.getProperty();
        String type = m.getType();
        if (property != null && ("required".equals(type) || "additionalProperties".equals(type)
                || "unevaluatedProperties".equals(type))) {
            path = path.isEmpty() ? property : path + "." + property;
        }
        return path.isEmpty() ? "$" : path;
    }
}

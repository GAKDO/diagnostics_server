// 테스트 도구(테스트 자체는 아님): 진단 서버가 "진단이 기록됨(trace.diagnostics.recorded)" 이벤트를 낼 때,
// 그 내용이 팀이 약속한 형식(contracts 레포의 이벤트 스키마 사본)에 맞는지 검사해 준다.

package com.gakdo.trace.diagnostics.support;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** contracts 이벤트 스키마 사본(src/test/resources/contracts/events)으로 낸 이벤트를 검증한다. */
public final class EventSchemas {

    private static final JsonSchema RECORDED = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012, b -> b.schemaMappers(
                    m -> m.mapPrefix("https://raw.githubusercontent.com/GAKDO/contracts/main/", "classpath:contracts/")))
            .getSchema(SchemaLocation.of("classpath:contracts/events/trace.diagnostics.recorded.schema.json"));

    private EventSchemas() {
    }

    public static Set<ValidationMessage> validateRecorded(JsonNode event) {
        return RECORDED.validate(event);
    }
}

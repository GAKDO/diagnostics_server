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

    // (도구) JsonSchema = JSON이 약속한 모양(칸 이름·형식·범위)에 맞는지 검사하는 규칙 묶음
    // "진단이 기록됨" 이벤트 스키마를 읽어 둔다. 인터넷 주소(contracts main) 대신 테스트 폴더의 사본을 쓰도록 바꿔 연결한다
    private static final JsonSchema RECORDED = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012, b -> b.schemaMappers(
                    m -> m.mapPrefix("https://raw.githubusercontent.com/GAKDO/contracts/main/", "classpath:contracts/")))
            .getSchema(SchemaLocation.of("classpath:contracts/events/trace.diagnostics.recorded.schema.json"));

    private EventSchemas() {
    }

    // 테스트 도구: 이벤트 JSON을 검사해 틀린 점 목록을 돌려준다(비어 있으면 통과)
    public static Set<ValidationMessage> validateRecorded(JsonNode event) {
        // 스키마로 검사한 결과를 돌려준다
        return RECORDED.validate(event);
    }
}

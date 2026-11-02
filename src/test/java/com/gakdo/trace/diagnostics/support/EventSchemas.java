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

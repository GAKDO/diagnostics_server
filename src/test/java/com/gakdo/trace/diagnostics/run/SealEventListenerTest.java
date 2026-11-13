package com.gakdo.trace.diagnostics.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.run.application.SealedCheckpointStore;
import com.gakdo.trace.diagnostics.run.infra.SealEventListener;

/** trace.record.sealed 소비(11/13 진단 다시 하기 잠금). Docker 없이 돈다. */
class SealEventListenerTest {

    private static final String CP = "h40b9AN9xMQhXRHrOyJWB";

    private final SealedCheckpointStore store = mock(SealedCheckpointStore.class);
    private final SealEventListener listener = new SealEventListener(store, new ObjectMapper());

    private static byte[] sealed(String checkpointId, String signedAt) {
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-13T11:00:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + checkpointId + "\",\"seq\":1,"
                + "\"checkpointType\":\"OUT\",\"grade\":\"A\",\"payloadHash\":\"" + "a".repeat(64) + "\",\"prevHash\":null"
                + (signedAt == null ? "" : ",\"signedAt\":\"" + signedAt + "\"") + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("봉인 이벤트를 받으면 그 시점을 봉인으로 기록하고 시각은 signedAt을 쓴다")
    void recordsSealedCheckpoint() {
        when(store.markSealed(eq(CP), any())).thenReturn(true);
        assertThat(listener.handle(sealed(CP, "2026-11-13T11:00:05+09:00"))).isTrue();
        verify(store).markSealed(CP, OffsetDateTime.parse("2026-11-13T11:00:05+09:00"));
    }

    @Test
    @DisplayName("signedAt이 없으면 봉투의 occurredAt을 봉인 시각으로 쓴다")
    void fallsBackToOccurredAt() {
        listener.handle(sealed(CP, null));
        verify(store).markSealed(CP, OffsetDateTime.parse("2026-11-13T11:00:00+09:00"));
    }

    @Test
    @DisplayName("JSON이 아니거나 checkpointId가 NanoID가 아니면 기록하지 않고 DLX로 보낸다")
    void malformedGoesToDlx() {
        assertThatThrownBy(() -> listener.handle("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle(sealed("bad", null)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).markSealed(anyString(), any());
    }
}

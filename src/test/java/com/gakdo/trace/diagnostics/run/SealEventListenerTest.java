// 이 파일이 확인하는 것: 기록이 봉인되면(trace.record.sealed 이벤트) 진단 서버가 그 시점을 "봉인됨"으로 적어 두는 부분.
// 봉인된 시점은 관리자가 "진단 다시 하기"를 할 수 없게 막는 데 쓰인다(11/13 추가). Docker 없이 돈다.

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

    // [상황] 서명 시각(signedAt)이 들어 있는 봉인 이벤트가 온다.
    // [기대] 그 시점을 봉인으로 기록하고, 봉인 시각은 signedAt을 쓴다.
    @Test
    @DisplayName("봉인 이벤트를 받으면 그 시점을 봉인으로 기록하고 시각은 signedAt을 쓴다")
    void recordsSealedCheckpoint() {
        when(store.markSealed(eq(CP), any())).thenReturn(true);
        assertThat(listener.handle(sealed(CP, "2026-11-13T11:00:05+09:00"))).isTrue();
        verify(store).markSealed(CP, OffsetDateTime.parse("2026-11-13T11:00:05+09:00"));
    }

    // [상황] signedAt이 없는 봉인 이벤트가 온다.
    // [기대] 이벤트가 생긴 시각(occurredAt)을 봉인 시각으로 쓴다.
    @Test
    @DisplayName("signedAt이 없으면 봉투의 occurredAt을 봉인 시각으로 쓴다")
    void fallsBackToOccurredAt() {
        listener.handle(sealed(CP, null));
        verify(store).markSealed(CP, OffsetDateTime.parse("2026-11-13T11:00:00+09:00"));
    }

    // [상황] JSON이 아닌 메시지, 시점 ID 형식(21자 NanoID)이 틀린 메시지가 온다.
    // [기대] 기록하지 않고 DLX(처리 못 한 메시지 보관함)로 보낸다.
    @Test
    @DisplayName("JSON이 아니거나 checkpointId가 NanoID가 아니면 기록하지 않고 DLX로 보낸다")
    void malformedGoesToDlx() {
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        byte[] badId = sealed("bad", null);
        assertThatThrownBy(() -> listener.handle(notJson))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle(badId))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).markSealed(anyString(), any());
    }
}

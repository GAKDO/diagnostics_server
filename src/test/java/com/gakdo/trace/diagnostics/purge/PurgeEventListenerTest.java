// 이 파일이 확인하는 것: 렌탈 서버가 "시험 시점 지움" 이벤트를 보내면 진단 서버가 그 시점의 진단을 지우는 부분.
// 잘못된 메시지는 지우지 않고 DLX(처리 못 한 메시지를 따로 모아 두는 곳)로 보낸다.

package com.gakdo.trace.diagnostics.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.purge.application.RunPurgeStore;
import com.gakdo.trace.diagnostics.purge.application.RunPurgeService;
import com.gakdo.trace.diagnostics.purge.infra.PurgeEventListener;

class PurgeEventListenerTest {

    private static final String CP = "h40b9AN9xMQhXRHrOyJWB";

    private final RunPurgeStore store = mock(RunPurgeStore.class);
    private final PurgeEventListener listener = new PurgeEventListener(new RunPurgeService(store), new ObjectMapper());

    private static byte[] event(String serial) {
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-04T16:20:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + CP + "\",\"checkpointType\":\"OUT\","
                + "\"deviceId\":\"Nq8sUe2ZpW4cYb6Td1MfA\",\"serial\":\"" + serial + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    // [상황] 시리얼 TEST-0002인 시험 시점의 지움 이벤트가 온다.
    // [기대] 그 시점의 진단을 지운다.
    @Test
    @DisplayName("시험 시점 지움 이벤트를 받으면 그 시점의 진단을 지운다")
    void purgesTestCheckpoint() {
        when(store.deleteCheckpoint(CP)).thenReturn(1);
        assertThat(listener.handle(event("TEST-0002"))).isEqualTo(1);
        verify(store).deleteCheckpoint(CP);
    }

    // [상황] 실제 기기 시리얼(C02REAL0001) 이벤트와, 깨진 JSON 메시지가 온다.
    // [기대] 둘 다 지우지 않고 DLX로 보낸다.
    @Test
    @DisplayName("시리얼이 TEST-가 아니거나 JSON이 깨졌으면 지우지 않고 DLX로 보낸다")
    void refusesRealOrBroken() {
        byte[] real = event("C02REAL0001");
        byte[] broken = "{".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> listener.handle(real)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle(broken)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] 지우는 중에 DB 오류가 난다.
    // [기대] 같은 메시지를 큐에 다시 넣지 않고 DLX로 보낸다(같은 실패가 끝없이 반복되는 것 방지).
    @Test
    @DisplayName("DB 오류는 다시 넣지 않고 DLX로 보낸다(재전달 무한 반복 방지)")
    void dbErrorGoesToDlx() {
        when(store.deleteCheckpoint(CP)).thenThrow(new IllegalStateException("db"));
        byte[] test = event("TEST-0002");
        assertThatThrownBy(() -> listener.handle(test)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}

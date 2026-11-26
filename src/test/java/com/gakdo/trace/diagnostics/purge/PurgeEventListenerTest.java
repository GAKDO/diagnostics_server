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

    // 시험용 시점 ID
    private static final String CP = "h40b9AN9xMQhXRHrOyJWB";

    // (도구) mock(종류) = 진짜 대신 가짜 객체를 만든다. 실제로 지우지 않고 지우라고 했는지만 보려고
    // 지우기 저장소(가짜)
    private final RunPurgeStore store = mock(RunPurgeStore.class);
    // 시험할 리스너를 진짜 지우기 서비스(가짜 저장소 사용)와 JSON 읽기 도구로 만든다
    private final PurgeEventListener listener = new PurgeEventListener(new RunPurgeService(store), new ObjectMapper());

    // 테스트 도구: 그 시리얼이 적힌 "시험 시점 지움" 이벤트 JSON을 바이트로 만든다
    private static byte[] event(String serial) {
        // 이벤트 칸(렌탈·시점·종류·기기·시리얼)을 이어 붙여 UTF-8 바이트로 돌려준다
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-04T16:20:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + CP + "\",\"checkpointType\":\"OUT\","
                + "\"deviceId\":\"Nq8sUe2ZpW4cYb6Td1MfA\",\"serial\":\"" + serial + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    // [상황] 시리얼 TEST-0002인 시험 시점의 지움 이벤트가 온다.
    // [기대] 그 시점의 진단을 지운다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("시험 시점 지움 이벤트를 받으면 그 시점의 진단을 지운다")
    void purgesTestCheckpoint() {
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 그 시점을 지우면 1건 지웠다고 답하게 한다
        when(store.deleteCheckpoint(CP)).thenReturn(1);
        // (도구) assertThat(값).isEqualTo(기대값) = "값이 기대값과 같아야 한다"를 확인하는 AssertJ 도구
        // 실행+확인: TEST-0002 이벤트를 처리하면 지운 건수 1이 나와야 한다
        assertThat(listener.handle(event("TEST-0002"))).isEqualTo(1);
        // (도구) verify(가짜).메서드(값) = 그 값으로 (한 번) 불렸는지 확인
        // 확인: 그 시점 지우기를 불렀어야 한다
        verify(store).deleteCheckpoint(CP);
    }

    // [상황] 실제 기기 시리얼(C02REAL0001) 이벤트와, 깨진 JSON 메시지가 온다.
    // [기대] 둘 다 지우지 않고 DLX로 보낸다.
    @Test
    @DisplayName("시리얼이 TEST-가 아니거나 JSON이 깨졌으면 지우지 않고 DLX로 보낸다")
    void refusesRealOrBroken() {
        // 준비: 실제 기기 시리얼 이벤트
        byte[] real = event("C02REAL0001");
        // 준비: 깨진 JSON 메시지
        byte[] broken = "{".getBytes(StandardCharsets.UTF_8);
        // (도구) assertThatThrownBy(() -> 코드).isInstanceOf(예외) = 그 코드를 돌리면 이 종류의 예외가 나야 통과
        // 실행+확인: 실제 기기 이벤트는 "다시 넣지 말고 DLX로" 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(real)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 실행+확인: 깨진 JSON도 같은 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(broken)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // (도구) never() = 한 번도 불리지 않았어야 통과. any() = 아무 값이나
        // 확인: 아무것도 지우지 않았어야 한다
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] 지우는 중에 DB 오류가 난다.
    // [기대] 같은 메시지를 큐에 다시 넣지 않고 DLX로 보낸다(같은 실패가 끝없이 반복되는 것 방지).
    @Test
    @DisplayName("DB 오류는 다시 넣지 않고 DLX로 보낸다(재전달 무한 반복 방지)")
    void dbErrorGoesToDlx() {
        // (도구) thenThrow(예외) = 그 호출이 오면 예외를 던지게 한다
        // 준비: 지우면 DB 오류가 나게 한다
        when(store.deleteCheckpoint(CP)).thenThrow(new IllegalStateException("db"));
        // 준비: 시험 시점 이벤트
        byte[] test = event("TEST-0002");
        // 실행+확인: 처리하면 DLX로 보내는 예외가 나야 한다 = 같은 실패가 끝없이 반복되지 않는다
        assertThatThrownBy(() -> listener.handle(test)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}

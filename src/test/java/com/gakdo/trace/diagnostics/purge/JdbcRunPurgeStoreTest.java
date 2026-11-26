// 이 파일이 확인하는 것: 시험용 렌탈(기기 일련번호가 TEST-로 시작)을 지울 때 진단 기록도 함께 지우는 저장소.
// 실수로 실제 고객 기기의 진단을 지우지 않도록, 이벤트에 적힌 시리얼만 믿지 않고 DB에 저장된 시리얼로 다시 확인한다.

package com.gakdo.trace.diagnostics.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gakdo.trace.diagnostics.purge.infra.JdbcRunPurgeStore;

/** 시험 시점 지우기는 저장된 기기 시리얼로 다시 확인한다(이벤트 시리얼만 믿지 않음). */
class JdbcRunPurgeStoreTest {

    // 시험용 시점 ID
    private static final String CP = "cp_000000000000000001";
    // (도구) mock(종류) = 진짜 대신 가짜 객체를 만든다. 진짜 DB 없이 어떤 SQL을 보냈는지만 보려고 JdbcTemplate(SQL 실행 도구)을 가짜로 둔다
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    // 시험할 지우기 저장소를 가짜 SQL 도구로 만든다
    private final JdbcRunPurgeStore store = new JdbcRunPurgeStore(jdbc);

    // [상황] 지우라는 시점의 진단 중에 실제 기기 시리얼(TEST-가 아닌 것)이 있다.
    // [기대] 아무것도 지우지 않는다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("저장된 진단이 실제 기기 시리얼이면 아무것도 지우지 않는다")
    void realDeviceIsKept() {
        // (도구) when(가짜.메서드(조건)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. anyString() = 아무 글자나, eq(값) = 그 값일 때
        // 준비: "이 시점에 TEST-가 아닌 진단이 몇 개?"라는 질문에 1개라고 답하게 한다 = 실제 기기 진단이 있음
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CP))).thenReturn(1);
        // (도구) assertThat(값).isZero() = 값이 0이어야 통과하는 AssertJ 검사
        // 실행+확인: 그 시점을 지우라고 하면 지운 건수가 0이어야 한다
        assertThat(store.deleteCheckpoint(CP)).isZero();
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았는지 확인
        // 확인: 지우기(update) SQL을 아예 보내지 않았어야 한다
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // [상황] 그 시점의 진단이 모두 TEST- 시리얼이다.
    // [기대] 지운다(지운 건수 1).
    @Test
    @DisplayName("저장된 진단이 모두 TEST- 시리얼이면 지운다")
    void testDeviceIsPurged() {
        // 준비: 실제 기기 진단은 0개라고 답하게 한다 = 모두 TEST- 시리얼
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CP))).thenReturn(0);
        // 준비: 그 시점의 TEST- 진단을 지우는 SQL이 오면 1건 지웠다고 답하게 한다
        when(jdbc.update("delete from diagnostics.diagnostic_run where checkpoint_id = ?"
                + " and device_serial like 'TEST-%'", CP)).thenReturn(1);
        // (도구) isEqualTo(값) = 그 값과 같아야 통과
        // 실행+확인: 지운 건수가 1이어야 한다
        assertThat(store.deleteCheckpoint(CP)).isEqualTo(1);
    }

    // [상황] 확인한 직후 실제 기기 진단이 끼어드는 경우를 대비한다.
    // [기대] 실제로 지우는 SQL 문장마다 "TEST-로 시작하는 시리얼만" 조건이 붙어 있다(이중 안전장치).
    @Test
    @DisplayName("검사 뒤 실제 기기 진단이 끼어들어도 지우는 문장마다 TEST- 시리얼만 고른다")
    void everyDeleteFiltersTestSerial() {
        // 준비: 실제 기기 진단은 0개라고 답하게 한다
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CP))).thenReturn(0);
        // 실행: 그 시점을 지운다
        store.deleteCheckpoint(CP);
        // (도구) ArgumentCaptor = 가짜에게 넘어간 값을 붙잡아 두는 그물. getAllValues()로 모두 꺼낸다
        // 보낸 SQL 문장을 붙잡을 그물을 만든다
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        // (도구) atLeastOnce() = 적어도 한 번 불렸어야 통과
        // 확인: 지우기 SQL을 한 번 이상 보냈어야 하고, 그 문장들을 그물에 담는다
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        // (도구) isNotEmpty() = 비어 있지 않아야, allMatch(조건) = 모두 조건에 맞아야 통과
        // 확인: 모든 지우기 문장에 "TEST-로 시작하는 시리얼만" 조건이 붙어 있어야 한다(이중 안전장치)
        assertThat(sql.getAllValues()).isNotEmpty().allMatch(q -> q.contains("device_serial like 'TEST-%'"));
    }
}

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

    private static final String CP = "cp_000000000000000001";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcRunPurgeStore store = new JdbcRunPurgeStore(jdbc);

    @Test
    @DisplayName("저장된 진단이 실제 기기 시리얼이면 아무것도 지우지 않는다")
    void realDeviceIsKept() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CP))).thenReturn(1);
        assertThat(store.deleteCheckpoint(CP)).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("저장된 진단이 모두 TEST- 시리얼이면 지운다")
    void testDeviceIsPurged() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CP))).thenReturn(0);
        when(jdbc.update(eq("delete from diagnostics.diagnostic_run where checkpoint_id = ?"), eq(CP))).thenReturn(1);
        assertThat(store.deleteCheckpoint(CP)).isEqualTo(1);
    }
}

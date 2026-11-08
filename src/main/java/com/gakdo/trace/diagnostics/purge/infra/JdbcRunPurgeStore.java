package com.gakdo.trace.diagnostics.purge.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.diagnostics.purge.application.RunPurgeStore;

/** 진단 실행과 딸린 행을 FK 순서대로 지운다. 플래그는 다른 시점 실행을 기준(base_run_id)으로 가리킬 수 있어 양쪽 다 지운다. */
@Repository
public class JdbcRunPurgeStore implements RunPurgeStore {

    /** 시험 기기 진단만. 문장마다 저장된 시리얼로 다시 거르므로 검사 뒤 실제 기기 업로드가 끼어들어도 지우지 않는다. */
    private static final String RUNS = "select id from diagnostics.diagnostic_run where checkpoint_id = ?"
            + " and device_serial like 'TEST-%'";

    private final JdbcTemplate jdbc;

    public JdbcRunPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteCheckpoint(String checkpointId) {
        // 이벤트의 시리얼(TEST-)만 믿지 않는다. 이 시점에 저장된 진단의 기기 시리얼이 시험 기기가 아니면 지우지 않는다
        // (같은 브로커 계정을 쓰는 다른 구성요소가 실제 고객 시점의 진단을 지우지 못하게)
        Integer real = jdbc.queryForObject("select count(*) from diagnostics.diagnostic_run where checkpoint_id = ?"
                + " and device_serial not like 'TEST-%'", Integer.class, checkpointId);
        if (real != null && real > 0) {
            return 0;
        }
        jdbc.update("delete from diagnostics.inspection_flag where run_id in (" + RUNS
                + ") or base_run_id in (" + RUNS + ")", checkpointId, checkpointId);
        for (String table : new String[] {"hardware_component", "battery_reading", "ssd_health", "benchmark_result"}) {
            jdbc.update("delete from diagnostics." + table + " where run_id in (" + RUNS + ")", checkpointId);
        }
        return jdbc.update("delete from diagnostics.diagnostic_run where checkpoint_id = ?"
                + " and device_serial like 'TEST-%'", checkpointId);
    }
}

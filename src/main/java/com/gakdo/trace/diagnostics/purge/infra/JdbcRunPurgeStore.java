package com.gakdo.trace.diagnostics.purge.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.diagnostics.purge.application.RunPurgeStore;

/** 진단 실행과 딸린 행을 FK 순서대로 지운다. 플래그는 다른 시점 실행을 기준(base_run_id)으로 가리킬 수 있어 양쪽 다 지운다. */
@Repository
public class JdbcRunPurgeStore implements RunPurgeStore {

    private static final String RUNS = "select id from diagnostics.diagnostic_run where checkpoint_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcRunPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteCheckpoint(String checkpointId) {
        jdbc.update("delete from diagnostics.inspection_flag where checkpoint_id = ? or run_id in (" + RUNS
                + ") or base_run_id in (" + RUNS + ")", checkpointId, checkpointId, checkpointId);
        for (String table : new String[] {"hardware_component", "battery_reading", "ssd_health", "benchmark_result"}) {
            jdbc.update("delete from diagnostics." + table + " where run_id in (" + RUNS + ")", checkpointId);
        }
        return jdbc.update("delete from diagnostics.diagnostic_run where checkpoint_id = ?", checkpointId);
    }
}

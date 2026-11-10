package com.gakdo.trace.diagnostics.purge.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.diagnostics.purge.application.RunPurgeStore;

/** 진단 실행과 딸린 행을 FK 순서대로 지운다. 플래그는 다른 시점 실행을 기준(base_run_id)으로 가리킬 수 있어 양쪽 다 지운다. */
@Repository
public class JdbcRunPurgeStore implements RunPurgeStore {

    /*
     * 시험 기기 진단만. 문장마다 저장된 시리얼로 다시 거르므로 검사 뒤 실제 기기 업로드가 끼어들어도 지우지 않는다.
     * 표 이름·조건을 이어 붙이지 않고 완성된 문장으로 둔다(동적 SQL을 만들지 않음).
     */
    private static final String TEST_RUNS = "(select id from diagnostics.diagnostic_run where checkpoint_id = ?"
            + " and device_serial like 'TEST-%')";
    private static final String COUNT_REAL = "select count(*) from diagnostics.diagnostic_run where checkpoint_id = ?"
            + " and device_serial not like 'TEST-%'";
    private static final String DELETE_FLAGS = "delete from diagnostics.inspection_flag where run_id in " + TEST_RUNS
            + " or base_run_id in " + TEST_RUNS;
    private static final String[] DELETE_CHILDREN = {
        "delete from diagnostics.hardware_component where run_id in " + TEST_RUNS,
        "delete from diagnostics.battery_reading where run_id in " + TEST_RUNS,
        "delete from diagnostics.ssd_health where run_id in " + TEST_RUNS,
        "delete from diagnostics.benchmark_result where run_id in " + TEST_RUNS
    };
    private static final String DELETE_RUNS = "delete from diagnostics.diagnostic_run where checkpoint_id = ?"
            + " and device_serial like 'TEST-%'";

    private final JdbcTemplate jdbc;

    public JdbcRunPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteCheckpoint(String checkpointId) {
        // 이벤트의 시리얼(TEST-)만 믿지 않는다. 이 시점에 저장된 진단의 기기 시리얼이 시험 기기가 아니면 지우지 않는다
        // (같은 브로커 계정을 쓰는 다른 구성요소가 실제 고객 시점의 진단을 지우지 못하게)
        Integer real = jdbc.queryForObject(COUNT_REAL, Integer.class, checkpointId);
        if (real != null && real > 0) {
            return 0;
        }
        jdbc.update(DELETE_FLAGS, checkpointId, checkpointId);
        for (String delete : DELETE_CHILDREN) {
            jdbc.update(delete, checkpointId);
        }
        return jdbc.update(DELETE_RUNS, checkpointId);
    }
}

package com.gakdo.trace.diagnostics.purge.application;

/** 시점 하나의 진단 행을 지우는 포트(시험 데이터 지우기). 구현은 infra(JDBC). */
public interface RunPurgeStore {

    /** @return 지운 진단 실행 수(이미 없으면 0) */
    int deleteCheckpoint(String checkpointId);
}

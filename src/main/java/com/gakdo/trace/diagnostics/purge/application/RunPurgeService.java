package com.gakdo.trace.diagnostics.purge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시험 데이터 지우기(trace.checkpoint.purged, 11/4 민서 요청): 시점 하나의 진단 실행과 딸린 행을 한 트랜잭션으로 지운다. */
@Service
public class RunPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RunPurgeService.class);

    private final RunPurgeStore store;

    public RunPurgeService(RunPurgeStore store) {
        this.store = store;
    }

    @Transactional
    public int purge(String rentalId, String checkpointId) {
        int runs = store.deleteCheckpoint(checkpointId);
        log.info("시험 시점 진단 지움 rental={} checkpoint={} 실행={}", rentalId, checkpointId, runs);
        return runs;
    }
}

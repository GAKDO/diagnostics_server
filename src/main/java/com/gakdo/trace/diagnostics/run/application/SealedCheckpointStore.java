package com.gakdo.trace.diagnostics.run.application;

import java.time.OffsetDateTime;

/** 봉인된 시점 목록(trace.record.sealed로 채움). 진단 다시 하기를 봉인 전에만 허용하는 데 쓴다. 구현은 infra(JDBC). */
public interface SealedCheckpointStore {

    /** 봉인을 기록한다. 같은 시점을 두 번 받아도 처음 기록을 그대로 둔다. @return 새로 기록했으면 true */
    boolean markSealed(String checkpointId, OffsetDateTime sealedAt);

    boolean isSealed(String checkpointId);
}

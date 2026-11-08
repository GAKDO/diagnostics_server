package com.gakdo.trace.diagnostics.trend.application;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 진단 실행 트랜잭션이 커밋된 뒤에 추이를 쓴다. 롤백되면 쓰지 않는다.
 * 응답을 늦추지 않도록 별도 실행기(가상 스레드)에서 쓰고, 어떤 실패도 경고 로그로만 남긴다.
 */
@Component
public class TrendListener {

    private static final Logger log = LoggerFactory.getLogger(TrendListener.class);

    private final TrendWriter writer;
    private final Executor executor;

    public TrendListener(TrendWriter writer, @Qualifier("trendExecutor") Executor executor) {
        this.writer = writer;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecorded(DiagnosticsTrendRecorded event) {
        try {
            executor.execute(() -> writeQuietly(event.point()));
        } catch (RuntimeException e) {
            log.warn("진단 추이 쓰기를 맡기지 못했습니다: {}", e.toString());
        }
    }

    private void writeQuietly(TrendPoint point) {
        try {
            writer.write(point);
        } catch (RuntimeException e) {
            log.warn("진단 추이를 쓰지 못했습니다: {}", e.toString());
        }
    }
}

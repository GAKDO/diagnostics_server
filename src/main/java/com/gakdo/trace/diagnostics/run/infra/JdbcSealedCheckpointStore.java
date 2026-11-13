package com.gakdo.trace.diagnostics.run.infra;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.diagnostics.run.application.SealedCheckpointStore;

/** diagnostics.sealed_checkpoint(V5). 완성된 문장만 쓴다(동적 SQL 없음). */
@Repository
public class JdbcSealedCheckpointStore implements SealedCheckpointStore {

    private static final String INSERT = "insert into diagnostics.sealed_checkpoint(checkpoint_id, sealed_at) values (?, ?)"
            + " on conflict (checkpoint_id) do nothing";
    private static final String EXISTS = "select exists(select 1 from diagnostics.sealed_checkpoint where checkpoint_id = ?)";

    private final JdbcTemplate jdbc;

    public JdbcSealedCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markSealed(String checkpointId, OffsetDateTime sealedAt) {
        return jdbc.update(INSERT, checkpointId, sealedAt) > 0;
    }

    @Override
    public boolean isSealed(String checkpointId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS, Boolean.class, checkpointId));
    }
}

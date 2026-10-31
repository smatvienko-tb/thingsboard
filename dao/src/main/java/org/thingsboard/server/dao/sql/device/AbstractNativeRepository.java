// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.device;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

import static org.thingsboard.server.dao.model.ModelConstants.NULL_UUID;

@RequiredArgsConstructor
@Repository
@Slf4j
public class AbstractNativeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * Runs {@code query} once and passes every row to {@code consumer}, reading {@code fetchSize} rows per round trip
     * through a server-side cursor. Memory stays bounded by {@code fetchSize}, and the whole iteration sees one snapshot.
     * <p>
     * One pooled connection is held for the whole iteration (a cursor cannot release it between batches). This is still the
     * shortest total hold: one pass instead of many paged queries (about 0.5 s per 1M devices on PostgreSQL, about 1 s on Citus,
     * where keyset or OFFSET pages fan out to every shard). To keep the hold that short, the consumer runs while the cursor is
     * open and must only do fast in-memory work: no blocking calls and no waiting on other database work. An exception thrown
     * by the consumer stops the iteration and is rethrown (Spring's repository exception translation may wrap it).
     */
    protected <T> void stream(String query, int fetchSize, RowMapper<T> mapper, Consumer<T> consumer, Object... params) {
        long startTs = System.currentTimeMillis();
        int[] rowCount = {0};
        RowCallbackHandler handler = rs -> consumer.accept(mapper.mapRow(rs, rowCount[0]++));
        // the cursor only streams inside a transaction (autocommit off); otherwise the driver loads the full result
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.getJdbcOperations().query(connection -> {
            PreparedStatement ps = connection.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(fetchSize);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps;
        }, handler));
        log.debug("Streamed {} rows, connection held for {} ms: {}", rowCount[0], System.currentTimeMillis() - startTs, query);
    }

    protected static UUID getTenantId(ResultSet rs) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        return tenantId != null ? tenantId : TenantId.SYS_TENANT_ID.getId();
    }

    protected static EntityId getOwnerId(ResultSet rs, UUID tenantId) throws SQLException {
        UUID customerId = rs.getObject("customer_id", UUID.class);
        return customerId != null && !NULL_UUID.equals(customerId) ? new CustomerId(customerId) : TenantId.fromUUID(tenantId);
    }

}

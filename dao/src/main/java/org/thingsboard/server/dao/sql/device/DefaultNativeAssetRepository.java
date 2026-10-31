// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.server.common.data.ProfileEntityIdInfo;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.AssetProfileId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@Slf4j
public class DefaultNativeAssetRepository extends AbstractNativeRepository implements NativeAssetRepository {

    private static final String PROFILE_ASSET_ID_INFO_BY_TENANT_QUERY =
            "SELECT id, tenant_id, customer_id, asset_profile_id FROM asset WHERE tenant_id = ?";

    public DefaultNativeAssetRepository(NamedParameterJdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        super(jdbcTemplate, transactionTemplate);
    }

    @Override
    public void findProfileEntityIdInfosByTenantId(UUID tenantId, int fetchSize, Consumer<ProfileEntityIdInfo> consumer) {
        stream(PROFILE_ASSET_ID_INFO_BY_TENANT_QUERY, fetchSize, (rs, rowNum) -> toInfo(rs), consumer, tenantId);
    }

    private static ProfileEntityIdInfo toInfo(ResultSet rs) throws SQLException {
        UUID tenantId = getTenantId(rs);
        return ProfileEntityIdInfo.create(tenantId, getOwnerId(rs, tenantId),
                new AssetProfileId(rs.getObject("asset_profile_id", UUID.class)), new AssetId(rs.getObject("id", UUID.class)));
    }

}

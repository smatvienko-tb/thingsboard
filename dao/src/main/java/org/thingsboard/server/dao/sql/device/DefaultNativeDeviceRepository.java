// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.server.common.data.DeviceIdInfo;
import org.thingsboard.server.common.data.ProfileEntityIdInfo;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@Slf4j
public class DefaultNativeDeviceRepository extends AbstractNativeRepository implements NativeDeviceRepository {

    private static final String DEVICE_ID_INFO_QUERY = "SELECT id, tenant_id, customer_id FROM device";
    private static final String PROFILE_DEVICE_ID_INFO_BY_TENANT_QUERY =
            "SELECT id, tenant_id, customer_id, device_profile_id FROM device WHERE tenant_id = ?";

    public DefaultNativeDeviceRepository(NamedParameterJdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        super(jdbcTemplate, transactionTemplate);
    }

    @Override
    public void findAllDeviceIdInfos(int fetchSize, Consumer<DeviceIdInfo> consumer) {
        stream(DEVICE_ID_INFO_QUERY, fetchSize, (rs, rowNum) -> new DeviceIdInfo(
                getTenantId(rs), rs.getObject("customer_id", UUID.class), rs.getObject("id", UUID.class)), consumer);
    }

    @Override
    public void findProfileEntityIdInfosByTenantId(UUID tenantId, int fetchSize, Consumer<ProfileEntityIdInfo> consumer) {
        stream(PROFILE_DEVICE_ID_INFO_BY_TENANT_QUERY, fetchSize, (rs, rowNum) -> toInfo(rs), consumer, tenantId);
    }

    private static ProfileEntityIdInfo toInfo(ResultSet rs) throws SQLException {
        UUID tenantId = getTenantId(rs);
        return ProfileEntityIdInfo.create(tenantId, getOwnerId(rs, tenantId),
                new DeviceProfileId(rs.getObject("device_profile_id", UUID.class)), new DeviceId(rs.getObject("id", UUID.class)));
    }

}

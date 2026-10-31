// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.cf;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.cf.CalculatedField;
import org.thingsboard.server.common.data.cf.CalculatedFieldType;
import org.thingsboard.server.common.data.cf.configuration.CalculatedFieldConfiguration;
import org.thingsboard.server.common.data.debug.DebugSettings;
import org.thingsboard.server.common.data.id.CalculatedFieldId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.sql.device.AbstractNativeRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@Slf4j
public class DefaultNativeCalculatedFieldRepository extends AbstractNativeRepository implements NativeCalculatedFieldRepository {

    private static final String CF_QUERY = "SELECT id, created_time, tenant_id, entity_type, entity_id, type, name, " +
            "configuration_version, configuration, version, debug_settings, additional_info FROM calculated_field";
    private static final String CF_BY_TENANT_QUERY = CF_QUERY + " WHERE tenant_id = ?";

    public DefaultNativeCalculatedFieldRepository(NamedParameterJdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        super(jdbcTemplate, transactionTemplate);
    }

    @Override
    public void findAllCalculatedFields(int fetchSize, Consumer<CalculatedField> consumer) {
        stream(CF_QUERY, fetchSize, (rs, rowNum) -> toCalculatedField(rs), skipInvalid(consumer));
    }

    @Override
    public void findCalculatedFieldsByTenantId(UUID tenantId, int fetchSize, Consumer<CalculatedField> consumer) {
        stream(CF_BY_TENANT_QUERY, fetchSize, (rs, rowNum) -> toCalculatedField(rs), skipInvalid(consumer), tenantId);
    }

    private static Consumer<CalculatedField> skipInvalid(Consumer<CalculatedField> consumer) {
        return calculatedField -> {
            if (calculatedField != null) {
                consumer.accept(calculatedField);
            }
        };
    }

    private static CalculatedField toCalculatedField(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        CalculatedField calculatedField = new CalculatedField();
        calculatedField.setId(new CalculatedFieldId(id));
        calculatedField.setCreatedTime(rs.getLong("created_time"));
        calculatedField.setTenantId(TenantId.fromUUID(rs.getObject("tenant_id", UUID.class)));
        calculatedField.setEntityId(EntityIdFactory.getByTypeAndUuid(EntityType.valueOf(rs.getString("entity_type")), rs.getObject("entity_id", UUID.class)));
        calculatedField.setType(CalculatedFieldType.valueOf(rs.getString("type")));
        calculatedField.setName(rs.getString("name"));
        calculatedField.setConfigurationVersion(rs.getInt("configuration_version"));
        try {
            JsonNode configuration = JacksonUtil.toJsonNode(rs.getString("configuration"));
            calculatedField.setConfiguration(JacksonUtil.treeToValue(configuration, CalculatedFieldConfiguration.class));
        } catch (Exception e) {
            log.error("Invalid configuration for CalculatedField [{}]. Skipping.", id, e);
            return null;
        }
        long version = rs.getLong("version");
        calculatedField.setVersion(rs.wasNull() ? 0 : version);
        calculatedField.setDebugSettings(JacksonUtil.fromString(rs.getString("debug_settings"), DebugSettings.class));
        calculatedField.setAdditionalInfo(JacksonUtil.toJsonNode(rs.getString("additional_info")));
        return calculatedField;
    }

}

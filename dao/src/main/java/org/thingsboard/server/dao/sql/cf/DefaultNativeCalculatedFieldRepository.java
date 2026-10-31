// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.cf;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.cf.CalculatedField;
import org.thingsboard.server.common.data.cf.CalculatedFieldType;
import org.thingsboard.server.common.data.cf.configuration.CalculatedFieldConfiguration;
import org.thingsboard.server.common.data.debug.DebugSettings;
import org.thingsboard.server.common.data.id.CalculatedFieldId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Repository
@Slf4j
public class DefaultNativeCalculatedFieldRepository implements NativeCalculatedFieldRepository {

    private static final String CF_QUERY = "SELECT id, created_time, tenant_id, entity_type, entity_id, type, name, " +
            "configuration_version, configuration, version, debug_settings, additional_info FROM calculated_field";
    // keyset pages over the primary key and the (tenant_id, id) index: each page is a short range scan, no OFFSET and no count
    private static final String CF_FIRST_PAGE_QUERY = CF_QUERY + " ORDER BY id LIMIT ?";
    private static final String CF_NEXT_PAGE_QUERY = CF_QUERY + " WHERE id > ? ORDER BY id LIMIT ?";
    private static final String CF_BY_TENANT_FIRST_PAGE_QUERY = CF_QUERY + " WHERE tenant_id = ? ORDER BY id LIMIT ?";
    private static final String CF_BY_TENANT_NEXT_PAGE_QUERY = CF_QUERY + " WHERE tenant_id = ? AND id > ? ORDER BY id LIMIT ?";

    private final JdbcTemplate jdbcTemplate;

    public DefaultNativeCalculatedFieldRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate.getJdbcTemplate();
    }

    @Override
    public void findAllCalculatedFields(int pageSize, Consumer<List<CalculatedField>> pageConsumer) {
        forEachPage(pageSize, pageConsumer,
                () -> jdbcTemplate.query(CF_FIRST_PAGE_QUERY, KEYSET_ROW_MAPPER, pageSize),
                lastId -> jdbcTemplate.query(CF_NEXT_PAGE_QUERY, KEYSET_ROW_MAPPER, lastId, pageSize));
    }

    @Override
    public void findCalculatedFieldsByTenantId(UUID tenantId, int pageSize, Consumer<List<CalculatedField>> pageConsumer) {
        forEachPage(pageSize, pageConsumer,
                () -> jdbcTemplate.query(CF_BY_TENANT_FIRST_PAGE_QUERY, KEYSET_ROW_MAPPER, tenantId, pageSize),
                lastId -> jdbcTemplate.query(CF_BY_TENANT_NEXT_PAGE_QUERY, KEYSET_ROW_MAPPER, tenantId, lastId, pageSize));
    }

    /**
     * Reads keyset pages until a page comes back shorter than {@code pageSize}. Each page is handed to the consumer after its
     * query has finished, so no database connection is held while the consumer runs; rows with an unreadable configuration
     * are left out of the page.
     */
    private static void forEachPage(int pageSize, Consumer<List<CalculatedField>> pageConsumer,
                                    Supplier<List<KeysetRow>> firstPage, Function<UUID, List<KeysetRow>> nextPage) {
        List<KeysetRow> page = firstPage.get();
        while (true) {
            List<CalculatedField> calculatedFields = page.stream().map(KeysetRow::calculatedField).filter(Objects::nonNull).toList();
            if (!calculatedFields.isEmpty()) {
                pageConsumer.accept(calculatedFields);
            }
            if (page.size() < pageSize) {
                return;
            }
            page = nextPage.apply(page.get(page.size() - 1).id());
        }
    }

    private static final RowMapper<KeysetRow> KEYSET_ROW_MAPPER =
            (rs, rowNum) -> new KeysetRow(rs.getObject("id", UUID.class), toCalculatedField(rs));

    // the id is kept separately because a row with an unreadable configuration maps to a null calculated field
    private record KeysetRow(UUID id, CalculatedField calculatedField) {}

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

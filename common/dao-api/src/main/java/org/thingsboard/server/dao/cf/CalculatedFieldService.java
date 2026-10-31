// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.cf;

import org.thingsboard.server.common.data.cf.CalculatedField;
import org.thingsboard.server.common.data.cf.CalculatedFieldFilter;
import org.thingsboard.server.common.data.cf.CalculatedFieldInfo;
import org.thingsboard.server.common.data.cf.CalculatedFieldType;
import org.thingsboard.server.common.data.id.CalculatedFieldId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.dao.entity.EntityDaoService;

import java.util.List;
import java.util.function.Consumer;

public interface CalculatedFieldService extends EntityDaoService {

    CalculatedField save(CalculatedField calculatedField);

    CalculatedField save(CalculatedField calculatedField, boolean doValidate);

    CalculatedField findById(TenantId tenantId, CalculatedFieldId calculatedFieldId);

    CalculatedField findByEntityIdAndTypeAndName(EntityId entityId, CalculatedFieldType type, String name);

    List<CalculatedFieldId> findCalculatedFieldIdsByEntityId(TenantId tenantId, EntityId entityId);

    List<CalculatedField> findCalculatedFieldsByEntityId(TenantId tenantId, EntityId entityId);

    /**
     * Passes every calculated field of every tenant to {@code consumer} in one database pass, reading {@code fetchSize}
     * rows per round trip; fields with an unreadable configuration are skipped. The consumer runs while the database
     * cursor is open: keep it fast and in-memory.
     */
    void findAllCalculatedFields(int fetchSize, Consumer<CalculatedField> consumer);

    /**
     * Same as {@link #findAllCalculatedFields(int, Consumer)}, limited to one tenant.
     */
    void findCalculatedFieldsByTenantId(TenantId tenantId, int fetchSize, Consumer<CalculatedField> consumer);

    PageData<CalculatedFieldInfo> findCalculatedFieldsByTenantIdAndFilter(TenantId tenantId, CalculatedFieldFilter filter, PageLink pageLink);

    PageData<String> findCalculatedFieldNamesByTenantIdAndType(TenantId tenantId, CalculatedFieldType type, PageLink pageLink);

    PageData<CalculatedField> findCalculatedFieldsByEntityId(TenantId tenantId, EntityId entityId, CalculatedFieldType type, PageLink pageLink);

    void deleteCalculatedField(TenantId tenantId, CalculatedFieldId calculatedFieldId);

    int deleteAllCalculatedFieldsByEntityId(TenantId tenantId, EntityId entityId);

    boolean referencedInAnyCalculatedField(TenantId tenantId, EntityId referencedEntityId);

}

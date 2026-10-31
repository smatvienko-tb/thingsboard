// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.cf;

import org.thingsboard.server.common.data.cf.CalculatedField;

import java.util.UUID;
import java.util.function.Consumer;

public interface NativeCalculatedFieldRepository {

    void findAllCalculatedFields(int fetchSize, Consumer<CalculatedField> consumer);

    void findCalculatedFieldsByTenantId(UUID tenantId, int fetchSize, Consumer<CalculatedField> consumer);

}

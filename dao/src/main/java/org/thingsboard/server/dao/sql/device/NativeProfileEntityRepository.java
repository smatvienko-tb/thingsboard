// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.device;

import org.thingsboard.server.common.data.ProfileEntityIdInfo;

import java.util.UUID;
import java.util.function.Consumer;

public interface NativeProfileEntityRepository {

    void findProfileEntityIdInfosByTenantId(UUID tenantId, int fetchSize, Consumer<ProfileEntityIdInfo> consumer);

}

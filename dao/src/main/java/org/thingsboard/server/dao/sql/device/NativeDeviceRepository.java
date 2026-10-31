// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0 AND BUSL-1.1
package org.thingsboard.server.dao.sql.device;

import org.thingsboard.server.common.data.DeviceIdInfo;

import java.util.function.Consumer;

public interface NativeDeviceRepository extends NativeProfileEntityRepository {

    void findAllDeviceIdInfos(int fetchSize, Consumer<DeviceIdInfo> consumer);

}

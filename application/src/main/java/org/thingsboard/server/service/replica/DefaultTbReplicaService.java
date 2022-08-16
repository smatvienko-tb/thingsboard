/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.replica;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.gen.transport.TransportProtos.ToReplicaMsg;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.util.TbCoreComponent;

@Slf4j
@TbCoreComponent
@Service
public class DefaultTbReplicaService implements TbReplicaService {

    @Autowired
    DeviceService deviceService;

    @Autowired
    TbClusterService tbClusterService;

    @Override
    public void processReplicaMsg(TbProtoQueueMsg<ToReplicaMsg> toReplicaMsgTbProtoQueueMsg) {
        log.warn("Processing replica msg [{}]", toReplicaMsgTbProtoQueueMsg);
        final ToReplicaMsg toReplicaMsg = toReplicaMsgTbProtoQueueMsg.getValue();
        try {
            final EntityType entityType = EntityType.valueOf(toReplicaMsg.getEntityType());

            switch (entityType) {
                case DEVICE:
                    processDevice(toReplicaMsg);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported entity type [" + entityType + "]");
            }

        } catch (Throwable e) {
            log.warn("Failed to process message: {}", toReplicaMsg, e);
            //callback.onFailure(e);
        }
    }

    void processDevice(ToReplicaMsg toReplicaMsg) {
        final ActionType actionType = ActionType.valueOf(toReplicaMsg.getAction());
        switch (actionType) {
            case ADDED:
            case UPDATED:
                processDeviceUpdate(toReplicaMsg);
                break;
            case DELETED:
                processDeviceDelete(toReplicaMsg);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported action type [" + actionType + "]");
        }
    }

    void processDeviceUpdate(ToReplicaMsg toReplicaMsg) {
        Device device = JacksonUtil.fromString(toReplicaMsg.getData(), Device.class);
        Device oldDevice = deviceService.findDeviceById(device.getTenantId(), device.getId());
        Device savedDevice = deviceService.saveDevice(device, false);
        tbClusterService.onDeviceUpdated(savedDevice, oldDevice, false);
    }

    void processDeviceDelete(ToReplicaMsg toReplicaMsg) {
        Device device = JacksonUtil.fromString(toReplicaMsg.getData(), Device.class);
        tbClusterService.onDeviceDeleted(device, null, false);
    }

}

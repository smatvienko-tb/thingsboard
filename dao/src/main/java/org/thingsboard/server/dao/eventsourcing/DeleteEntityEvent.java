/**
 * Copyright © 2016-2023 The Thingsboard Authors
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
package org.thingsboard.server.dao.eventsourcing;

import lombok.Builder;
import lombok.Data;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;

@Builder(builderClassName = "Builder", toBuilder = true)
@Data
public class DeleteEntityEvent<T> {
    private final long ts;
    private final TenantId tenantId;
    private final EntityId entityId;
    private final EdgeId edgeId;
    private final T entity;

    // Use static class to avoid name collision with Lombok's generated Builder.
    public static class Builder<T> {
        private long ts = Long.MIN_VALUE;

        // Custom build method.
        public DeleteEntityEvent<T> build() {
            // If ts has not been set, set it to the current time.
            if (this.ts == Long.MIN_VALUE) {
                this.ts = System.currentTimeMillis();
            }

            // Call the original build method.
            return new DeleteEntityEvent<>(ts, tenantId, entityId, edgeId, entity);
        }
    }
}

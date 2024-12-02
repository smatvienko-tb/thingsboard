/**
 * Copyright © 2016-2024 The Thingsboard Authors
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
package org.thingsboard.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
final class ThingsBoardScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {

    public ThingsBoardScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        Objects.requireNonNull(command);
        return super.scheduleAtFixedRate(new SafePeriodicRunnable(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        return super.scheduleWithFixedDelay(new SafePeriodicRunnable(command), initialDelay, delay, unit);
    }

    private record SafePeriodicRunnable(Runnable runnable) implements Runnable {

        @Override
        public void run() {
            try {
                runnable.run();
            } catch (Exception e) {
                log.error("Uncaught exception occurred during periodic task execution!", e);
            }
        }

    }

}

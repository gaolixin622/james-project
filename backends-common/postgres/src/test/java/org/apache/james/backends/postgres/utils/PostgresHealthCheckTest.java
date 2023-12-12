/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.backends.postgres.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.backends.postgres.quota.PostgresQuotaLimitDAO;
import org.apache.james.backends.postgres.quota.PostgresQuotaModule;
import org.apache.james.core.healthcheck.Result;
import org.apache.james.core.healthcheck.ResultStatus;
import org.apache.james.core.quota.QuotaComponent;
import org.apache.james.core.quota.QuotaLimit;
import org.apache.james.core.quota.QuotaScope;
import org.apache.james.core.quota.QuotaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import reactor.core.publisher.Mono;

public class PostgresHealthCheckTest {
    private PostgresHealthCheck testee;

    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresQuotaModule.MODULE);

    @BeforeEach
    void setup() {
        testee = new PostgresHealthCheck(postgresExtension.getPostgresExecutor());
    }

    @Test
    void shouldBeHealthy() {
        Result result = Mono.from(testee.check()).block();
        assertThat(result.getStatus()).isEqualTo(ResultStatus.HEALTHY);
    }

    @Test
    void shouldBeUnhealthyWhenPaused() {
        try {
            postgresExtension.pause();
            Result result = Mono.from(testee.check()).block();
            assertThat(result.getStatus()).isEqualTo(ResultStatus.UNHEALTHY);
        } finally {
            postgresExtension.unpause();
        }
    }
}
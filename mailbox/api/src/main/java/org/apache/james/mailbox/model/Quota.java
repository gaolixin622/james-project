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
package org.apache.james.mailbox.model;

import org.apache.james.mailbox.quota.QuotaValue;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public class Quota<T extends QuotaValue<T>> {

    public static <T extends QuotaValue<T>> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T extends QuotaValue<T>> {

        private T computedLimit;
        private T used;

        private Builder() {
        }

        public Builder<T> computedLimit(T limit) {
            this.computedLimit = limit;
            return this;
        }

        public Builder<T> used(T used) {
            this.used = used;
            return this;
        }

        public Quota<T> build() {
            Preconditions.checkState(used != null);
            Preconditions.checkState(computedLimit != null);
            return new Quota<>(used, computedLimit);
        }

    }

    private final T limit;
    private final T used;

    private Quota(T used, T max) {
        this.used = used;
        this.limit = max;
    }

    public T getLimit() {
        return limit;
    }

    public T getUsed() {
        return used;
    }

    public Quota<T> addValueToQuota(T value) {
        return new Quota<>(used.add(value), limit);
    }

    /**
     * Tells us if the quota is reached
     *
     * @return True if the user over uses the resource of this quota
     */
    public boolean isOverQuota() {
        return isOverQuotaWithAdditionalValue(0);
    }

    public boolean isOverQuotaWithAdditionalValue(long additionalValue) {
        Preconditions.checkArgument(additionalValue >= 0);
        return limit.isLimited() && used.add(additionalValue).isGreaterThan(limit);
    }

    @Override
    public String toString() {
        return used + "/" + limit;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || ! (o instanceof  Quota)) {
            return false;
        }
        Quota<?> other = (Quota<?>) o;
        return Objects.equal(used, other.getUsed())
            && Objects.equal(limit,other.getLimit());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(used, limit);
    }

}
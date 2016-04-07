/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.routing.allocation.deallocator;


import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.test.cluster.TestClusterService;
import org.junit.Test;

import static org.hamcrest.core.Is.is;

public class DeallocatorMinAvailibilityConfigTest extends ElasticsearchTestCase {

    private static final ClusterService CLUSTER_SERVICE = new TestClusterService();

    @Test
    public void testMinAvailibilityConfigRead() throws Exception {

        Deallocators deallocFull = new Deallocators(CLUSTER_SERVICE,
                new AllShardsDeallocator(CLUSTER_SERVICE, null, null),
                new PrimariesDeallocator(CLUSTER_SERVICE, null, null, null),
                ImmutableSettings.builder()
                        .put(Deallocators.GRACEFUL_STOP_MIN_AVAILABILITY,
                                Deallocators.MinAvailability.FULL).build());

        Deallocators deallocPrimary = new Deallocators(CLUSTER_SERVICE,
                new AllShardsDeallocator(CLUSTER_SERVICE, null, null),
                new PrimariesDeallocator(CLUSTER_SERVICE, null, null, null),
                ImmutableSettings.builder()
                        .put(Deallocators.GRACEFUL_STOP_MIN_AVAILABILITY,
                                Deallocators.MinAvailability.PRIMARIES).build());

        assertThat(deallocFull.deallocator() instanceof AllShardsDeallocator, is(true));
        assertThat(deallocPrimary.deallocator() instanceof PrimariesDeallocator, is(true));
    }
}

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

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 2)
public class PrimariesDeallocatorIT extends AbstractDeallocatorTestCase {

    static {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
        System.setProperty(TESTS_CLUSTER, ""); // ensure InternalTestCluster
    }

    @Test
    public void testDeallocate() throws Exception {
        createIndices();

        PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        Deallocator.DeallocationResult result = future.get(1, TimeUnit.MINUTES);
        assertThat(result.success(), is(true));
        assertThat(result.didDeallocate(), is(true));

        ensureGreen("t0");

        assertThat(clusterService().state().getRoutingNodes().node(takeDownNode.id()).shardsWithState("t0", ShardRoutingState.STARTED, ShardRoutingState.INITIALIZING, ShardRoutingState.RELOCATING).size(), is(0));
        ClusterHealthStatus status = client().admin().cluster().prepareHealth().setWaitForYellowStatus().setTimeout("2s").execute().actionGet().getStatus();
        assertThat(status, isOneOf(ClusterHealthStatus.GREEN, ClusterHealthStatus.YELLOW));
    }

    @Test
    public void testDeallocateAllocationEnableSetting() throws Exception {
        createIndices();

        cluster().client().admin().cluster().prepareUpdateSettings().setTransientSettings(
                Settings.builder().put(EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE, EnableAllocationDecider.Allocation.NONE.name())
        ).execute().actionGet();
        Thread.sleep(100);

        PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        Deallocator.DeallocationResult result = future.get(1, TimeUnit.MINUTES);
        assertThat(result.success(), is(true));
        assertThat(result.didDeallocate(), is(true));

        ensureGreen("t0");

        ClusterHealthStatus status = client().admin().cluster().prepareHealth().setWaitForYellowStatus().setTimeout("2s").execute().actionGet().getStatus();
        assertThat(status, isOneOf(ClusterHealthStatus.GREEN, ClusterHealthStatus.YELLOW));

        awaitBusy(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                ClusterState newState = ((InternalTestCluster)cluster()).getInstance(ClusterService.class, takeDownNode.name()).state();
                return newState.getRoutingNodes().node(takeDownNode.id())
                        .shardsWithState("t0", ShardRoutingState.STARTED, ShardRoutingState.INITIALIZING, ShardRoutingState.RELOCATING).size() == 0;
            }
        }, 1000, TimeUnit.MILLISECONDS);
        deallocator.close();
    }

    @Test
    public void testDeallocateMultipleZeroReplicaIndices() throws Exception {
        createIndices();
        client().admin().indices()
                .prepareCreate("t2")
                .addMapping("default", MAPPING_SOURCE)
                .setSettings(Settings.builder().put("number_of_shards", 3).put("number_of_replicas", 0))
                .execute().actionGet();
        for (int i = 0; i<1000; i++) {
            client().prepareIndex("t2", "default")
                    .setId(String.valueOf(randomInt()))
                    .setSource(ImmutableMap.<String, Object>of("name", randomAsciiOfLength(10))).execute().actionGet();
        }
        refresh();

        PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        Deallocator.DeallocationResult result = future.get(1, TimeUnit.MINUTES);
        assertThat(result.success(), is(true));
        assertThat(result.didDeallocate(), is(true));

        ensureGreen("t0");

        assertThat(clusterService().state().getRoutingNodes().node(takeDownNode.id()).shardsWithState("t0", ShardRoutingState.STARTED, ShardRoutingState.INITIALIZING, ShardRoutingState.RELOCATING).size(), is(0));
        ClusterHealthStatus status = client().admin().cluster().prepareHealth().setWaitForYellowStatus().setTimeout("2s").execute().actionGet().getStatus();
        assertThat(status, is(isOneOf(ClusterHealthStatus.GREEN, ClusterHealthStatus.YELLOW)));
    }

    @Test
    public void testCancel() throws Exception {
        createIndices();

        final PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        assertThat(deallocator.cancel(), is(false));
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        assertThat(deallocator.isDeallocating(), is(true));
        assertThat(deallocator.cancel(), is(true));
        awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                return !deallocator.isDeallocating();
            }
        });

        assertThat(future.isDone(), is(true));
        assertThat(future.isCancelled(), is(true));
    }

    @Test
    public void testCancelAllocationEnableSetting() throws Exception {
        createIndices();

        cluster().client().admin().cluster().prepareUpdateSettings().setTransientSettings(
                Settings.builder().put(EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE, EnableAllocationDecider.Allocation.NONE.name())
        ).execute().actionGet();

        final PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        assertThat(deallocator.cancel(), is(false));
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        assertThat(deallocator.isDeallocating(), is(true));
        assertThat(deallocator.cancel(), is(true));
        awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                return !deallocator.isDeallocating();
            }
        });

        assertThat(future.isDone(), is(true));
        assertThat(future.isCancelled(), is(true));

        awaitBusy(new Predicate<Void>() {
            @Override
            public boolean apply(Void aVoid) {
                ClusterState newState = ((InternalTestCluster)cluster()).getInstance(ClusterService.class, takeDownNode.name()).state();

                return newState.metaData().settings().get(EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE, "").equals(EnableAllocationDecider.Allocation.NONE.name());
            }
        }, 100, TimeUnit.MILLISECONDS);
        deallocator.close();
    }

    @Test
    public void testDeallocateNoOps() throws Exception {
        PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());

        // NOOP
        Deallocator.DeallocationResult result = deallocator.deallocate().get(1, TimeUnit.SECONDS);
        assertThat(result.success(), is(true));
        assertThat(result.didDeallocate(), is(false));

        createIndices();

        // DOUBLE deallocation
        deallocator.deallocate();

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("node already waiting for primary only deallocation");
        deallocator.deallocate();
    }

    @Test
    public void testNotOverrideExistingSettings() throws Exception {
        createIndices();
        client().admin().indices().prepareUpdateSettings("t0").setSettings(
                Settings.builder().put(PrimariesDeallocator.EXCLUDE_NODE_ID_FROM_INDEX, "abc")
        ).execute().actionGet();

        PrimariesDeallocator deallocator = ((InternalTestCluster)cluster()).getInstance(PrimariesDeallocator.class, takeDownNode.name());
        ListenableFuture<Deallocator.DeallocationResult> future = deallocator.deallocate();
        Deallocator.DeallocationResult result = future.get(1, TimeUnit.MINUTES);
        assertThat(result.success(), is(true));
        assertThat(result.didDeallocate(), is(true));

        assertThat(deallocator.cancel(), is(true));
        ensureGreen();

        awaitBusy(new Predicate<Void>() {
            @Override
            public boolean apply(Void input) {
                return clusterService().state().metaData().index("t0").getSettings().get(PrimariesDeallocator.EXCLUDE_NODE_ID_FROM_INDEX).equals("abc");
            }
        }, 10000, TimeUnit.MILLISECONDS);
        deallocator.close();
    }

}

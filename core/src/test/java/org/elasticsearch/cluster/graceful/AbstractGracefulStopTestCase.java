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

package org.elasticsearch.cluster.graceful;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.allocation.deallocator.Deallocators;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.cluster.service.GracefulStop;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public abstract class AbstractGracefulStopTestCase extends ESIntegTestCase {

    static String MAPPING_SOURCE;

    GracefulStop gracefulStop;
    Deallocators deallocators;

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @BeforeClass
    public static void prepareClass() throws Exception {
        MAPPING_SOURCE = XContentFactory.jsonBuilder().startObject().startObject("properties")
                .startObject("name")
                .field("type", "string")
                .endObject()
                .endObject()
                .endObject().string();
    }

    @Before
    public void prepare() {
        DiscoveryNode takeDownNode = clusterService().state().nodes().dataNodes().values().iterator().next().value;
        gracefulStop = ((InternalTestCluster) cluster()).getInstance(GracefulStop.class, takeDownNode.name());
        deallocators = ((InternalTestCluster) cluster()).getInstance(Deallocators.class, takeDownNode.name());
    }

    @After
    public void cleanUp() {
        client().admin().cluster().prepareUpdateSettings()
                .setTransientSettingsToRemove(new HashSet<>(Arrays.asList(GracefulStop.SettingNames.FORCE,
                        GracefulStop.SettingNames.REALLOCATE, Deallocators.GRACEFUL_STOP_MIN_AVAILABILITY,
                        GracefulStop.SettingNames.TIMEOUT)));
        gracefulStop = null;
        deallocators = null;
    }

    protected void setSettings(boolean force, boolean reallocate, String minAvailability, String timeOut) {
        client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(Settings.builder()
                        .put(GracefulStop.SettingNames.FORCE, force)
                        .put(GracefulStop.SettingNames.REALLOCATE, reallocate)
                        .put(Deallocators.GRACEFUL_STOP_MIN_AVAILABILITY, minAvailability)
                        .put(GracefulStop.SettingNames.TIMEOUT, timeOut)).execute().actionGet();
    }

    /**
     * asserting the cluster.routing.allocation.enable setting got reset to null
     */
    private static final Predicate ALLOCATION_SETTINGS_GOT_RESET = new Predicate() {
        @Override
        public boolean apply(Object input) {
            return internalCluster().clusterService().state().metaData()
                    .settings().get(EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE) == null;
        }
    };

    protected void assertAllocationSettingsGotReset() throws Exception {
        assertTrue("'cluster.routing.allocation.enable' did not get reset", awaitBusy(ALLOCATION_SETTINGS_GOT_RESET, 5, TimeUnit.SECONDS));
    }

    protected void createIndex(int shards, int replicas, int records) {
        client().admin().indices()
                .prepareCreate("t2")
                .addMapping("default", MAPPING_SOURCE)
                .setSettings(Settings.builder().put("number_of_shards", shards).put("number_of_replicas", replicas))
                .execute().actionGet();
        for (int i = 0; i < records; i++) {
            client().prepareIndex("t2", "default")
                    .setId(String.valueOf(randomInt()))
                    .setSource(ImmutableMap.<String, Object>of("name", randomAsciiOfLength(10))).execute().actionGet();
        }
        refresh();
    }
}

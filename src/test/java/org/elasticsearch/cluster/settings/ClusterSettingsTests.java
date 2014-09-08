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

package org.elasticsearch.cluster.settings;

import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequestBuilder;
import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.common.collect.Sets;
import org.apache.lucene.store.StoreRateLimiting;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.allocation.decider.DisableAllocationDecider;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.indices.store.IndicesStore;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Test;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import java.util.Set;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.TEST;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBlocked;
import static org.hamcrest.Matchers.*;

@ClusterScope(scope = TEST)
public class ClusterSettingsTests extends ElasticsearchIntegrationTest {

    @Test
    public void clusterNonExistingSettingsUpdate() {
        String key1 = "no_idea_what_you_are_talking_about";
        int value1 = 10;

        ClusterUpdateSettingsResponse response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(ImmutableSettings.builder().put(key1, value1).build())
                .get();

        assertAcked(response);
        assertThat(response.getTransientSettings().getAsMap().entrySet(), Matchers.emptyIterable());
    }

    @Test
    public void clusterSettingsUpdateResponse() {
        String key1 = "indices.cache.filter.size";
        int value1 = 10;

        String key2 = DisableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_DISABLE_ALLOCATION;
        boolean value2 = true;

        Settings transientSettings1 = ImmutableSettings.builder().put(key1, value1).build();
        Settings persistentSettings1 = ImmutableSettings.builder().put(key2, value2).build();

        ClusterUpdateSettingsResponse response1 = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(transientSettings1)
                .setPersistentSettings(persistentSettings1)
                .execute()
                .actionGet();

        assertAcked(response1);
        assertThat(response1.getTransientSettings().get(key1), notNullValue());
        assertThat(response1.getTransientSettings().get(key2), nullValue());
        assertThat(response1.getPersistentSettings().get(key1), nullValue());
        assertThat(response1.getPersistentSettings().get(key2), notNullValue());

        Settings transientSettings2 = ImmutableSettings.builder().put(key1, value1).put(key2, value2).build();
        Settings persistentSettings2 = ImmutableSettings.EMPTY;

        ClusterUpdateSettingsResponse response2 = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(transientSettings2)
                .setPersistentSettings(persistentSettings2)
                .execute()
                .actionGet();

        assertAcked(response2);
        assertThat(response2.getTransientSettings().get(key1), notNullValue());
        assertThat(response2.getTransientSettings().get(key2), notNullValue());
        assertThat(response2.getPersistentSettings().get(key1), nullValue());
        assertThat(response2.getPersistentSettings().get(key2), nullValue());

        Settings transientSettings3 = ImmutableSettings.EMPTY;
        Settings persistentSettings3 = ImmutableSettings.builder().put(key1, value1).put(key2, value2).build();

        ClusterUpdateSettingsResponse response3 = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(transientSettings3)
                .setPersistentSettings(persistentSettings3)
                .execute()
                .actionGet();

        assertAcked(response3);
        assertThat(response3.getTransientSettings().get(key1), nullValue());
        assertThat(response3.getTransientSettings().get(key2), nullValue());
        assertThat(response3.getPersistentSettings().get(key1), notNullValue());
        assertThat(response3.getPersistentSettings().get(key2), notNullValue());
    }

    @Test
    public void testUpdateDiscoveryPublishTimeout() {

        DiscoverySettings discoverySettings = internalCluster().getInstance(DiscoverySettings.class);

        assertThat(discoverySettings.getPublishTimeout(), equalTo(DiscoverySettings.DEFAULT_PUBLISH_TIMEOUT));

        ClusterUpdateSettingsResponse response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(ImmutableSettings.builder().put(DiscoverySettings.PUBLISH_TIMEOUT, "1s").build())
                .get();

        assertAcked(response);
        assertThat(response.getTransientSettings().getAsMap().get(DiscoverySettings.PUBLISH_TIMEOUT), equalTo("1s"));
        assertThat(discoverySettings.getPublishTimeout().seconds(), equalTo(1l));

        response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(ImmutableSettings.builder().put(DiscoverySettings.PUBLISH_TIMEOUT, "whatever").build())
                .get();

        assertAcked(response);
        assertThat(response.getTransientSettings().getAsMap().entrySet(), Matchers.emptyIterable());
        assertThat(discoverySettings.getPublishTimeout().seconds(), equalTo(1l));

        response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(ImmutableSettings.builder().put(DiscoverySettings.PUBLISH_TIMEOUT, -1).build())
                .get();

        assertAcked(response);
        assertThat(response.getTransientSettings().getAsMap().entrySet(), Matchers.emptyIterable());
        assertThat(discoverySettings.getPublishTimeout().seconds(), equalTo(1l));
    }

    @Test
    public void testClusterUpdateSettingsWithBlocks() {
        String key1 = "cluster.routing.allocation.enable";
        Settings transientSettings = ImmutableSettings.builder().put(key1, false).build();

        String key2 = "cluster.routing.allocation.node_concurrent_recoveries";
        Settings persistentSettings = ImmutableSettings.builder().put(key2, "5").build();

        ClusterUpdateSettingsRequestBuilder request = client().admin().cluster().prepareUpdateSettings()
                                                                                .setTransientSettings(transientSettings)
                                                                                .setPersistentSettings(persistentSettings);

        // Cluster settings updates are blocked when the cluster is read only
        try {
            setClusterReadOnly(true);
            assertBlocked(request, MetaData.CLUSTER_READ_ONLY_BLOCK);

            // But it's possible to update the settings to update the "cluster.blocks.read_only" setting
            Settings settings = settingsBuilder().put(MetaData.SETTING_READ_ONLY, false).build();
            assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(settings).get());

        } finally {
            setClusterReadOnly(false);
        }

        // It should work now
        ClusterUpdateSettingsResponse response = request.execute().actionGet();

        assertAcked(response);
        assertThat(response.getTransientSettings().get(key1), notNullValue());
        assertThat(response.getTransientSettings().get(key2), nullValue());
        assertThat(response.getPersistentSettings().get(key1), nullValue());
        assertThat(response.getPersistentSettings().get(key2), notNullValue());
    }

    public void testResetSettings() throws Exception {
        String setting = IndicesStore.INDICES_STORE_THROTTLE_TYPE;

        Settings transientSettings = ImmutableSettings.builder().put(setting, "none").build();
        Settings persistentSettings = ImmutableSettings.builder().put(setting, "all").build();

        ClusterUpdateSettingsResponse response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettings(transientSettings)
                .setPersistentSettings(persistentSettings)
                .execute()
                .actionGet();

        assertAcked(response);
        assertThat(response.getTransientSettings().get(setting), is("none"));
        assertThat(response.getPersistentSettings().get(setting), is("all"));

        Set<String> resetSettingNames = Sets.newHashSet();
        resetSettingNames.add(setting);

        response = client().admin().cluster()
                .prepareUpdateSettings()
                .setTransientSettingsToRemove(resetSettingNames)
                .execute()
                .actionGet();

        assertAcked(response);

        Injector injector = internalCluster().getInstance(Injector.class);
        IndicesStore indicesStore = injector.getProvider(IndicesStore.class).get();
        assertThat(indicesStore.rateLimiting().getType(), is(StoreRateLimiting.Type.ALL));

        response = client().admin().cluster()
                .prepareUpdateSettings()
                .setPersistentSettingsToRemove(resetSettingNames)
                .execute()
                .actionGet();

        assertAcked(response);

        assertThat(indicesStore.rateLimiting().getType(), is(StoreRateLimiting.Type.MERGE));
    }

}

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

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.Arrays;

public abstract class AbstractDeallocatorTestCase extends ESIntegTestCase {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    protected DiscoveryNode takeDownNode;

    protected static String MAPPING_SOURCE;

    @BeforeClass
    public static void prepareClass() throws IOException {
        MAPPING_SOURCE = XContentFactory.jsonBuilder().startObject().startObject("properties")
                .startObject("name")
                .field("type", "string")
                .endObject()
                .endObject()
                .endObject().string();
    }

    @Before
    public void prepare() throws Exception {
        RoutingNode[] nodes = clusterService().state().getRoutingNodes().toArray();
        takeDownNode = nodes[randomInt(nodes.length-1)].node();
    }

    protected void createIndices() throws Exception {
        client().admin().indices()
                .prepareCreate("t0")
                .addMapping("default", MAPPING_SOURCE)
                .setSettings(Settings.builder().put("number_of_shards", 2).put("number_of_replicas", 0))
                .execute().actionGet();
        client().admin().indices()
                .prepareCreate("t1")
                .addMapping("default", MAPPING_SOURCE)
                .setSettings(Settings.builder().put("number_of_shards", 2).put("number_of_replicas", 1))
                .execute().actionGet();
        ensureGreen();

        for (String table : Arrays.asList("t0", "t1")) {
            for (int i = 0; i<10; i++) {
                client().prepareIndex(table, "default")
                        .setId(String.valueOf(randomInt()))
                        .setSource(ImmutableMap.<String, Object>of("name", randomAsciiOfLength(10))).execute().actionGet();
            }
        }
        refresh();
    }
}

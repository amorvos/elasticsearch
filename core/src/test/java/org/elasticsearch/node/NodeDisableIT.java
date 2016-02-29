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

package org.elasticsearch.node;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.hamcrest.core.Is.is;

public class NodeDisableIT extends ESIntegTestCase {

    static {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
    }

    private Node node;

    @Test
    public void testHttpDisableAndReEnable() throws Exception {
        node = NodeBuilder.nodeBuilder().local(true).data(true).settings(
                Settings.builder()
                        .put(ClusterName.SETTING, getClass().getName())
                        .put("node.name", getClass().getName())
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 0)
                        .put(EsExecutors.PROCESSORS, 1)
                        .put("http.enabled", true)
                        .put("index.store.type", "ram")
                        .put("config.ignore_system_properties", true)
                        .put("path.home", createTempDir())).build();
        node.start();
        HttpServerTransport httpServerTransport = node.injector().getInstance(HttpServerTransport.class);
        InetSocketAddress address = ((InetSocketTransportAddress) httpServerTransport.boundAddress().publishAddress()).address();

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpResponse response = new HttpRequestBuilder(httpClient)
                .host(NetworkAddress.formatAddress(address.getAddress())).port(address.getPort())
                .path("/")
                .method("GET").execute();
        assertThat(response.getStatusCode(), is(200));

        httpClient.close();
        assertThat(node.disable(), is(true));

        httpClient = HttpClients.createDefault();
        response = new HttpRequestBuilder(httpClient)
                .host(NetworkAddress.formatAddress(address.getAddress())).port(address.getPort())
                .path("/")
                .method("GET").execute();
        assertThat(response.getStatusCode(), is(503));

        node.start();

        response = new HttpRequestBuilder(httpClient)
                .host(NetworkAddress.formatAddress(address.getAddress())).port(address.getPort())
                .path("/")
                .method("GET").execute();
        assertThat(response.getStatusCode(), is(200));
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        node.close();
        node = null;
    }
}

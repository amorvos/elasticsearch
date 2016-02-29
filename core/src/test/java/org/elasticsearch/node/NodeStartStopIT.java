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

import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Test;

import java.util.Locale;

public class NodeStartStopIT extends ESIntegTestCase {

    static {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
    }

    private Node node;

    @Test
    public void testFastStartAndStop() throws Exception {
        // assert that no exception is thrown when starting and stopping the
        // internal node in parallel etc.
        for (int i = 0; i < 10; i++) {
            String name = String.format(Locale.ENGLISH, "%s_%d", getClass().getName(), System.currentTimeMillis());
            node = NodeBuilder.nodeBuilder().local(true).data(true).settings(
                    Settings.builder()
                            .put(ClusterName.SETTING, name)
                            .put("node.name", name)
                            .put("http.enabled", true)
                            .put("config.ignore_system_properties", true)
                            .put("path.home", createTempDir())).build();
            Thread startThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    node.start();
                }
            });
            Thread stopThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    node.stop();
                }
            });
            startThread.start();
            stopThread.start();
            startThread.join();
            stopThread.join();
            node.close();
            node = null;
        }
    }
}

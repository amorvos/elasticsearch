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

package org.elasticsearch.cloud.azure;

import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.azure.management.network.models.*;
import com.microsoft.azure.utility.AuthHelper;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import org.elasticsearch.cloud.azure.management.AzureComputeServiceAbstractMock;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.plugins.Plugin;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock Azure API with a single started node
 */
public class AzureComputeServiceSimpleMock extends AzureComputeServiceAbstractMock {

    public static class TestPlugin extends Plugin {
        @Override
        public String name() {
            return "mock-compute-service";
        }

        @Override
        public String description() {
            return "plugs in a mock compute service for testing";
        }

        public void onModule(AzureModule azureModule) {
            azureModule.computeServiceImpl = AzureComputeServiceSimpleMock.class;
        }
    }

    static final class Azure {
        private static final String ENDPOINT = "https://management.core.windows.net/";
        private static final String AUTH_ENDPOINT = "https://login.windows.net/";
    }


    @Inject
    public AzureComputeServiceSimpleMock(Settings settings) {
        super(settings);
    }

    @Override
    public Configuration getConfiguration() {
        try {
            AuthenticationResult authRes = AuthHelper.getAccessTokenFromServicePrincipalCredentials(
                    Azure.ENDPOINT,
                    Azure.AUTH_ENDPOINT,
                    settings.get(Management.TENANT_ID),
                    settings.get(Management.APP_ID),
                    settings.get(Management.APP_SECRET));
            return ManagementConfiguration.configure(
                    null,
                    (URI) null,
                    settings.get(Management.SUBSCRIPTION_ID), // subscription id
                    authRes.getAccessToken()
            );
        } catch (Exception e) {
            logger.error("can not start azure client: {}", e.getMessage());

        }
        return null;
    }
}

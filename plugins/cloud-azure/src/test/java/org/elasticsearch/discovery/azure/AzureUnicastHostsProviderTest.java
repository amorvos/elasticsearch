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

package org.elasticsearch.discovery.azure;

import com.microsoft.azure.management.network.*;
import com.microsoft.azure.management.network.models.*;
import com.microsoft.windowsazure.exception.ServiceException;
import org.elasticsearch.cloud.azure.AbstractAzureComputeServiceTestCase;
import org.elasticsearch.cloud.azure.AzureComputeServiceSimpleMock;
import org.elasticsearch.cloud.azure.management.AzureComputeService.Discovery;
import org.elasticsearch.cloud.azure.management.AzureComputeService.Management;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST,
        numDataNodes = 0,
        transportClientRatio = 0.0,
        numClientNodes = 0)
public class AzureUnicastHostsProviderTest {

    @Test
    public void testSingleSubnet() throws IOException, ServiceException {

        String rgName = "rgName";

        Subnet subnet = new Subnet();

        ResourceId resourceId = new ResourceId();
        resourceId.setId("/subscriptions/xx/resourceGroups/rgName/providers/Microsoft.Network/networkInterfaces/nic_dummy/ipConfigurations/Nic-IP-config");

        subnet.setIpConfigurations(CollectionUtils.asArrayList(resourceId));
        subnet.setName("mySubnet");

        NetworkResourceProviderClient providerClient = mock(NetworkResourceProviderClient.class);
        VirtualNetworkOperations virtualNetworkOperations = mock(VirtualNetworkOperationsImpl.class);
        VirtualNetworkGetResponse virtualNetworkGetResponse = mock(VirtualNetworkGetResponse.class);
        NetworkInterfaceOperations networkInterfaceOperations = mock(NetworkInterfaceOperationsImpl.class);
        NetworkInterfaceGetResponse networkInterfaceGetResponse = mock(NetworkInterfaceGetResponse.class);
        NetworkInterfaceIpConfiguration ipConfiguration = new NetworkInterfaceIpConfiguration();
        ipConfiguration.setPrivateIpAddress("10.0.0.4");

        NetworkInterface nic = new NetworkInterface();
        nic.setName("nic_dummy");
        nic.setIpConfigurations(CollectionUtils.asArrayList(ipConfiguration));

        VirtualNetwork virtualNetwork = new VirtualNetwork();
        virtualNetwork.setSubnets(CollectionUtils.asArrayList(subnet));

        when(virtualNetworkGetResponse.getVirtualNetwork()).thenReturn(virtualNetwork);


        when(providerClient.getVirtualNetworksOperations()).thenReturn(virtualNetworkOperations);
        when(virtualNetworkOperations.get("rgName", "vnetName")).thenReturn(virtualNetworkGetResponse);

        when(providerClient.getNetworkInterfacesOperations()).thenReturn(networkInterfaceOperations);
        when(networkInterfaceOperations.get("rgName", "nic_dummy")).thenReturn(networkInterfaceGetResponse);
        when(networkInterfaceGetResponse.getNetworkInterface()).thenReturn(nic);

        List<String> networkAddresses = AzureUnicastHostsProvider.listIPAddresses(providerClient, rgName, "vnetName", "", "vnet", AzureUnicastHostsProvider.HostType.PRIVATE_IP, null);
        assertEquals(networkAddresses.size(), 1);
    }

}

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

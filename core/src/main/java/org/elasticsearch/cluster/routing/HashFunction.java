package org.elasticsearch.cluster.routing;

public interface HashFunction {

    int hashRouting(String routing);
}

package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;


public interface ClusteringPolicy {

    
    String policyVersion();

    
    Map<String, List<ClusterMember>> cluster(List<ClusterMember> members);
}

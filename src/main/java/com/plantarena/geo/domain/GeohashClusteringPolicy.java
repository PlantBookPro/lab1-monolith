package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;


public final class GeohashClusteringPolicy implements ClusteringPolicy {

    private final int precision;

    public GeohashClusteringPolicy(int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Точность geohash 1–12: " + precision);
        }
        this.precision = precision;
    }

    @Override
    public String policyVersion() {
        return "geohash-v1-p" + precision;
    }

    @Override
    public Map<String, List<ClusterMember>> cluster(List<ClusterMember> members) {
        return members.stream().collect(Collectors.groupingBy(
            member -> Geohash.encode(member.latitude(), member.longitude(), precision),
            TreeMap::new, Collectors.toList()));
    }
}

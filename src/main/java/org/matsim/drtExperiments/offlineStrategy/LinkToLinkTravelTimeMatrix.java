package org.matsim.drtExperiments.offlineStrategy;

import one.util.streamex.EntryStream;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.zone.Zone;
import org.matsim.contrib.zone.skims.Matrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.contrib.zone.skims.TravelTimeMatrix;
import org.matsim.core.router.util.TravelTime;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.matsim.contrib.dvrp.path.VrpPaths.FIRST_LINK_TT;

/**
 * Link to link travel time to be used by the offline solver *
 */
public class LinkToLinkTravelTimeMatrix {
    private final TravelTimeMatrix nodeToNodeTravelTimeMatrix;
    private final TravelTime travelTime;
    private final Network network;

    LinkToLinkTravelTimeMatrix(Network network, TravelTime travelTime, Set<Id<Link>> relevantLinks, double time) {
        this.network = network;
        this.travelTime = travelTime;
        this.nodeToNodeTravelTimeMatrix = calculateTravelTimeMatrix(relevantLinks, time);
    }

    public double getTravelTime(Link fromLink, Link toLink, double departureTime) {
        if (fromLink.getId().toString().equals(toLink.getId().toString())) {
            return 0;
        }
        double travelTimeFromNodeToNode = nodeToNodeTravelTimeMatrix.getTravelTime(fromLink.getToNode(), toLink.getFromNode(), departureTime);
        return FIRST_LINK_TT + travelTimeFromNodeToNode
                + VrpPaths.getLastLinkTT(travelTime, toLink, departureTime + travelTimeFromNodeToNode);
    }

    private TravelTimeMatrix calculateTravelTimeMatrix(Set<Id<Link>> relevantLinks, double time) {
        Map<Node, Zone> zoneByNode = relevantLinks
                .stream()
                .flatMap(linkId -> Stream.of(network.getLinks().get(linkId).getFromNode(), network.getLinks().get(linkId).getToNode()))
                .collect(toMap(n -> n, node -> new Zone(Id.create(node.getId(), Zone.class), "node", node.getCoord()),
                        (zone1, zone2) -> zone1));
        var nodeByZone = EntryStream.of(zoneByNode).invert().toMap();
        Matrix nodeToNodeMatrix = TravelTimeMatrices.calculateTravelTimeMatrix(network, nodeByZone, time, travelTime,
                new TimeAsTravelDisutility(travelTime), Runtime.getRuntime().availableProcessors());

        return (fromNode, toNode, departureTime) -> nodeToNodeMatrix.get(zoneByNode.get(fromNode), zoneByNode.get(toNode));
    }
}

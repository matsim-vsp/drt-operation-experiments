package org.matsim.drtExperiments.basicStructures;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.passenger.DrtRequest;

public record GeneralRequest(Id<Person> passengerId, Id<Link> fromLinkId, Id<Link> toLinkId,
                      double earliestStartTime, double latestStartTime, double latestArrivalTime) {
}


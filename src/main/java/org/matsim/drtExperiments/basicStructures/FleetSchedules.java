package org.matsim.drtExperiments.basicStructures;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.List;
import java.util.Map;

public record FleetSchedules(
        Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMap,
        Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMap,
        List<Id<Person>> rejectedRequests) {
}

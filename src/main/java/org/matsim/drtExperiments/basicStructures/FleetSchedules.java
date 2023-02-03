package org.matsim.drtExperiments.basicStructures;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.*;

public record FleetSchedules(
        Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMap,
        Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMap,
        Map<Id<Person>, GeneralRequest> rejectedRequests) {

    public FleetSchedules copySchedule() {
        Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMapCopy = new HashMap<>();
        for (Id<DvrpVehicle> vehicleId : this.vehicleToTimetableMap().keySet()) {
            vehicleToTimetableMapCopy.put(vehicleId, copyTimetable(this.vehicleToTimetableMap.get(vehicleId)));
        }
        Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMapCopy = new HashMap<>(this.requestIdToVehicleMap);
        Map<Id<Person>, GeneralRequest> rejectedRequestsCopy = new HashMap<>(this.rejectedRequests);

        return new FleetSchedules(vehicleToTimetableMapCopy, requestIdToVehicleMapCopy, rejectedRequestsCopy);
    }

    public static List<TimetableEntry> copyTimetable(List<TimetableEntry> timetable) {
        List<TimetableEntry> timetableCopy = new ArrayList<>();
        for (TimetableEntry timetableEntry : timetable) {
            timetableCopy.add(new TimetableEntry(timetableEntry));
        }
        return timetableCopy;
    }
}

package org.matsim.drtExperiments.offlineStrategy;

import com.google.common.base.Preconditions;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.core.router.util.TravelTime;
import org.matsim.drtExperiments.basicStructures.FleetSchedules;
import org.matsim.drtExperiments.basicStructures.GeneralRequest;
import org.matsim.drtExperiments.basicStructures.OnlineVehicleInfo;
import org.matsim.drtExperiments.basicStructures.TimetableEntry;

import java.util.*;

/**
 * The parallel insertion strategy with regression heuristic *
 */
public class OfflineSolverRegretHeuristic implements OfflineSolver {
    private final Network network;
    private final TravelTime travelTime;
    private final double stopDuration;

    public OfflineSolverRegretHeuristic(Network network, TravelTime travelTime, DrtConfigGroup drtConfigGroup) {
        this.network = network;
        this.travelTime = travelTime;
        this.stopDuration = drtConfigGroup.stopDuration;
    }

    @Override
    public FleetSchedules calculate(FleetSchedules previousSchedules, Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap, List<GeneralRequest> newRequests, double time) {
        // Initialize fleet schedule when it is null
        if (previousSchedules == null) {
            Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMap = new HashMap<>();
            for (OnlineVehicleInfo vehicleInfo : onlineVehicleInfoMap.values()) {
                vehicleToTimetableMap.put(vehicleInfo.vehicle().getId(), new ArrayList<>());
            }
            Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMap = new HashMap<>();
            Map<Id<Person>, GeneralRequest> rejectedRequests = new HashMap<>();
            previousSchedules = new FleetSchedules(vehicleToTimetableMap, requestIdToVehicleMap, rejectedRequests);
        }

        if (newRequests.isEmpty()) {
            return previousSchedules;
        }

        // Prepare link to link travel time matrix based on all relevant locations (links)
        LinkToLinkTravelTimeMatrix linkToLinkTravelTimeMatrix = prepareLinkToLinkTravelMatrix(previousSchedules, onlineVehicleInfoMap, newRequests, time);

        // Update the schedule to the current situation (e.g., errors caused by those 1s differences; traffic situation...)
        updateFleetSchedule(previousSchedules, onlineVehicleInfoMap, linkToLinkTravelTimeMatrix);

        // Create insertion calculator
        InsertionCalculator insertionCalculator = new InsertionCalculator(network, travelTime, stopDuration, linkToLinkTravelTimeMatrix);

        // Perform regret insertion
        return performRegretInsertion(insertionCalculator, previousSchedules, onlineVehicleInfoMap, newRequests);
    }

    public LinkToLinkTravelTimeMatrix prepareLinkToLinkTravelMatrix(FleetSchedules previousSchedules,
                                                                    Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap, List<GeneralRequest> newRequests,
                                                                    double time) {
        Set<Id<Link>> relevantLinks = new HashSet<>();
        // Vehicle locations
        for (OnlineVehicleInfo onlineVehicleInfo : onlineVehicleInfoMap.values()) {
            relevantLinks.add(onlineVehicleInfo.currentLink().getId());
        }
        // Requests locations
        // open requests on timetable
        for (List<TimetableEntry> timetable : previousSchedules.vehicleToTimetableMap().values()) {
            for (TimetableEntry timetableEntry : timetable) {
                if (timetableEntry.getStopType() == TimetableEntry.StopType.PICKUP) {
                    relevantLinks.add(timetableEntry.getRequest().fromLinkId());
                } else {
                    relevantLinks.add(timetableEntry.getRequest().toLinkId());
                }
            }
        }
        // new requests
        for (GeneralRequest request : newRequests) {
            relevantLinks.add(request.fromLinkId());
            relevantLinks.add(request.toLinkId());
        }
        return new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, time);
    }

    public FleetSchedules performRegretInsertion(InsertionCalculator insertionCalculator, FleetSchedules previousSchedules,
                                                 Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap, List<GeneralRequest> newRequests) {
        Preconditions.checkArgument(!newRequests.isEmpty(), "There is no new request to insert!");
        // Initialize the matrix (LinkedHashMap is used to preserved order of the matrix -> reproducible results even if there are plans with same max regret/score)
        Map<GeneralRequest, Map<OnlineVehicleInfo, InsertionCalculator.InsertionData>> insertionMatrix = new LinkedHashMap<>();
        for (GeneralRequest request : newRequests) {
            insertionMatrix.put(request, new LinkedHashMap<>());
            for (OnlineVehicleInfo vehicleInfo : onlineVehicleInfoMap.values()) {
                InsertionCalculator.InsertionData insertionData = insertionCalculator.computeInsertionData(vehicleInfo, request, previousSchedules);
                insertionMatrix.get(request).put(vehicleInfo, insertionData);
            }
        }

        // Insert each request recursively
        boolean finished = false;
        while (!finished) {
            // Get the request with the highest regret and insert it to the best vehicle
            double largestRegret = -1;
            GeneralRequest requestWithLargestRegret = null;
            for (GeneralRequest request : insertionMatrix.keySet()) {
                double regret = getRegret(request, insertionMatrix);
                if (regret > largestRegret) {
                    largestRegret = regret;
                    requestWithLargestRegret = request;
                }
            }

            assert requestWithLargestRegret != null;
            InsertionCalculator.InsertionData bestInsertionData = getBestInsertionForRequest(requestWithLargestRegret, insertionMatrix);

            if (bestInsertionData.cost() < InsertionCalculator.NOT_FEASIBLE_COST) {
                // Formally insert the request to the timetable
                previousSchedules.requestIdToVehicleMap().put(requestWithLargestRegret.passengerId(), bestInsertionData.vehicleInfo().vehicle().getId());
                previousSchedules.vehicleToTimetableMap().put(bestInsertionData.vehicleInfo().vehicle().getId(), bestInsertionData.candidateTimetable());

                // Remove the request from the insertion matrix
                insertionMatrix.remove(requestWithLargestRegret);

                // Update insertion data for the rest of the request and the selected vehicle
                for (GeneralRequest request : insertionMatrix.keySet()) {
                    InsertionCalculator.InsertionData updatedInsertionData = insertionCalculator.computeInsertionData(bestInsertionData.vehicleInfo(), request, previousSchedules);
                    insertionMatrix.get(request).put(bestInsertionData.vehicleInfo(), updatedInsertionData);
                }
            } else {
                // The best insertion is already infeasible. Reject this request
                previousSchedules.rejectedRequests().put(requestWithLargestRegret.passengerId(), requestWithLargestRegret);
                // Remove the request from the insertion matrix
                insertionMatrix.remove(requestWithLargestRegret);
            }
            finished = insertionMatrix.isEmpty();
        }
        return previousSchedules;
    }


    // private methods
    private double getRegret(GeneralRequest request, Map<GeneralRequest, Map<OnlineVehicleInfo, InsertionCalculator.InsertionData>> insertionMatrix) {
        List<InsertionCalculator.InsertionData> insertionDataList = new ArrayList<>(insertionMatrix.get(request).values());
        insertionDataList.sort(Comparator.comparingDouble(InsertionCalculator.InsertionData::cost));
        return insertionDataList.get(1).cost() + insertionDataList.get(2).cost() - 2 * insertionDataList.get(0).cost();
        //  regret-3 is used here. It can also be switched to regret-2, regret-4, regret-5 ... regret-q
    }

    private InsertionCalculator.InsertionData getBestInsertionForRequest(
            GeneralRequest request, Map<GeneralRequest, Map<OnlineVehicleInfo, InsertionCalculator.InsertionData>> insertionMatrix) {
        double minInsertionCost = Double.MAX_VALUE;
        InsertionCalculator.InsertionData bestInsertion = null;
        for (InsertionCalculator.InsertionData insertionData : insertionMatrix.get(request).values()) {
            if (insertionData.cost() < minInsertionCost) {
                minInsertionCost = insertionData.cost();
                bestInsertion = insertionData;
            }
        }
        return bestInsertion;
    }

    // TODO duplicated code from here, consider re-structure
    private void updateFleetSchedule(FleetSchedules previousSchedules,
                                     Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap,
                                     LinkToLinkTravelTimeMatrix linkToLinkTravelTimeMatrix) {
        for (Id<DvrpVehicle> vehicleId : onlineVehicleInfoMap.keySet()) {
            previousSchedules.vehicleToTimetableMap().computeIfAbsent(vehicleId, t -> new ArrayList<>()); // When new vehicle enters service, create a new entry for it
            if (!onlineVehicleInfoMap.containsKey(vehicleId)) {
                previousSchedules.vehicleToTimetableMap().remove(vehicleId); // When a vehicle ends service, remove it from the schedule
            }
        }

        for (Id<DvrpVehicle> vehicleId : previousSchedules.vehicleToTimetableMap().keySet()) {
            List<TimetableEntry> timetable = previousSchedules.vehicleToTimetableMap().get(vehicleId);
            if (!timetable.isEmpty()) {
                Link currentLink = onlineVehicleInfoMap.get(vehicleId).currentLink();
                double currentTime = onlineVehicleInfoMap.get(vehicleId).divertableTime();
                for (TimetableEntry timetableEntry : timetable) {
                    Id<Link> stopLinkId = timetableEntry.getStopType() == TimetableEntry.StopType.PICKUP ? timetableEntry.getRequest().fromLinkId() : timetableEntry.getRequest().toLinkId();
                    Link stopLink = network.getLinks().get(stopLinkId);
                    double newArrivalTime = currentTime + linkToLinkTravelTimeMatrix.getTravelTime(currentLink, stopLink, currentTime);
                    timetableEntry.updateArrivalTime(newArrivalTime);
                    currentTime = timetableEntry.getDepartureTime();
                    currentLink = stopLink;
                }
            }
        }
    }
}

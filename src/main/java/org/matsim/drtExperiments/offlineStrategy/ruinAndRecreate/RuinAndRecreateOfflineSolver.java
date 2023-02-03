package org.matsim.drtExperiments.offlineStrategy.ruinAndRecreate;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.core.router.util.TravelTime;
import org.matsim.drtExperiments.basicStructures.FleetSchedules;
import org.matsim.drtExperiments.basicStructures.GeneralRequest;
import org.matsim.drtExperiments.basicStructures.OnlineVehicleInfo;
import org.matsim.drtExperiments.basicStructures.TimetableEntry;
import org.matsim.drtExperiments.offlineStrategy.InsertionCalculator;
import org.matsim.drtExperiments.offlineStrategy.LinkToLinkTravelTimeMatrix;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolver;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolverRegretHeuristic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RuinAndRecreateOfflineSolver implements OfflineSolver {
    private final int maxIterations;
    private final Network network;
    private final TravelTime travelTime;
    private final DrtConfigGroup drtConfigGroup;

    public RuinAndRecreateOfflineSolver(int maxIterations, Network network, TravelTime travelTime, DrtConfigGroup drtConfigGroup) {
        this.maxIterations = maxIterations;
        this.network = network;
        this.travelTime = travelTime;
        this.drtConfigGroup = drtConfigGroup;
    }

    @Override
    public FleetSchedules calculate(FleetSchedules previousSchedules,
                                    Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap, List<GeneralRequest> newRequests,
                                    double time) {
        // Initialize all the necessary objects
        RecreateSolutionAcceptor solutionAcceptor = new SimpleAnnealingThresholdAcceptor();
        RuinSelector ruinSelector = new RandomRuinSelector();
        SolutionCostCalculator solutionCostCalculator = new DefaultSolutionCostCalculator();

        // Initialize regret inserter
        OfflineSolverRegretHeuristic regretInserter = new OfflineSolverRegretHeuristic(network, travelTime, drtConfigGroup);
        LinkToLinkTravelTimeMatrix linkToLinkTravelTimeMatrix = regretInserter.prepareLinkToLinkTravelMatrix(previousSchedules, onlineVehicleInfoMap, newRequests, time);
        // TODO consider optimize this structure. prepare link to link travel time matrix may be moved to another place

        // Update the schedule to the current situation (e.g., errors caused by those 1s differences; traffic situation...)
        updateFleetSchedule(previousSchedules, onlineVehicleInfoMap, linkToLinkTravelTimeMatrix);

        // Create insertion calculator
        InsertionCalculator insertionCalculator = new InsertionCalculator(network, travelTime, drtConfigGroup.stopDuration, linkToLinkTravelTimeMatrix);

        // Calculate initial solution
        FleetSchedules initialSolution = regretInserter.performRegretInsertion(insertionCalculator, previousSchedules, onlineVehicleInfoMap, newRequests);
        double initialScore = solutionCostCalculator.calculateSolutionCost(initialSolution, time);

        // Initialize the best solution (set to initial solution)
        FleetSchedules currentBestSolution = initialSolution;
        double currentBestScore = initialScore;

        // Initialize the fall back solution (set to the initial solution)
        FleetSchedules currentSolution = initialSolution;
        double currentScore = initialScore;

        for (int i = 0; i < maxIterations; i++) {
            // Create a copy of current solution
            FleetSchedules newSolution = currentSolution.copySchedule();

            // Ruin the plan by removing some requests from the schedule
            Set<GeneralRequest> requestsToRemove = ruinSelector.selectRequestsToBeRuined(newSolution);
            for (GeneralRequest request : requestsToRemove) {
                Id<DvrpVehicle> vehicleId = newSolution.requestIdToVehicleMap().get(request.passengerId());
                insertionCalculator.removeRequestFromSchedule(onlineVehicleInfoMap.get(vehicleId), request, newSolution);
            }

            // Recreate: try to re-insert all the removed requests, along with rejected requests, back into the schedule
            // TODO consider change the list to set in the input argument
            regretInserter.performRegretInsertion(insertionCalculator, newSolution, onlineVehicleInfoMap, (List<GeneralRequest>) newSolution.rejectedRequests().values());

            // Score the new solution
            double newScore = solutionCostCalculator.calculateSolutionCost(newSolution, time);
            if (solutionAcceptor.acceptSolutionOrNot(newScore, currentScore, i, maxIterations)) {
                currentSolution = newSolution;
                currentScore = newScore;
                if (newScore < currentBestScore) {
                    currentBestScore = newScore;
                    currentBestSolution = newSolution;
                }
            }
        }

        return currentBestSolution;
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

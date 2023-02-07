package org.matsim.drtExperiments.offlineStrategy.ruinAndRecreate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.matsim.drtExperiments.offlineStrategy.InsertionCalculator;
import org.matsim.drtExperiments.offlineStrategy.LinkToLinkTravelTimeMatrix;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolver;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolverRegretHeuristic;

import java.util.*;

public class RuinAndRecreateOfflineSolver implements OfflineSolver {
    private final int maxIterations;
    private final Network network;
    private final TravelTime travelTime;
    private final DrtConfigGroup drtConfigGroup;
    private static final Logger log = LogManager.getLogger(RuinAndRecreateOfflineSolver.class);

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

        // Initialize all the necessary objects
        RecreateSolutionAcceptor solutionAcceptor = new SimpleAnnealingThresholdAcceptor();
        RuinSelector ruinSelector = new RandomRuinSelector();
        SolutionCostCalculator solutionCostCalculator = new DefaultSolutionCostCalculator();

        // Initialize regret inserter
        OfflineSolverRegretHeuristic regretInserter = new OfflineSolverRegretHeuristic(network, travelTime, drtConfigGroup);
        LinkToLinkTravelTimeMatrix linkToLinkTravelTimeMatrix = regretInserter.prepareLinkToLinkTravelMatrix(previousSchedules, onlineVehicleInfoMap, newRequests, time);
        // TODO consider optimize this structure. "prepare link to link travel time matrix" may be moved to another place

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

        int displayCounter = 1;
        for (int i = 1; i < maxIterations + 1; i++) {
            // Create a copy of current solution
            FleetSchedules newSolution = currentSolution.copySchedule();

            // Ruin the plan by removing some requests from the schedule
            Set<GeneralRequest> requestsToRemove = ruinSelector.selectRequestsToBeRuined(newSolution);
            if (requestsToRemove.size() <= 1) {
                log.info("There is only 1 open requests to remove. It does not make sense to further iterate.");
                break;
            }

            for (GeneralRequest request : requestsToRemove) {
                Id<DvrpVehicle> vehicleId = newSolution.requestIdToVehicleMap().get(request.passengerId());
                insertionCalculator.removeRequestFromSchedule(onlineVehicleInfoMap.get(vehicleId), request, newSolution);
            }

            // Recreate: try to re-insert all the removed requests, along with rejected requests, back into the schedule
            // TODO the value set of the map does not preserve the order -> potential different result each time
            List<GeneralRequest> requestsToReinsert = new ArrayList<>(newSolution.rejectedRequests().values());
            newSolution.rejectedRequests().clear();
            newSolution = regretInserter.performRegretInsertion(insertionCalculator, newSolution, onlineVehicleInfoMap, requestsToReinsert);

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

            if (i % displayCounter == 0) {
                log.info("Ruin and Recreate iterations #" + i + ": Current score = " + currentScore + ", newScore score = " + newScore + ", accepted = " + solutionAcceptor.acceptSolutionOrNot(newScore, currentScore, i, maxIterations) + ", current best score = " + currentBestScore);
                displayCounter *= 2;
            }

        }
        log.info(maxIterations + " ruin and Recreate iterations complete!");

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

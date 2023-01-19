package org.matsim.drtExperiments.offlineStrategy;

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

import static java.util.stream.Collectors.toMap;

public class OfflineSolverSeqInsertion implements OfflineSolver {
    private final Network network;
    private final TravelTime travelTime;
    private final double stopDuration;

    public OfflineSolverSeqInsertion(Network network, TravelTime travelTime, DrtConfigGroup drtConfigGroup) {
        this.network = network;
        this.travelTime = travelTime;
        this.stopDuration = drtConfigGroup.stopDuration;
    }

    @Override
    public FleetSchedules calculate(FleetSchedules previousSchedules,
                                    Map<Id<DvrpVehicle>, OnlineVehicleInfo> onlineVehicleInfoMap,
                                    List<GeneralRequest> newRequests, double time) {
        if (previousSchedules == null) {
            Map<Id<DvrpVehicle>, List<TimetableEntry>> vehicleToTimetableMap = new HashMap<>();
            for (OnlineVehicleInfo vehicleInfo : onlineVehicleInfoMap.values()) {
                vehicleToTimetableMap.put(vehicleInfo.vehicle().getId(), new ArrayList<>());
            }
            Map<Id<Person>, Id<DvrpVehicle>> requestIdToVehicleMap = new HashMap<>();
            List<Id<Person>> rejectedRequests = new ArrayList<>();
            previousSchedules = new FleetSchedules(vehicleToTimetableMap, requestIdToVehicleMap, rejectedRequests);
        }

        if (newRequests.isEmpty()) {
            return previousSchedules;
        }

        // Prepare link to link travel time matrix based on all relevant locations (links)
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
        LinkToLinkTravelTimeMatrix linkToLinkTravelTimeMatrix = new LinkToLinkTravelTimeMatrix(network, travelTime, relevantLinks, time);

        // Update the schedule to the current situation (e.g., errors caused by those 1s differences; traffic situation...)
        updateFleetSchedule(previousSchedules, onlineVehicleInfoMap, linkToLinkTravelTimeMatrix);

        // Perform insertion
        for (GeneralRequest request : newRequests) {
            Link fromLink = network.getLinks().get(request.fromLinkId());
            Link toLink = network.getLinks().get(request.toLinkId());

            // Try to find the best insertion
            double bestInsertionCost = Double.MAX_VALUE;
            DvrpVehicle selectedVehicle = null;
            List<TimetableEntry> updatedTimetable = null;

            for (Id<DvrpVehicle> vehicleId : previousSchedules.vehicleToTimetableMap().keySet()) {
                OnlineVehicleInfo vehicleInfo = onlineVehicleInfoMap.get(vehicleId);
                Link currentLink = vehicleInfo.currentLink();
                double divertableTime = vehicleInfo.divertableTime();
                double serviceEndTime = vehicleInfo.vehicle().getServiceEndTime() - stopDuration; // the last stop must start on or before this time step
                List<TimetableEntry> originalTimetable = previousSchedules.vehicleToTimetableMap().get(vehicleId);

                // 1. if timetable is empty
                if (originalTimetable.isEmpty()) {
                    double timeToPickup = linkToLinkTravelTimeMatrix.getTravelTime(currentLink, fromLink, divertableTime);
                    double arrivalTimePickUp = divertableTime + timeToPickup;
                    double departureTimePickUp = Math.max(request.earliestStartTime(), arrivalTimePickUp) + stopDuration;
                    double tripTravelTime = linkToLinkTravelTimeMatrix.getTravelTime(fromLink, toLink, departureTimePickUp);
                    double arrivalTimeDropOff = departureTimePickUp + tripTravelTime;
                    double totalInsertionCost = timeToPickup + tripTravelTime;
                    if (arrivalTimePickUp > request.latestStartTime() || arrivalTimePickUp > serviceEndTime) {
                        continue;
                    }

                    if (totalInsertionCost < bestInsertionCost) {
                        bestInsertionCost = totalInsertionCost;
                        selectedVehicle = vehicleInfo.vehicle();
                        updatedTimetable = new ArrayList<>();
                        updatedTimetable.add(new TimetableEntry(request, TimetableEntry.StopType.PICKUP, arrivalTimePickUp, departureTimePickUp, 0, stopDuration, selectedVehicle));
                        updatedTimetable.add(new TimetableEntry(request, TimetableEntry.StopType.DROP_OFF, arrivalTimeDropOff, arrivalTimeDropOff + stopDuration, 1, stopDuration, selectedVehicle));
                        // Note: The departure time of the last stop is actually not meaningful, but this stop may become non-last stop later, therefore, we set the departure time of this stop as if it is a middle stop
                    }
                    continue;
                }

                // 2. If original timetable is non-empty
                for (int i = 0; i < originalTimetable.size() + 1; i++) {
                    double pickUpInsertionCost;
                    List<TimetableEntry> temporaryTimetable;

                    // Insert pickup
                    if (i < originalTimetable.size()) {
                        if (originalTimetable.get(i).isVehicleFullBeforeThisStop()) {
                            continue;
                        }
                        double detourA;
                        double arrivalTimePickUpStop;
                        Link linkOfStopBeforePickUpInsertion;
                        double departureTimeOfStopBeforePickUpInsertion;
                        if (i == 0) { // insert pickup before the first stop
                            linkOfStopBeforePickUpInsertion = currentLink; // no stop before pickup insertion --> use current location of the vehicle
                            departureTimeOfStopBeforePickUpInsertion = divertableTime; // no stop before pickup insertion --> use divertable time of the vehicle
                            detourA = linkToLinkTravelTimeMatrix.getTravelTime(currentLink, fromLink, divertableTime);
                            arrivalTimePickUpStop = divertableTime + detourA;
                        } else {
                            TimetableEntry stopBeforePickUpInsertion = originalTimetable.get(i - 1);
                            linkOfStopBeforePickUpInsertion = network.getLinks().get(stopBeforePickUpInsertion.getLinkId());
                            departureTimeOfStopBeforePickUpInsertion = stopBeforePickUpInsertion.getDepartureTime();
                            detourA = linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforePickUpInsertion, fromLink, stopBeforePickUpInsertion.getDepartureTime());
                            arrivalTimePickUpStop = departureTimeOfStopBeforePickUpInsertion + detourA;
                        }
                        if (arrivalTimePickUpStop > request.latestStartTime() || arrivalTimePickUpStop > serviceEndTime) {
                            break; // Vehicle can no longer reach the pickup location in time. No need to continue with this vehicle
                        }
                        double departureTimePickUpStop = Math.max(arrivalTimePickUpStop, request.earliestStartTime()) + stopDuration;
                        TimetableEntry stopAfterPickUpInsertion = originalTimetable.get(i);
                        Link linkOfStopAfterPickUpInsertion = network.getLinks().get(stopAfterPickUpInsertion.getLinkId());
                        double detourB = linkToLinkTravelTimeMatrix.getTravelTime(fromLink, linkOfStopAfterPickUpInsertion, departureTimePickUpStop);
                        double newArrivalTimeOfNextStop = departureTimePickUpStop + detourB;
                        double delayCausedByInsertingPickUp = newArrivalTimeOfNextStop - stopAfterPickUpInsertion.getArrivalTime();
                        if (isInsertionNotFeasible(originalTimetable, i, delayCausedByInsertingPickUp, serviceEndTime)) {
                            continue;
                        }
                        pickUpInsertionCost = detourA + detourB - linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforePickUpInsertion, linkOfStopAfterPickUpInsertion, departureTimeOfStopBeforePickUpInsertion);
                        TimetableEntry pickupStopToInsert = new TimetableEntry(request, TimetableEntry.StopType.PICKUP,
                                arrivalTimePickUpStop, departureTimePickUpStop, stopAfterPickUpInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle());
                        temporaryTimetable = insertPickup(originalTimetable, i, pickupStopToInsert, delayCausedByInsertingPickUp);
                    } else { // Append pickup at the end
                        TimetableEntry stopBeforePickUpInsertion = originalTimetable.get(i - 1);
                        Link linkOfStopBeforePickUpInsertion = network.getLinks().get(stopBeforePickUpInsertion.getLinkId());
                        double departureTimeOfStopBeforePickUpInsertion = stopBeforePickUpInsertion.getDepartureTime();
                        double travelTimeToPickUp = linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforePickUpInsertion, fromLink, departureTimeOfStopBeforePickUpInsertion);
                        double arrivalTimePickUpStop = travelTimeToPickUp + departureTimeOfStopBeforePickUpInsertion;
                        if (arrivalTimePickUpStop > request.latestStartTime() || arrivalTimePickUpStop > serviceEndTime) {
                            break;
                        }
                        double departureTimePickUpStop = Math.max(arrivalTimePickUpStop, request.earliestStartTime()) + stopDuration;
                        pickUpInsertionCost = travelTimeToPickUp;
                        TimetableEntry pickupStopToInsert = new TimetableEntry(request, TimetableEntry.StopType.PICKUP,
                                arrivalTimePickUpStop, departureTimePickUpStop, 0, stopDuration, vehicleInfo.vehicle());
                        temporaryTimetable = insertPickup(originalTimetable, i, pickupStopToInsert, 0); //Appending pickup at the end will not cause any delay to the original timetable
                    }

                    // Insert drop off
                    for (int j = i + 1; j < temporaryTimetable.size() + 1; j++) {
                        // Check occupancy feasibility
                        if (temporaryTimetable.get(j - 1).isVehicleOverloaded()) {
                            break; // If the stop before the drop-off insertion is overloaded, then it is not feasible to insert drop off at or after current location
                        }

                        TimetableEntry stopBeforeDropOffInsertion = temporaryTimetable.get(j - 1);
                        Link linkOfStopBeforeDropOffInsertion = network.getLinks().get(stopBeforeDropOffInsertion.getLinkId());
                        double departureTimeOfStopBeforeDropOffInsertion = stopBeforeDropOffInsertion.getDepartureTime();
                        if (j < temporaryTimetable.size()) { // Insert drop off between two stops
                            double detourC = linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforeDropOffInsertion, toLink, departureTimeOfStopBeforeDropOffInsertion);
                            double arrivalTimeDropOffStop = departureTimeOfStopBeforeDropOffInsertion + detourC;
                            if (arrivalTimeDropOffStop > request.latestArrivalTime() || arrivalTimeDropOffStop > serviceEndTime) {
                                break;
                            }
                            double departureTimeDropOffStop = arrivalTimeDropOffStop + stopDuration;
                            TimetableEntry stopAfterDropOffInsertion = temporaryTimetable.get(j);
                            Link linkOfStopAfterDropOffInsertion = network.getLinks().get(stopAfterDropOffInsertion.getLinkId());
                            double detourD = linkToLinkTravelTimeMatrix.getTravelTime(toLink, linkOfStopAfterDropOffInsertion, departureTimeDropOffStop);
                            double newArrivalTimeOfStopAfterDropOffInsertion = departureTimeDropOffStop + detourD;
                            double delayCausedByDropOffInsertion = newArrivalTimeOfStopAfterDropOffInsertion - stopAfterDropOffInsertion.getArrivalTime();
                            if (isInsertionNotFeasible(temporaryTimetable, j, delayCausedByDropOffInsertion, serviceEndTime)) {
                                continue;
                            }
                            double dropOffInsertionCost = detourC + detourD - linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforeDropOffInsertion, linkOfStopAfterDropOffInsertion, departureTimeOfStopBeforeDropOffInsertion);
                            double totalInsertionCost = dropOffInsertionCost + pickUpInsertionCost;
                            if (totalInsertionCost < bestInsertionCost) {
                                bestInsertionCost = totalInsertionCost;
                                selectedVehicle = vehicleInfo.vehicle();
                                TimetableEntry dropOffStopToInsert = new TimetableEntry(request, TimetableEntry.StopType.DROP_OFF,
                                        arrivalTimeDropOffStop, departureTimeDropOffStop, stopAfterDropOffInsertion.getOccupancyBeforeStop(), stopDuration, vehicleInfo.vehicle()); //Attention: currently, the occupancy before next stop is already increased!
                                updatedTimetable = insertDropOff(temporaryTimetable, j, dropOffStopToInsert, delayCausedByDropOffInsertion);
                            }
                        } else { // Append drop off at the end
                            double travelTimeToDropOffStop = linkToLinkTravelTimeMatrix.getTravelTime(linkOfStopBeforeDropOffInsertion, toLink, departureTimeOfStopBeforeDropOffInsertion);
                            double arrivalTimeDropOffStop = departureTimeOfStopBeforeDropOffInsertion + travelTimeToDropOffStop;
                            if (arrivalTimeDropOffStop > request.latestArrivalTime() || arrivalTimeDropOffStop > serviceEndTime) {
                                continue;
                            }
                            double totalInsertionCost = pickUpInsertionCost + travelTimeToDropOffStop;
                            double departureTimeDropOffStop = arrivalTimeDropOffStop + stopDuration;
                            if (totalInsertionCost < bestInsertionCost) {
                                bestInsertionCost = totalInsertionCost;
                                selectedVehicle = vehicleInfo.vehicle();
                                TimetableEntry dropOffStopToInsert = new TimetableEntry(request, TimetableEntry.StopType.DROP_OFF,
                                        arrivalTimeDropOffStop, departureTimeDropOffStop, 1, stopDuration, vehicleInfo.vehicle());
                                updatedTimetable = insertDropOff(temporaryTimetable, j, dropOffStopToInsert, 0); // Append at the end --> no delay to other stops
                            }
                        }
                    }
                }
            }

            if (selectedVehicle == null) {
                previousSchedules.rejectedRequests().add(request.passengerId());
            } else {
                previousSchedules.vehicleToTimetableMap().put(selectedVehicle.getId(), updatedTimetable);
                previousSchedules.requestIdToVehicleMap().put(request.passengerId(), selectedVehicle.getId());
            }
        }
        return previousSchedules;
    }

    // Private methods
    private boolean isInsertionNotFeasible(List<TimetableEntry> originalTimetable, int insertionIdx, double delay, double serviceEndTime) {
        for (int i = insertionIdx; i < originalTimetable.size(); i++) {
            TimetableEntry stop = originalTimetable.get(i);
            double newArrivalTime = stop.getArrivalTime() + delay;
            if (stop.isTimeConstraintViolated(delay) || newArrivalTime > serviceEndTime) {
                return true;
            }
            delay = stop.getEffectiveDelayIfStopIsDelayedBy(delay); // Update the delay after this stop (as stop time of some stops may be squeezed)
            if (delay <= 0) {
                return false; // The delay becomes 0, then there will be no impact on the following stops --> feasible (not feasible = false)
            }
        }
        return false; // If we reach here, then every stop is feasible (not feasible = false)
    }

    private List<TimetableEntry> insertPickup(List<TimetableEntry> originalTimetable, int pickUpIdx,
                                              TimetableEntry stopToInsert, double delay) {
        // Create a copy of the original timetable (and copy each object inside)
        // Note: Delay includes the pickup time
        List<TimetableEntry> temporaryTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : originalTimetable) {
            temporaryTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (pickUpIdx < temporaryTimetable.size()) {
            temporaryTimetable.add(pickUpIdx, stopToInsert);
            for (int i = pickUpIdx + 1; i < temporaryTimetable.size(); i++) {
                double effectiveDelay = temporaryTimetable.get(i).getEffectiveDelayIfStopIsDelayedBy(delay);
                temporaryTimetable.get(i).delayTheStopBy(delay);
                temporaryTimetable.get(i).addPickupBeforeTheStop();
                delay = effectiveDelay; // Update the delay carry over to the next stop
            }
        } else {
            temporaryTimetable.add(stopToInsert); // insert at the end
        }
        return temporaryTimetable;
    }

    private List<TimetableEntry> insertDropOff(List<TimetableEntry> temporaryTimetable, int dropOffIdx,
                                               TimetableEntry stopToInsert, double delay) {
        // Note: Delay includes the Drop-off time
        List<TimetableEntry> candidateTimetable = new ArrayList<>();
        for (TimetableEntry timetableEntry : temporaryTimetable) {
            candidateTimetable.add(new TimetableEntry(timetableEntry));
        }

        if (dropOffIdx < candidateTimetable.size()) {
            candidateTimetable.add(dropOffIdx, stopToInsert);
            for (int i = dropOffIdx + 1; i < candidateTimetable.size(); i++) {
                double effectiveDelay = candidateTimetable.get(i).getEffectiveDelayIfStopIsDelayedBy(delay);
                candidateTimetable.get(i).delayTheStopBy(delay);
                candidateTimetable.get(i).addDropOffBeforeTheStop();
                delay = effectiveDelay; // Update the delay carry over to the next stop
            }
        } else {
            candidateTimetable.add(stopToInsert); // insert at the end
        }
        return candidateTimetable;
    }

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

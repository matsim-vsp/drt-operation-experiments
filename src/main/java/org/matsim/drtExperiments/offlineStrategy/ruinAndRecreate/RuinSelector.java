package org.matsim.drtExperiments.offlineStrategy.ruinAndRecreate;

import org.matsim.drtExperiments.basicStructures.FleetSchedules;
import org.matsim.drtExperiments.basicStructures.GeneralRequest;

import java.util.Set;

public interface RuinSelector {

    Set<GeneralRequest> selectRequestsToBeRuined(FleetSchedules fleetSchedules);
}

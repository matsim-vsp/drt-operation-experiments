package org.matsim.drtExperiments.run.drt_constraint_study.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.util.DrtEventsReaders;
import org.matsim.contrib.dvrp.passenger.*;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.application.ApplicationUtils.globFile;

public class DetourAnalysis {
    public static void main(String[] args) throws IOException {
        Path outputDirectory = Path.of(args[0]);
        DetourAnalysisEventsHandler detourAnalysis = new DetourAnalysisEventsHandler(outputDirectory);
        detourAnalysis.readEvents();
        detourAnalysis.writeAnalysis();
    }

//    public static void readEvents(DetourAnalysisEventsHandler detourAnalysis){
//        Path outputEventsPath = globFile(detourAnalysis.getOutputDirectory(), "*output_events.xml.gz*");
//        EventsManager eventManager = EventsUtils.createEventsManager();
//        eventManager.addHandler(detourAnalysis);
//        eventManager.initProcessing();
//        MatsimEventsReader matsimEventsReader = DrtEventsReaders.createEventsReader(eventManager);
//        matsimEventsReader.readFile(outputEventsPath.toString());
//    }

    public static class DetourAnalysisEventsHandler implements PassengerRequestSubmittedEventHandler,
            PassengerRequestScheduledEventHandler, PassengerRequestRejectedEventHandler, PassengerPickedUpEventHandler,
            PassengerDroppedOffEventHandler {
        private final Path outputDirectory;
        private final Network network;
        private final TravelTime travelTime;
        private final LeastCostPathCalculator router;
        private final Map<Id<Person>, Double> submissionTimeMap = new LinkedHashMap<>();
        private final Map<Id<Person>, Double> directTripTimeMap = new HashMap<>();
        private final Map<Id<Person>, Double> scheduledPickupTimeMap = new HashMap<>();
        private final Map<Id<Person>, Double> actualPickupTimeMap = new HashMap<>();
        private final Map<Id<Person>, Double> arrivalTimeMap = new HashMap<>();
        private final List<Id<Person>> rejectedPersons = new ArrayList<>();
        private final Map<Id<Person>, Double> waitTimeMap = new HashMap<>();

        public DetourAnalysisEventsHandler(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
            Path networkPath = globFile(outputDirectory, "*output_network.xml.gz*");
            this.network = NetworkUtils.readNetwork(networkPath.toString());
            this.travelTime = new QSimFreeSpeedTravelTime(1);
            this.router = new SpeedyALTFactory().createPathCalculator(network, new TimeAsTravelDisutility(travelTime), travelTime);
        }

        @Override
        public void handleEvent(PassengerRequestSubmittedEvent event) {
            double submissionTime = event.getTime();
            double directTripTime = VrpPaths.calcAndCreatePath
                    (network.getLinks().get(event.getFromLinkId()), network.getLinks().get(event.getToLinkId()),
                            submissionTime, router, travelTime).getTravelTime();
            submissionTimeMap.put(event.getPersonIds().get(0), submissionTime);
            directTripTimeMap.put(event.getPersonIds().get(0), directTripTime);
        }

        @Override
        public void handleEvent(PassengerRequestScheduledEvent event) {
            double scheduledPickupTime = Math.ceil(event.getPickupTime());
            scheduledPickupTimeMap.put(event.getPersonIds().get(0), scheduledPickupTime);
        }

        @Override
        public void handleEvent(PassengerRequestRejectedEvent event) {
            rejectedPersons.add(event.getPersonIds().get(0));
        }

        @Override
        public void handleEvent(PassengerPickedUpEvent event) {
            double actualPickupTime = event.getTime();
            actualPickupTimeMap.put(event.getPersonId(), actualPickupTime);
            double waitTime = actualPickupTime - submissionTimeMap.get(event.getPersonId());
            waitTimeMap.put(event.getPersonId(), waitTime);
        }

        @Override
        public void handleEvent(PassengerDroppedOffEvent event) {
            double arrivalTime = event.getTime();
            arrivalTimeMap.put(event.getPersonId(), arrivalTime);
        }

        @Override
        public void reset(int iteration) {
            PassengerRequestScheduledEventHandler.super.reset(iteration);
        }

        public void writeAnalysis() throws IOException {
            String detourAnalysisOutput = outputDirectory.toString() + "/detour-analysis.tsv";
            CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(detourAnalysisOutput), CSVFormat.TDF);
            csvPrinter.printRecord(Arrays.asList("person_id", "submission", "scheduled_pickup", "actual_pickup",
                    "arrival", "direct_trip_duration", "total_wait_time", "delay_since_scheduled_pickup",
                    "actual_ride_duration", "total_travel_time"));

            for (Id<Person> personId : submissionTimeMap.keySet()) {
                if (rejectedPersons.contains(personId)) {
                    continue;
                }

                double submissionTime = submissionTimeMap.get(personId);
                double scheduledPickupTime = scheduledPickupTimeMap.get(personId);
                double actualPickupTime = actualPickupTimeMap.get(personId);
                double arrivalTime = arrivalTimeMap.get(personId);
                double directTripDuration = directTripTimeMap.get(personId);

                double waitTime = actualPickupTime - submissionTime;
                double delay = actualPickupTime - scheduledPickupTime;
                double actualRideDuration = arrivalTime - actualPickupTime;
                double actualTotalTravelTime = arrivalTime - submissionTime;

                csvPrinter.printRecord(Arrays.asList(
                        personId.toString(),
                        Double.toString(submissionTime),
                        Double.toString(scheduledPickupTime),
                        Double.toString(actualPickupTime),
                        Double.toString(arrivalTime),
                        Double.toString(directTripDuration),
                        Double.toString(waitTime),
                        Double.toString(delay),
                        Double.toString(actualRideDuration),
                        Double.toString(actualTotalTravelTime)
                ));
            }
            csvPrinter.close();
        }

        public List<Id<Person>> getRejectedPersons() {
            return rejectedPersons;
        }

        public void readEvents() {
            Path outputEventsPath = globFile(outputDirectory, "*output_events.xml.gz*");
            EventsManager eventManager = EventsUtils.createEventsManager();
            eventManager.addHandler(this);
            eventManager.initProcessing();
            MatsimEventsReader matsimEventsReader = DrtEventsReaders.createEventsReader(eventManager);
            matsimEventsReader.readFile(outputEventsPath.toString());
        }

        public double get95pctWaitTime() {
            List<Double> waitingTimes = waitTimeMap.values().stream().sorted().toList();
            int idx = (int) Math.min(Math.ceil(waitingTimes.size() * 0.95), waitingTimes.size() - 1);
            return waitingTimes.get(idx);
        }
    }
}

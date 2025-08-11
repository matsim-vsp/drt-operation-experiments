package org.matsim.drtExperiments.run.drt_constraint_study.experiments;

import org.apache.commons.io.FileUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.passenger.DrtOfferAcceptor;
import org.matsim.contrib.drt.passenger.MaxDetourOfferAcceptor;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.drtExperiments.run.drt_constraint_study.analysis.DetourAnalysis;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RunOnlineFleetSizing implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private Path configPath;

    @CommandLine.Option(names = "--output", description = "root output folder", required = true)
    private String output;

    @CommandLine.Option(names = "--fleet-size", description = "fleet size from", defaultValue = "250")
    private int fleetSize;

    @CommandLine.Option(names = "--steps", description = "maximum number of runs", defaultValue = "50")
    private int steps;

    @CommandLine.Option(names = "--step-size", description = "number of vehicles increased for each step", defaultValue = "5")
    private int stepSizeStageOne;

    @CommandLine.Option(names = "--step-size-2", description = "number of vehicles increased for each step", defaultValue = "1")
    private int stepSizeStageTwo;

    @CommandLine.Option(names = "--seats", description = "fleet size", defaultValue = "8")
    private int seats;

    @CommandLine.Option(names = "--wait-time", description = "max wait time", defaultValue = "300")
    private double maxWaitTime;

    @CommandLine.Option(names = "--detour-alpha", description = "max detour alpha", defaultValue = "1.5")
    private double detourAlpha;

    @CommandLine.Option(names = "--detour-beta", description = "max detour beta", defaultValue = "300")
    private double detourBeta;

    @CommandLine.Option(names = "--pickup-delay", description = "max pickup delay", defaultValue = "120")
    private double maxPickupDelay;

    @CommandLine.Option(names = "--max-abs-detour", description = "max pickup delay", defaultValue = "7200")
    private double maxAbsDetour;

    @CommandLine.Option(names = "--allow-rejection", description = "allow rejection")
    private boolean allowRejection;

    @CommandLine.Option(names = "--stop-duration", description = "max pickup delay", defaultValue = "60")
    private double stopDuration;

    public static void main(String[] args) {
        new RunOnlineFleetSizing().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        // Create a temporary config file in the same folder, so that multiple runs can be run in the cluster at the same time
        String temporaryConfig = createTemporaryConfig(configPath, output);

        // Stage 1
        int maxFleetSize = fleetSize + stepSizeStageOne * (steps - 1);
        while (fleetSize <= maxFleetSize) {
            String outputDirectory = runSimulation(temporaryConfig);
            boolean requirementFulfilled = analyzeResult(outputDirectory);
            if (requirementFulfilled) {
                break;
            }
            fleetSize += stepSizeStageOne;
        }

        // Stage 2
        if (fleetSize < maxFleetSize && stepSizeStageTwo < stepSizeStageOne && steps > 1) {
            // Move back to previous step (large step) and move forward for one small step
            fleetSize = fleetSize - stepSizeStageOne + stepSizeStageTwo;
            while (true) {
                String outputDirectory = runSimulation(temporaryConfig);
                boolean requirementFulfilled = analyzeResult(outputDirectory);
                if (requirementFulfilled) {
                    break;
                }
                fleetSize += stepSizeStageTwo;
            }
        }

        // Delete the temporary config file for the current run
        Files.delete(Path.of(temporaryConfig));
        return 0;
    }

    private String runSimulation(String temporaryConfig) {
        String outputDirectory = output + "/fleet-size-" + fleetSize;
        Config config = ConfigUtils.loadConfig(temporaryConfig, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        config.controller().setOutputDirectory(outputDirectory);

        // Assume single DRT operator
        DrtConfigGroup drtConfigGroup = DrtConfigGroup.getSingleModeDrtConfig(config);
        drtConfigGroup.vehiclesFile = "drt-vehicles/" + fleetSize + "-" + seats + "_seater-drt-vehicles.xml";

        drtConfigGroup.maxAllowedPickupDelay = maxPickupDelay;
        drtConfigGroup.maxDetourAlpha = detourAlpha;
        drtConfigGroup.maxDetourBeta = detourBeta;
        drtConfigGroup.maxAbsoluteDetour = maxAbsDetour;

        drtConfigGroup.maxWaitTime = maxWaitTime;

        drtConfigGroup.maxTravelTimeAlpha = 10;
        drtConfigGroup.maxTravelTimeBeta = 7200;

        drtConfigGroup.rejectRequestIfMaxWaitOrTravelTimeViolated = allowRejection;
        drtConfigGroup.stopDuration = stopDuration;

        Controler controler = DrtControlerCreator.createControler(config, false);
        controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.mode) {
            @Override
            protected void configureQSim() {
                bindModal(DrtOfferAcceptor.class).toProvider(modalProvider(getter ->
                        new MaxDetourOfferAcceptor(drtConfigGroup.maxAllowedPickupDelay)));
            }
        });

        controler.run();
        return outputDirectory;
    }


    private String createTemporaryConfig(Path configPath, String output) throws IOException {
        int taskId = (int) (System.currentTimeMillis() / 1000);
        File originalConfig = new File(configPath.toString());
        String temporaryConfig = configPath.getParent().toString() + "/temporary_" + taskId + ".config.xml";
        File copy = new File(temporaryConfig);
        try {
            FileUtils.copyFile(originalConfig, copy);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!Files.exists(Path.of(output))) {
            Files.createDirectory(Path.of(output));
        }
        return temporaryConfig;
    }

    private boolean analyzeResult(String outputDirectory) throws IOException {
        Path outputDirectoryPath = Path.of(outputDirectory);
        DetourAnalysis.DetourAnalysisEventsHandler analysisEventsHandler =
                new DetourAnalysis.DetourAnalysisEventsHandler(outputDirectoryPath);
        analysisEventsHandler.readEvents();
        analysisEventsHandler.writeAnalysis();
        if (allowRejection) {
            return analysisEventsHandler.getRejectedPersons().size() == 0;
        }
        return analysisEventsHandler.get95pctWaitTime() <= maxWaitTime;
    }
}

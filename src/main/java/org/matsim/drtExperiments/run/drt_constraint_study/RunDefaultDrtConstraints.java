package org.matsim.drtExperiments.run.drt_constraint_study;

import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.passenger.DrtOfferAcceptor;
import org.matsim.contrib.drt.passenger.MaxDetourOfferAcceptor;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import picocli.CommandLine;

public class RunDefaultDrtConstraints implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputDirectory;

    public static void main(String[] args) {
        new RunDefaultDrtConstraints().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        config.controller().setOutputDirectory(outputDirectory);
        MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
        for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
            drtConfigGroup.maxWaitTime = 600;
            drtConfigGroup.maxTravelTimeAlpha = 1.5;
            drtConfigGroup.maxTravelTimeBeta = 900;
        }

        Controler controler = DrtControlerCreator.createControler(config, false);
        controler.run();

        return 0;
    }
}

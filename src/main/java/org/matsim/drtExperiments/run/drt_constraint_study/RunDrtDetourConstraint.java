package org.matsim.drtExperiments.run.drt_constraint_study;

import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.passenger.DrtOfferAcceptor;
import org.matsim.contrib.drt.passenger.MaxDetourOfferAcceptor;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import picocli.CommandLine;

public class RunDrtDetourConstraint implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private String configPath;

    @CommandLine.Option(names = "--output", description = "path to output directory", required = true)
    private String outputDirectory;

    public static void main(String[] args) {
        new RunDrtDetourConstraint().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        config.controller().setOutputDirectory(outputDirectory);
        MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
        for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
            drtConfigGroup.maxAllowedPickupDelay= 120;
            drtConfigGroup.maxDetourAlpha = 1.0;
            drtConfigGroup.maxDetourBeta = 720;
            drtConfigGroup.maxAbsoluteDetour = 7200;

            drtConfigGroup.maxWaitTime = 1200;

            drtConfigGroup.maxTravelTimeAlpha = 10;
            drtConfigGroup.maxTravelTimeBeta = 7200;

            drtConfigGroup.rejectRequestIfMaxWaitOrTravelTimeViolated = false;
        }

        Controler controler = DrtControlerCreator.createControler(config, false);

        for (DrtConfigGroup drtCfg : multiModeDrtConfigGroup.getModalElements()) {
            controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtCfg.mode) {
                @Override
                protected void configureQSim() {
                    bindModal(DrtOfferAcceptor.class).toProvider(modalProvider(getter ->
                            new MaxDetourOfferAcceptor(drtCfg.maxAllowedPickupDelay)));
                }
            });
        }
        controler.run();

        return 0;
    }
}

package org.matsim.drtExperiments.run.modules;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.optimizer.QSimScopeForkJoinPoolHolder;
import org.matsim.contrib.drt.optimizer.VehicleDataEntryFactoryImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.zone.skims.TravelTimeMatrix;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.drtExperiments.basicStructures.OnlineAndOfflineDrtOptimizer;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolver;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolverJsprit;
import org.matsim.drtExperiments.offlineStrategy.OfflineSolverSeqInsertion;
import org.matsim.drtExperiments.onlineStrategy.OnlineSolver;
import org.matsim.drtExperiments.onlineStrategy.OnlineSolverBasicInsertionStrategy;

import java.util.Random;

public class OnlineAndOfflineDrtOperationModule extends AbstractDvrpModeQSimModule {
    private final Population prebookedPlans;
    private final DrtConfigGroup drtConfigGroup;
    private final double horizon;
    private final double interval;
    private final int maxIteration;
    private final boolean multiThread;
    private final long seed;
    private final OfflineSolverType offlineSolverType;

    public OnlineAndOfflineDrtOperationModule(Population prebookedPlans, DrtConfigGroup drtConfigGroup, double horizon,
                                              double interval, int maxIterations, boolean multiThread, long seed, OfflineSolverType type) {
        super(drtConfigGroup.getMode());
        this.prebookedPlans = prebookedPlans;
        this.drtConfigGroup = drtConfigGroup;
        this.horizon = horizon;
        this.interval = interval;
        this.maxIteration = maxIterations;
        this.multiThread = multiThread;
        this.seed = seed;
        this.offlineSolverType = type;
    }

    public enum OfflineSolverType {JSPRIT, SEQ_INSERTION}

    @Override
    protected void configureQSim() {
        addModalComponent(DrtOptimizer.class, this.modalProvider((getter) -> new OnlineAndOfflineDrtOptimizer(getter.getModal(Network.class), getter.getModal(TravelTime.class),
                getter.get(MobsimTimer.class), getter.getModal(DrtTaskFactory.class),
                getter.get(EventsManager.class), getter.getModal(ScheduleTimingUpdater.class),
                getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)),
                drtConfigGroup, getter.getModal(Fleet.class),
                getter.getModal(QSimScopeForkJoinPoolHolder.class).getPool(),
                getter.getModal(VehicleEntry.EntryFactory.class),
                getter.getModal(OfflineSolver.class),
                getter.getModal(OnlineSolver.class),
                getter.get(Population.class), horizon, interval, prebookedPlans)));

        bindModal(OnlineSolver.class).toProvider(modalProvider(
                getter -> new OnlineSolverBasicInsertionStrategy(getter.getModal(Network.class), drtConfigGroup,
                        getter.getModal(TravelTimeMatrix.class), getter.getModal(TravelTime.class),
                        getter.getModal(TravelDisutilityFactory.class).createTravelDisutility(getter.getModal(TravelTime.class)))));

        switch (offlineSolverType) {
            case JSPRIT -> bindModal(OfflineSolver.class).toProvider(modalProvider(
                    getter -> new OfflineSolverJsprit(
                            new OfflineSolverJsprit.Options(maxIteration, multiThread, new Random(seed)),
                            drtConfigGroup, getter.getModal(Network.class), getter.getModal(TravelTime.class))));
            case SEQ_INSERTION -> bindModal(OfflineSolver.class).toProvider(modalProvider(
                    getter -> new OfflineSolverSeqInsertion(
                            getter.getModal(Network.class), getter.getModal(TravelTime.class), drtConfigGroup)));
            default -> throw new RuntimeException("The solver is not implemented!");
        }

        addModalComponent(QSimScopeForkJoinPoolHolder.class,
                () -> new QSimScopeForkJoinPoolHolder(drtConfigGroup.numberOfThreads));
        bindModal(VehicleEntry.EntryFactory.class).toInstance(new VehicleDataEntryFactoryImpl(drtConfigGroup));

    }
}

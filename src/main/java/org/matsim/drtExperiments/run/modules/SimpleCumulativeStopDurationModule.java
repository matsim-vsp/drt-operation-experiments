package org.matsim.drtExperiments.run.modules;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.CumulativeStopTimeCalculator;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.StaticPassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

public class SimpleCumulativeStopDurationModule extends AbstractDvrpModeModule {
    private final DrtConfigGroup drtConfigGroup;

    public SimpleCumulativeStopDurationModule(DrtConfigGroup drtConfigGroup) {
        super(drtConfigGroup.getMode());
        this.drtConfigGroup = drtConfigGroup;
    }

    @Override
    public void install() {
        PassengerStopDurationProvider stopDurationProvider = StaticPassengerStopDurationProvider.of(drtConfigGroup.stopDuration, drtConfigGroup.stopDuration);
        bindModal(StopTimeCalculator.class).toInstance(new CumulativeStopTimeCalculator(stopDurationProvider));
    }
}

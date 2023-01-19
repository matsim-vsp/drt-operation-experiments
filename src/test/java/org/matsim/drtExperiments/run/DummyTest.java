package org.matsim.drtExperiments.run;

import org.junit.Rule;
import org.junit.Test;
import org.matsim.testcases.MatsimTestUtils;

public class DummyTest {
    @Rule
    public MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    public final void testDrtDoorToDoor() {
        System.out.println("Starting dummy test");
        double x = 1;
        double y = 2;
        double z = x + y;
        assert x + y == z : "some thing is wrong!!!";
    }
}

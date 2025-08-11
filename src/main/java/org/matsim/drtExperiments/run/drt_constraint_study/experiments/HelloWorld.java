package org.matsim.drtExperiments.run.drt_constraint_study.experiments;

import java.util.Arrays;
import java.util.List;

public class HelloWorld {
    public static void main(String[] args) {
        List<Double> list = Arrays.asList(10., 4., 2.);
        List<Double> sortedList = list.stream().sorted().toList();
        System.out.println(sortedList);

    }
}

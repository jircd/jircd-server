/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.integration;

import java.util.Arrays;

/** Summary statistics for {@link CapabilityFanoutLoadTest}'s per-round samples. */
final class FanoutStats {

    private FanoutStats() {}

    /** The median of {@code values}; the mean of the two middle values for an even count. */
    static double median(double... values) {
        if (values.length == 0) throw new IllegalArgumentException("no values");
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /**
     * The coefficient of variation in percent: the sample standard deviation over the mean. It is 0
     * for fewer than two values.
     */
    static double cvPercent(double... values) {
        if (values.length < 2) return 0;
        double mean = Arrays.stream(values).average().orElseThrow();
        if (mean == 0) return 0;
        double squares = 0;
        for (double value : values) squares += (value - mean) * (value - mean);
        return 100 * Math.sqrt(squares / (values.length - 1)) / Math.abs(mean);
    }

    /** The {@code fraction} quantile (nearest rank) of ascending {@code sortedNanos}, in ms. */
    static double quantileMs(long[] sortedNanos, double fraction) {
        int rank = (int) Math.ceil(sortedNanos.length * fraction);
        return sortedNanos[Math.max(rank, 1) - 1] / 1e6;
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FanoutStatsTest {

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(FanoutStats.median(9, 1, 5)).isEqualTo(5.0);
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheMiddleTwo() {
        assertThat(FanoutStats.median(4, 1, 2, 10)).isEqualTo(3.0);
    }

    @Test
    void medianIgnoresAnOutlierAndLeavesTheInputUnsorted() {
        double[] values = {100, 1, 2, 3, 2};
        assertThat(FanoutStats.median(values)).isEqualTo(2.0);
        assertThat(values).containsExactly(100, 1, 2, 3, 2);
    }

    @Test
    void medianOfNothingIsRejected() {
        assertThatThrownBy(FanoutStats::median).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void budgetMaxWithNoSkipCountsRoundZero() {
        // a later phase's round 0 spike must fail the budget
        assertThat(FanoutStats.budgetMaxMs(0, 1372, 363, 331)).isEqualTo(1372.0);
        assertThat(FanoutStats.budgetMaxMs(0, 5)).isEqualTo(5.0);
    }

    @Test
    void budgetMaxIgnoresExactlyTheFirstNRounds() {
        assertThat(FanoutStats.budgetMaxMs(1, 1372, 363, 331, 125, 284)).isEqualTo(363.0);
        assertThat(FanoutStats.budgetMaxMs(3, 1862, 800, 598, 395, 494, 305)).isEqualTo(494.0);
        // round N, the first one not skipped, counts
        assertThat(FanoutStats.budgetMaxMs(2, 1862, 800, 598, 90)).isEqualTo(598.0);
    }

    @Test
    void budgetMaxStillCountsALaterRoundAfterTheSkippedOnes() {
        assertThat(FanoutStats.budgetMaxMs(2, 10, 20, 1200, 125)).isEqualTo(1200.0);
    }

    @Test
    void budgetMaxLeavingNoRoundIsRejected() {
        assertThatThrownBy(() -> FanoutStats.budgetMaxMs(1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FanoutStats.budgetMaxMs(3, 5, 6, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FanoutStats.budgetMaxMs(4, 5, 6, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FanoutStats.budgetMaxMs(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void budgetMaxNegativeSkipIsRejected() {
        assertThatThrownBy(() -> FanoutStats.budgetMaxMs(-1, 5, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cvIsTheSampleStandardDeviationOverTheMean() {
        // mean 5, sample variance 32 / 7, so sd = 2.138...
        assertThat(FanoutStats.cvPercent(2, 4, 4, 4, 5, 5, 7, 9))
                .isCloseTo(
                        100 * Math.sqrt(32.0 / 7) / 5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void cvOfIdenticalOrTooFewValuesIsZero() {
        assertThat(FanoutStats.cvPercent(3, 3, 3)).isZero();
        assertThat(FanoutStats.cvPercent(3)).isZero();
        assertThat(FanoutStats.cvPercent()).isZero();
    }

    @Test
    void quantileUsesTheNearestRank() {
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) nanos[i] = (i + 1) * 1_000_000L;
        assertThat(FanoutStats.quantileMs(nanos, 0.99)).isEqualTo(99.0);
        assertThat(FanoutStats.quantileMs(nanos, 0.5)).isEqualTo(50.0);
        assertThat(FanoutStats.quantileMs(new long[] {7_000_000L}, 0.99)).isEqualTo(7.0);
    }
}

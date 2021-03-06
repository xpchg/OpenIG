/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openig.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.util.time.Duration.duration;
import static org.mockito.Mockito.mock;

import org.forgerock.util.time.Duration;
import org.forgerock.util.time.TimeService;
import org.testng.annotations.Test;


public class TokenBucketTest {

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void shouldNotBePossibleToInstantiateWithUnlimitedDuration() throws Exception {
        new TokenBucket(mock(TimeService.class), 1, duration("unlimited"));
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void shouldNotBePossibleToInstantiateWithDurationLessThan1Ms() throws Exception {
        new TokenBucket(mock(TimeService.class), 1, duration("3 nanoseconds"));
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void shouldNotBePossibleToInstantiateWithNegativeCapacity() throws Exception {
        new TokenBucket(mock(TimeService.class), -1, mock(Duration.class));
    }

    @Test
    public void shouldTheBucketBeRefilled() throws Exception {
        FakeTimeService time = new FakeTimeService(0);

        // a token bucket that can refill 1 token every 333.333 ms.
        TokenBucket bucket = new TokenBucket(time, 3, duration("1 second"));

        assertThat(bucket.tryConsume()).isEqualTo(0); // First time, so we can consume a token
        assertThat(bucket.getRemainingTokensCount()).isEqualTo(2);

        time.advance(1);
        assertThat(bucket.tryConsume()).isEqualTo(0); // Second time < 1s, so we can consume a token
        assertThat(bucket.getRemainingTokensCount()).isEqualTo(1);

        time.advance(1);
        assertThat(bucket.tryConsume()).isEqualTo(0); // Third time < 1s, so we can consume a token
        assertThat(bucket.getRemainingTokensCount()).isEqualTo(0);

        time.advance(1);
        assertThat(bucket.tryConsume()).isEqualTo(330); // Not enough elapsed time to get a refill

        time.advance(330);
        assertThat(bucket.tryConsume()).isEqualTo(0); // Enough elapsed time to get a refill
    }

    @Test
    public void shouldTheRefillRateBeLessThan1Millisecond() throws Exception {
        FakeTimeService time = new FakeTimeService(0);

        // a token bucket that can refill 1 token every 0.333 ms.
        TokenBucket bucket = new TokenBucket(time, 3000, duration("1 second"));

        assertThat(bucket.tryConsume()).isEqualTo(0); // First time, so we can consume a token
        assertThat(bucket.getRemainingTokensCount()).isEqualTo(2999);

        time.advance(1);
        assertThat(bucket.tryConsume()).isEqualTo(0); // Second time < 1s, so we can consume a token
        assertThat(bucket.getRemainingTokensCount()).isEqualTo(2999);
    }

}

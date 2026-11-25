/*
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.antibruteforce.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.time.Instant;

import org.junit.Test;

public class FailedLoginStateTest {

  private static final Instant TIME = Instant.parse("2026-11-24T10:43:04Z");

  @Test
  public void latestIsTheFirstWhenTheSecondIsMissing() {
    FailedLoginState state = new FailedLoginState(3, TIME);
    assertSame(state, FailedLoginState.latest(state, null));
  }

  @Test
  public void latestIsTheLaterFailure() {
    FailedLoginState older = new FailedLoginState(7, TIME);
    FailedLoginState newer = new FailedLoginState(1, TIME.plusMillis(1));
    assertSame(newer, FailedLoginState.latest(older, newer));
    assertSame(newer, FailedLoginState.latest(newer, older));
  }

  @Test
  public void latestIsTheHigherCountForTheSameFailureTime() {
    FailedLoginState lower = new FailedLoginState(5, TIME);
    FailedLoginState higher = new FailedLoginState(6, TIME);
    assertSame(higher, FailedLoginState.latest(lower, higher));
    assertSame(higher, FailedLoginState.latest(higher, lower));
  }

  @Test
  public void latestIsTheResetForTheSameFailureTime() {
    FailedLoginState locked = new FailedLoginState(5, TIME);
    FailedLoginState reset = new FailedLoginState(0, TIME);
    assertSame(reset, FailedLoginState.latest(locked, reset));
    assertSame(reset, FailedLoginState.latest(reset, locked));
  }

  /** A reset read back from the profile, which stores milliseconds, wins over the failures recorded on this node. */
  @Test
  public void latestIsTheResetReadFromTheProfileForAFailureTimeWithNanoseconds() {
    Instant failureTime = TIME.plusNanos(123456);
    FailedLoginState recorded = new FailedLoginState(5, failureTime);
    FailedLoginState reset = new FailedLoginState(0, Instant.ofEpochMilli(failureTime.toEpochMilli()));
    assertEquals(reset.latestFailureTime(), recorded.latestFailureTime());
    assertSame(reset, FailedLoginState.latest(recorded, reset));
  }

}

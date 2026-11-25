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

import java.time.Instant;

/**
 * Failed login attempts of a user: how many in a row, and the time of the
 * failure the lock duration is counted from, or for a reset recorded by a
 * successful login, the time of that login.
 */
public record FailedLoginState(int attempts, Instant latestFailureTime) {

  public static final FailedLoginState NONE = new FailedLoginState(0, Instant.EPOCH);

  /**
   * For the same failure time, a state with no attempt is a reset written after
   * those failures (a successful login, a password reset, which set the
   * attempts to 0 and leave the failure time), and failures recorded while the
   * account is locked keep its failure time and add attempts.
   *
   * @param first a state, never null
   * @param second a state, or null when there is none
   * @return the state recorded last: the later failure time; for the same
   *         failure time, the reset, then the higher number of attempts
   */
  public static FailedLoginState latest(FailedLoginState first, FailedLoginState second) {
    if (second == null) {
      return first;
    } else if (first.latestFailureTime().equals(second.latestFailureTime())) {
      if (first.attempts() == 0 || second.attempts() == 0) {
        return first.attempts() == 0 ? first : second;
      }
      return first.attempts() >= second.attempts() ? first : second;
    } else {
      return first.latestFailureTime().isAfter(second.latestFailureTime()) ? first : second;
    }
  }

}

/*
 * Copyright (C) 2022 eXo Platform SAS
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
package org.exoplatform.antibruteforce.plugin;

import org.exoplatform.antibruteforce.model.FailedLoginState;
import org.exoplatform.antibruteforce.utils.Utils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.AccountTemporaryLockedException;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.plugin.SecurityCheckAuthenticationPlugin;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Locks an account for {@code blockingTime} minutes once
 * {@code maxAuthenticationAttempts} logins in a row have failed, and sends the
 * user an "account locked" email when the account gets locked. The
 * authentication of a single user can run on several threads at once (a client
 * replaying a stale password on parallel requests), and the profile saved by
 * one of them is committed only when the authenticator ends its request: the
 * failures are therefore counted under a lock shared by the users whose names
 * hash to the same stripe, against the latest of the stored profile and the
 * state this node recorded last. Both are local to the node: on a cluster,
 * failures received at the same time by several nodes can still send one email
 * per node. A password reset ends the lock when it saves the failure time of
 * that lock; one that saves back an older failure time, read from a profile
 * cached before the last recorded failure, is ignored on this node until the
 * lock time of that failure ends: a locked account stays locked, and the
 * failures before the reset still count. A failure that waited too long for its
 * lock is counted without it.
 */
public class AntiBruteForceAuthenticationPlugin extends SecurityCheckAuthenticationPlugin {

  private static final Log      LOG                         = ExoLogger.getLogger(AntiBruteForceAuthenticationPlugin.class);

  private static final String     STATUS_NOT_OK               = "ko";

  private static final String     STATUS_OK                   = "ok";

  private static final String     ACCOUNT_LOCKED              = "accountLocked";

  private static final String     WRONG_CREDENTIALS           = "wrongCredentials";

  private static final String     SERVICE                     = "service";

  private static final String     LOGIN                       = "login";

  private static final String     OPERATION                   = "operation";

  private static final String     STATUS                      = "status";

  private static final String     AUTHENTICATION_ATTEMPTS     = "authenticationAttempts";

  private static final String     LATEST_AUTH_TIME            = "latestAuthFailureTime";

  private static final String     MAX_AUTHENTICATION_ATTEMPTS = "maxAuthenticationAttempts";

  private static final String     BLOCKING_TIME               = "blockingTime";

  private static final String     ACCOUNT_LOCKED_LOG          = SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " "
      + STATUS + "=" + STATUS_NOT_OK
      + " parameters=\"username:{}, authenticationAttempts:{}, maxAuthenticationAttempts:{}, latestAuthFailureTime={}, "
      + "lockTimeInMinutes={}, unlockTime={}\"" + " error_msg=\"Account is locked\"";

  private static final int        USER_LOCK_STRIPES           = 256;

  private static final long       USER_LOCK_TIMEOUT_SECONDS   = 5;

  private static final long       EXPIRED_STATES_SWEEP_SECONDS = 60;

  private final Lock[]            userLocks                   = new Lock[USER_LOCK_STRIPES];

  private final Map<String, FailedLoginState> recordedStates = new ConcurrentHashMap<>();

  private final Clock             clock;

  private final AtomicLong        lastExpiredStatesSweep      = new AtomicLong();

  private int                     maxAuthenticationAttempts   = 5;

  private int                     blockingTime                = 10;

  private OrganizationService     organizationService;

  private ListenerService         listenerService;

  public AntiBruteForceAuthenticationPlugin(InitParams initParams,
                                            OrganizationService organizationService,
                                            ListenerService listenerService) {
    this(initParams, organizationService, listenerService, Clock.systemUTC());
  }

  AntiBruteForceAuthenticationPlugin(InitParams initParams,
                                     OrganizationService organizationService,
                                     ListenerService listenerService,
                                     Clock clock) {
    this.organizationService = organizationService;
    this.listenerService = listenerService;
    this.clock = clock;
    for (int i = 0; i < USER_LOCK_STRIPES; i++) {
      userLocks[i] = new ReentrantLock();
    }
    if (initParams != null && initParams.getValueParam(MAX_AUTHENTICATION_ATTEMPTS)!=null) {
      this.maxAuthenticationAttempts=Integer.parseInt(initParams.getValueParam(MAX_AUTHENTICATION_ATTEMPTS).getValue());
    }
    if (initParams != null && initParams.getValueParam(BLOCKING_TIME)!=null) {
      this.blockingTime=Integer.parseInt(initParams.getValueParam(BLOCKING_TIME).getValue());
    }
  }

  @Override
  public void doCheck(User user) throws Exception {
    try {
      UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(user.getUserName());
      Instant now = clock.instant();
      FailedLoginState state = FailedLoginState.latest(toState(profile), getRecordedState(user.getUserName(), now));
      if (isLocked(state, now)) {
        Instant unlockTime = getUnlockTime(state);
        LOG.warn(ACCOUNT_LOCKED_LOG,
                 user.getUserName(),
                 state.attempts(),
                 this.maxAuthenticationAttempts,
                 state.latestFailureTime(),
                 this.blockingTime,
                 unlockTime);
        broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, ACCOUNT_LOCKED);
        throw new AccountTemporaryLockedException(user.getUserName(), unlockTime);
      }
    } catch (AccountTemporaryLockedException atle) {
      throw atle;
    } catch (Exception e) {
      LOG.error("Unable to get gatein user profile for user {}", user.getUserName(), e);
    }
  }

  @Override
  public void onCheckFail(String userName) {
    try {
      User user = organizationService.getUserHandler().findUserByName(userName);
      if (user != null) {
        Instant now;
        FailedLoginState previousState;
        FailedLoginState newState;
        Lock userLock = getUserLock(user.getUserName());
        boolean locked = lock(userLock, userName);
        try {
          now = clock.instant();
          UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(userName);
          if (profile == null) {
            profile = organizationService.getUserProfileHandler().createUserProfileInstance(userName);
          }
          previousState = FailedLoginState.latest(toState(profile), getRecordedState(user.getUserName(), now));
          newState = nextState(previousState, now);
          profile.setAttribute(AUTHENTICATION_ATTEMPTS, String.valueOf(newState.attempts()));
          profile.setAttribute(LATEST_AUTH_TIME, String.valueOf(newState.latestFailureTime().toEpochMilli()));
          organizationService.getUserProfileHandler().saveUserProfile(profile, true);
          recordedStates.put(user.getUserName(), newState);
          removeExpiredStates(now);
        } finally {
          if (locked) {
            userLock.unlock();
          }
        }

        if (isLocked(previousState, now)) {
          // A request that passed doCheck before the lock was recorded: the
          // account is already locked and its user already notified
          LOG.warn(ACCOUNT_LOCKED_LOG,
                   user.getUserName(),
                   newState.attempts(),
                   this.maxAuthenticationAttempts,
                   newState.latestFailureTime(),
                   this.blockingTime,
                   getUnlockTime(newState));
          broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, ACCOUNT_LOCKED);
        } else if (newState.attempts() >= this.maxAuthenticationAttempts) {
          LOG.warn(ACCOUNT_LOCKED_LOG,
                   user.getUserName(),
                   newState.attempts(),
                   this.maxAuthenticationAttempts,
                   newState.latestFailureTime(),
                   this.blockingTime,
                   getUnlockTime(newState));
          broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, ACCOUNT_LOCKED);
          sendAccountLockedEmail(user);
        } else {
          LOG.warn(SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " " + STATUS + "=" + STATUS_NOT_OK
              + " parameters=\"username:{}, authenticationAttempts:{}, latestAuthFailureTime:{}, maxAuthenticationAttempts:{}\""
              + " error_msg=\"Login failed\"", userName, newState.attempts(), now, this.maxAuthenticationAttempts);
          broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, WRONG_CREDENTIALS);
        }
      }
    } catch (Exception e) {
      LOG.error("Unable to get gatein user profile for user {}", userName, e);
    }
  }

  @Override
  public void onCheckSuccess(String userName) {
    try {
      User user = organizationService.getUserHandler().findUserByName(userName);
      if (user != null) {
        Lock userLock = getUserLock(user.getUserName());
        boolean locked = lock(userLock, userName);
        try {
          UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(userName);
          if (profile == null) {
            profile = organizationService.getUserProfileHandler().createUserProfileInstance(userName);
          }
          boolean hadFailures = recordedStates.containsKey(user.getUserName()) || toState(profile).attempts() > 0;
          profile.setAttribute(AUTHENTICATION_ATTEMPTS, String.valueOf(0));
          organizationService.getUserProfileHandler().saveUserProfile(profile, true);
          if (hadFailures) {
            // The reset is committed when the request ends: until then, a
            // failure running in parallel must not count from the stored attempts
            recordedStates.put(user.getUserName(), new FailedLoginState(0, clock.instant()));
          }
        } finally {
          if (locked) {
            userLock.unlock();
          }
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug(SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " " + STATUS + "=" + STATUS_OK
              + " parameters=\"username:{}, authenticationAttempts:{}, maxAuthenticationAttempts:{}\"",
                    userName,
                    0,
                    this.maxAuthenticationAttempts);
        }
      }
    } catch (Exception e) {
      LOG.error("Unable to get gatein user profile for user {}", userName, e);
    }
  }

  void sendAccountLockedEmail(User user) {
    Utils.sendAccountLockedEmail(user, Locale.ENGLISH, organizationService);
  }

  int getRecordedStatesCount() {
    return recordedStates.size();
  }

  /**
   * A failure while the account is locked is counted without moving the lock
   * time. The count is reset only by a successful login or a password reset:
   * once a lock has expired, the next failure locks the account again.
   */
  private FailedLoginState nextState(FailedLoginState previousState, Instant now) {
    if (isLocked(previousState, now)) {
      return new FailedLoginState(previousState.attempts() + 1, previousState.latestFailureTime());
    } else {
      return new FailedLoginState(previousState.attempts() + 1, now);
    }
  }

  private boolean isLocked(FailedLoginState state, Instant now) {
    return state.attempts() >= this.maxAuthenticationAttempts && getUnlockTime(state).isAfter(now);
  }

  private Instant getUnlockTime(FailedLoginState state) {
    return state.latestFailureTime().plus(this.blockingTime, ChronoUnit.MINUTES);
  }

  /**
   * @return the state this node recorded for the user, while its lock time
   *         runs: the profile saved with it is committed long before, and from
   *         then on the stored profile alone decides
   */
  private FailedLoginState getRecordedState(String userName, Instant now) {
    FailedLoginState recordedState = recordedStates.get(userName);
    return recordedState == null || getUnlockTime(recordedState).isAfter(now) ? recordedState : null;
  }

  /**
   * A state recorded by this node is only needed until the profile saved with
   * it is committed; once its lock time is over, the profile alone decides.
   */
  private void removeExpiredStates(Instant now) {
    long lastSweep = lastExpiredStatesSweep.get();
    if (now.toEpochMilli() - lastSweep >= EXPIRED_STATES_SWEEP_SECONDS * 1000
        && lastExpiredStatesSweep.compareAndSet(lastSweep, now.toEpochMilli())) {
      recordedStates.values().removeIf(state -> !getUnlockTime(state).isAfter(now));
    }
  }

  /**
   * Waits for the lock of the user's stripe for {@code USER_LOCK_TIMEOUT_SECONDS}
   * at most, so that a burst of failures delays the other logins of that stripe
   * by a bounded time. Past it, the login is counted without the lock: parallel
   * failures of that user can then lose increments and each send the "account
   * locked" email again.
   */
  private boolean lock(Lock userLock, String userName) {
    try {
      if (userLock.tryLock(USER_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return true;
      }
      LOG.warn("Login attempts of user {} counted without lock: lock not acquired within {} seconds",
               userName,
               USER_LOCK_TIMEOUT_SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Login attempts of user {} counted without lock: interrupted while waiting for the lock", userName);
    }
    return false;
  }

  private Lock getUserLock(String userName) {
    return userLocks[Math.floorMod(userName.hashCode(), USER_LOCK_STRIPES)];
  }

  private FailedLoginState toState(UserProfile profile) {
    if (profile == null) {
      return FailedLoginState.NONE;
    }
    String attempts = profile.getAttribute(AUTHENTICATION_ATTEMPTS);
    String latestFailureTime = profile.getAttribute(LATEST_AUTH_TIME);
    try {
      return new FailedLoginState(attempts == null ? 0 : Integer.parseInt(attempts),
                                  latestFailureTime == null ? Instant.EPOCH
                                                            : Instant.ofEpochMilli(Long.parseLong(latestFailureTime)));
    } catch (NumberFormatException e) {
      // Read as no failure, so that the next login writes valid values again
      LOG.warn("Unreadable login attempts of user {}: {}={}, {}={}",
               profile.getUserName(),
               AUTHENTICATION_ATTEMPTS,
               attempts,
               LATEST_AUTH_TIME,
               latestFailureTime);
      return FailedLoginState.NONE;
    }
  }

  private void broadcastFailedLoginEvent(String userId, String status, String reason) {

    try {
      Map<String, String> info = new HashMap<>();
      info.put("user_id", userId);
      info.put(STATUS, status);
      info.put("reason", reason);

      listenerService.broadcast("login.failed", null, info);
    } catch (Exception e) {
      LOG.error("Error while broadcasting event 'login.failed' for user '{}'", userId, e);
    }
  }
}

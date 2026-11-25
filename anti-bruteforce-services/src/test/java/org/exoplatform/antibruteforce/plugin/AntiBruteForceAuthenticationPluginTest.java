package org.exoplatform.antibruteforce.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.organization.AccountTemporaryLockedException;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserHandler;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.UserProfileHandler;

@RunWith(MockitoJUnitRunner.class)
public class AntiBruteForceAuthenticationPluginTest {

  private static final String                ATTEMPTS  = "authenticationAttempts";

  private static final String                LATEST    = "latestAuthFailureTime";

  private static final String                USERNAME  = "user";

  private static final Instant               START     = Instant.parse("2026-11-24T10:43:04Z");

  @Mock
  private OrganizationService                organizationService;

  @Mock
  private ListenerService                    listenerService;

  @Mock
  private UserHandler                        userHandler;

  @Mock
  private UserProfileHandler                 userProfileHandler;

  @Mock
  private User                               user;

  private MutableClock                       clock;

  private AntiBruteForceAuthenticationPlugin antiBruteForceAuthenticationPlugin;

  @Before
  public void setUp() throws Exception {
    InitParams params = new InitParams();
    params.addParameter(valueParam("maxAuthenticationAttempts", "5"));
    params.addParameter(valueParam("blockingTime", "10"));
    clock = new MutableClock(START);
    lenient().when(user.getUserName()).thenReturn(USERNAME);
    lenient().when(organizationService.getUserHandler()).thenReturn(userHandler);
    lenient().when(organizationService.getUserProfileHandler()).thenReturn(userProfileHandler);
    lenient().when(userHandler.findUserByName(USERNAME)).thenReturn(user);
    antiBruteForceAuthenticationPlugin = spy(new AntiBruteForceAuthenticationPlugin(params,
                                                                                      organizationService,
                                                                                      listenerService,
                                                                                      clock));
    lenient().doNothing().when(antiBruteForceAuthenticationPlugin).sendAccountLockedEmail(user);
  }

  @Test
  public void doCheck() throws Exception {
    AntiBruteForceAuthenticationPlugin plugin = new AntiBruteForceAuthenticationPlugin(null,
                                                                                       organizationService,
                                                                                       listenerService);
    Map<String, String> attributes = storedProfile();
    attributes.put(ATTEMPTS, "5");
    attributes.put(LATEST, String.valueOf(Instant.now().toEpochMilli()));
    assertThrows(AccountTemporaryLockedException.class, () -> plugin.doCheck(user));

    attributes.put(LATEST, String.valueOf(Instant.now().minus(10, ChronoUnit.MINUTES).toEpochMilli()));
    try {
      plugin.doCheck(user);
    } catch (Exception e) {
      fail("AccountTemporaryLockedException Should not be thrown here");
    }
  }

  @Test
  public void onCheckFail() throws Exception {
    Map<String, String> attributes = storedProfile();
    attributes.put(ATTEMPTS, "3");
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals("4", attributes.get(ATTEMPTS));
    verify(antiBruteForceAuthenticationPlugin, never()).sendAccountLockedEmail(user);

    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals("5", attributes.get(ATTEMPTS));
    assertEquals(String.valueOf(START.toEpochMilli()), attributes.get(LATEST));
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
  }

  /**
   * Failures beyond the threshold, recorded while the account is locked, are
   * counted but neither send another email nor move the lock time: they come
   * from requests that passed doCheck before the lock was recorded.
   */
  @Test
  public void onCheckFailWhileLockedSendsNoOtherEmailAndKeepsTheLockTime() throws Exception {
    Map<String, String> attributes = storedProfile();
    for (int i = 0; i < 5; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);

    clock.advance(30);
    for (int i = 0; i < 15; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }
    assertEquals("20", attributes.get(ATTEMPTS));
    assertEquals(String.valueOf(START.toEpochMilli()), attributes.get(LATEST));
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
  }

  /**
   * Only a successful login resets the count: once a lock has expired, the next
   * failure locks the account again, with a new lock time and a new email.
   */
  @Test
  public void onCheckFailAfterAnExpiredLockLocksAgain() throws Exception {
    Map<String, String> attributes = storedProfile();
    attributes.put(ATTEMPTS, "7");
    attributes.put(LATEST, String.valueOf(START.toEpochMilli()));
    clock.advance(10 * 60L + 1);
    antiBruteForceAuthenticationPlugin.doCheck(user);

    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    assertEquals("8", attributes.get(ATTEMPTS));
    assertEquals(String.valueOf(clock.instant().toEpochMilli()), attributes.get(LATEST));
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
    assertThrows(AccountTemporaryLockedException.class, () -> antiBruteForceAuthenticationPlugin.doCheck(user));
  }

  /**
   * A password reset unlocks the account by setting the attempts to 0 in the
   * profile, outside this plugin and without changing the failure time.
   */
  @Test
  public void aResetWrittenInTheProfileUnlocksTheAccount() throws Exception {
    Map<String, String> attributes = storedProfile();
    for (int i = 0; i < 5; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }
    assertThrows(AccountTemporaryLockedException.class, () -> antiBruteForceAuthenticationPlugin.doCheck(user));

    attributes.put(ATTEMPTS, "0");

    antiBruteForceAuthenticationPlugin.doCheck(user);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals("1", attributes.get(ATTEMPTS));
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
  }

  @Test
  public void onCheckFailUnderTheThresholdKeepsCounting() throws Exception {
    Map<String, String> attributes = storedProfile();
    attributes.put(ATTEMPTS, "2");
    attributes.put(LATEST, String.valueOf(START.minus(1, ChronoUnit.DAYS).toEpochMilli()));

    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    assertEquals("3", attributes.get(ATTEMPTS));
    assertEquals(String.valueOf(START.toEpochMilli()), attributes.get(LATEST));
  }

  /**
   * The profile saved by a failure is committed only when the authenticator
   * ends its request: until then, other requests read the previous value. The
   * state this node recorded keeps the count, the lock and the email right.
   */
  @Test
  public void onCheckFailAndDoCheckUseTheRecordedStateWhileTheProfileIsNotCommitted() throws Exception {
    staleProfile("4", START.minus(1, ChronoUnit.MINUTES));

    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
    assertThrows(AccountTemporaryLockedException.class, () -> antiBruteForceAuthenticationPlugin.doCheck(user));
  }

  @Test
  public void parallelFailuresAreAllCountedAndSendOneEmail() throws Exception {
    UserProfile profile = staleProfile("4", START.minus(1, ChronoUnit.MINUTES));
    List<String> savedAttempts = Collections.synchronizedList(new ArrayList<>());
    doAnswer(invocation -> savedAttempts.add(invocation.getArgument(1))).when(profile).setAttribute(eq(ATTEMPTS), anyString());
    int threads = 20;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
          return null;
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
    assertEquals(IntStream.rangeClosed(5, 24).mapToObj(String::valueOf).toList(), savedAttempts);
  }

  /**
   * A successful login resets the count when its request ends: a failure
   * running in parallel, which still reads the attempts of an expired lock,
   * must not lock the account again.
   */
  @Test
  public void aFailureInParallelWithASuccessDoesNotLockAgain() throws Exception {
    staleProfile("5", START.minus(11, ChronoUnit.MINUTES));

    antiBruteForceAuthenticationPlugin.onCheckSuccess(USERNAME);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    verify(antiBruteForceAuthenticationPlugin, never()).sendAccountLockedEmail(user);
    antiBruteForceAuthenticationPlugin.doCheck(user);
  }

  /**
   * A successful login of a user with no failure records nothing on the node.
   */
  @Test
  public void aSuccessWithoutFailureRecordsNoState() throws Exception {
    staleProfile("0", START.minus(1, ChronoUnit.DAYS));
    antiBruteForceAuthenticationPlugin.onCheckSuccess(USERNAME);

    staleProfile("4", START.minus(1, ChronoUnit.MINUTES));
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
  }

  /**
   * Failures recorded on the node and not committed yet: the profile still has
   * no attempt when the user logs in successfully, and the next failure must
   * count from that success.
   */
  @Test
  public void aSuccessAfterUncommittedFailuresResetsTheCount() throws Exception {
    staleProfile("0", START.minus(1, ChronoUnit.DAYS));
    for (int i = 0; i < 4; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }

    antiBruteForceAuthenticationPlugin.onCheckSuccess(USERNAME);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    verify(antiBruteForceAuthenticationPlugin, never()).sendAccountLockedEmail(user);
    antiBruteForceAuthenticationPlugin.doCheck(user);
  }

  /**
   * A profile with no attempt and a failure time older than the lock recorded
   * by this node is read from a cache filled before the failures, or saved back
   * from such a read: the lock holds until it ends, then the count starts
   * again from the reset.
   */
  @Test
  public void aProfileWithNoAttemptReadBeforeTheLockKeepsTheAccountLocked() throws Exception {
    Map<String, String> attributes = storedProfile();
    for (int i = 0; i < 5; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }
    attributes.put(ATTEMPTS, "0");
    attributes.put(LATEST, String.valueOf(START.minus(1, ChronoUnit.MINUTES).toEpochMilli()));

    clock.advance(10 * 60L - 1);
    assertThrows(AccountTemporaryLockedException.class, () -> antiBruteForceAuthenticationPlugin.doCheck(user));

    clock.advance(1);
    antiBruteForceAuthenticationPlugin.doCheck(user);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals("1", attributes.get(ATTEMPTS));
    verify(antiBruteForceAuthenticationPlugin, times(1)).sendAccountLockedEmail(user);
  }

  @Test
  public void unreadableAttemptsAreReadAsNoFailure() throws Exception {
    Map<String, String> attributes = storedProfile();
    attributes.put(ATTEMPTS, "x");
    attributes.put(LATEST, "y");

    antiBruteForceAuthenticationPlugin.doCheck(user);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);

    assertEquals("1", attributes.get(ATTEMPTS));
    assertEquals(String.valueOf(START.toEpochMilli()), attributes.get(LATEST));
  }

  /**
   * The states recorded by the node are removed once their lock time is over,
   * at most once a minute.
   */
  @Test
  public void expiredRecordedStatesAreRemovedAtMostOnceAMinute() throws Exception {
    User otherUser = mock(User.class);
    when(otherUser.getUserName()).thenReturn("other");
    when(userHandler.findUserByName("other")).thenReturn(otherUser);
    UserProfile otherProfile = mock(UserProfile.class);
    when(userProfileHandler.findUserProfileByName("other")).thenReturn(otherProfile);
    storedProfile();

    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals(1, antiBruteForceAuthenticationPlugin.getRecordedStatesCount());

    // 10:00 after the first failure: its state has expired and is removed
    clock.advance(10 * 60L);
    antiBruteForceAuthenticationPlugin.onCheckFail("other");
    assertEquals(1, antiBruteForceAuthenticationPlugin.getRecordedStatesCount());

    clock.advance(59);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    clock.advance(10 * 60L - 59);
    antiBruteForceAuthenticationPlugin.onCheckFail("other");
    assertEquals(2, antiBruteForceAuthenticationPlugin.getRecordedStatesCount());

    // USERNAME's state expires now, 59 s after the last removal: kept until a
    // minute has passed since that removal
    clock.advance(59);
    antiBruteForceAuthenticationPlugin.onCheckFail("other");
    assertEquals(2, antiBruteForceAuthenticationPlugin.getRecordedStatesCount());

    clock.advance(1);
    antiBruteForceAuthenticationPlugin.onCheckFail("other");
    assertEquals(1, antiBruteForceAuthenticationPlugin.getRecordedStatesCount());
  }

  @Test
  public void onCheckSuccess() throws Exception {
    Map<String, String> attributes = storedProfile();
    for (int i = 0; i < 5; i++) {
      antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    }
    antiBruteForceAuthenticationPlugin.onCheckSuccess(USERNAME);
    assertEquals("0", attributes.get(ATTEMPTS));

    antiBruteForceAuthenticationPlugin.doCheck(user);
    antiBruteForceAuthenticationPlugin.onCheckFail(USERNAME);
    assertEquals("1", attributes.get(ATTEMPTS));
    assertTrue(attributes.containsKey(LATEST));
  }

  /**
   * A profile whose attributes are read and written through the returned map,
   * as the committed profile of a single request at a time.
   */
  private Map<String, String> storedProfile() throws Exception {
    Map<String, String> attributes = new ConcurrentHashMap<>();
    UserProfile profile = mock(UserProfile.class);
    lenient().when(profile.getUserName()).thenReturn(USERNAME);
    lenient().when(profile.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
    lenient().doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
             .when(profile)
             .setAttribute(anyString(), anyString());
    lenient().when(userProfileHandler.findUserProfileByName(USERNAME)).thenReturn(profile);
    return attributes;
  }

  /**
   * A profile that keeps returning its committed values whatever is saved: what
   * the other requests read before the failing one ends.
   */
  private UserProfile staleProfile(String attempts, Instant latestFailureTime) throws Exception {
    UserProfile profile = mock(UserProfile.class);
    lenient().when(profile.getUserName()).thenReturn(USERNAME);
    lenient().when(profile.getAttribute(ATTEMPTS)).thenReturn(attempts);
    lenient().when(profile.getAttribute(LATEST)).thenReturn(String.valueOf(latestFailureTime.toEpochMilli()));
    lenient().when(userProfileHandler.findUserProfileByName(USERNAME)).thenReturn(profile);
    return profile;
  }

  private static ValueParam valueParam(String name, String value) {
    ValueParam param = new ValueParam();
    param.setName(name);
    param.setValue(value);
    return param;
  }

  private static class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}

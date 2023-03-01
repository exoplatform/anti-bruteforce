package org.exoplatform.antibruteforce.plugin;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import org.exoplatform.antibruteforce.utils.Utils;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.PropertiesParam;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.organization.AccountTemporaryLockedException;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserHandler;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.UserProfileHandler;

@RunWith(MockitoJUnitRunner.class)
public class AntiBruteForceAuthenticationPluginTest {

  private static final MockedStatic<CommonsUtils>            COMMONS_UTILS             = mockStatic(CommonsUtils.class);

  private static final MockedStatic<Utils>       UTILS        = mockStatic(Utils.class);

  @Mock
  private OrganizationService                organizationService;

  @Mock
  private ListenerService                    listenerService;

  private AntiBruteForceAuthenticationPlugin antiBruteForceAuthenticationPlugin;

  @AfterClass
  public static void afterRunBare() throws Exception { // NOSONAR
    COMMONS_UTILS.close();
    UTILS.close();
  }

  @Before
  public void setUp() throws Exception {
    InitParams params = new InitParams();
    PropertiesParam maxAuthAttempts = new PropertiesParam();
    maxAuthAttempts.setProperty("maxAuthenticationAttempts", "5");
    PropertiesParam blockTime = new PropertiesParam();
    blockTime.setProperty("blockingTime", "10");
    params.addParameter(maxAuthAttempts);
    params.addParameter(blockTime);

    this.antiBruteForceAuthenticationPlugin =
                                            new AntiBruteForceAuthenticationPlugin(params, organizationService, listenerService);
  }

  @Test
  public void doCheck() throws Exception {
    UserProfile userProfile = mock(UserProfile.class);
    User user = mock(User.class);
    when(user.getUserName()).thenReturn("user");
    UserProfileHandler userProfileHandler = mock(UserProfileHandler.class);
    when(organizationService.getUserProfileHandler()).thenReturn(userProfileHandler);
    when(userProfileHandler.findUserProfileByName("user")).thenReturn(userProfile);
    when(userProfile.getAttribute("authenticationAttempts")).thenReturn("5");
    when(userProfile.getAttribute("latestAuthFailureTime")).thenReturn(String.valueOf(Timestamp.from(Instant.now()).getTime()));
    assertThrows(AccountTemporaryLockedException.class, () -> this.antiBruteForceAuthenticationPlugin.doCheck(user));

    when(userProfile.getAttribute("authenticationAttempts")).thenReturn("5");
    when(userProfile.getAttribute("latestAuthFailureTime")).thenReturn(String.valueOf(Timestamp.from(Instant.now()
            .minus(10, ChronoUnit.MINUTES)).getTime()));
    try {
      this.antiBruteForceAuthenticationPlugin.doCheck(user);
    } catch (Exception e) {
      fail("AccountTemporaryLockedException Should not be thrown here");
    }
  }

  @Test
  public void onCheckFail() throws Exception {
    User user = mock(User.class);
    UserProfile userProfile = mock(UserProfile.class);
    UserProfileHandler userProfileHandler = mock(UserProfileHandler.class);
    UserHandler userHandler = mock(UserHandler.class);
    when(organizationService.getUserHandler()).thenReturn(userHandler);
    when(userHandler.findUserByName("user")).thenReturn(user);
    when(organizationService.getUserProfileHandler()).thenReturn(userProfileHandler);
    when(userProfileHandler.findUserProfileByName("user")).thenReturn(userProfile);
    when(userProfile.getAttribute("authenticationAttempts")).thenReturn("3");
    this.antiBruteForceAuthenticationPlugin.onCheckFail("user");
    verify(userProfile, times(1)).setAttribute("authenticationAttempts", "4");
    UTILS.verifyNoInteractions();

    when(userProfile.getAttribute("authenticationAttempts")).thenReturn("4");
    this.antiBruteForceAuthenticationPlugin.onCheckFail("user");
    verify(userProfile, times(1)).setAttribute("authenticationAttempts", "5");
    UTILS.verify(() -> Utils.sendAccountLockedEmail(eq(user), any(), eq(organizationService)), times(1));
    Utils.sendAccountLockedEmail(user, Locale.ENGLISH, organizationService);
  }

  @Test
  public void onCheckSuccess() throws Exception {
    User user = mock(User.class);
    UserProfile userProfile = mock(UserProfile.class);
    UserProfileHandler userProfileHandler = mock(UserProfileHandler.class);
    UserHandler userHandler = mock(UserHandler.class);
    when(organizationService.getUserHandler()).thenReturn(userHandler);
    when(userHandler.findUserByName("user")).thenReturn(user);
    when(organizationService.getUserProfileHandler()).thenReturn(userProfileHandler);
    when(userProfileHandler.findUserProfileByName("user")).thenReturn(userProfile);
    this.antiBruteForceAuthenticationPlugin.onCheckSuccess("user");
    verify(userProfile, times(1)).setAttribute("authenticationAttempts", "0");
  }
}

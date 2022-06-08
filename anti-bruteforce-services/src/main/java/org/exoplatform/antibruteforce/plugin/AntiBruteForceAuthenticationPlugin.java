package org.exoplatform.antibruteforce.plugin;

import org.exoplatform.antibruteforce.utils.Utils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.AccountTemporaryLockedException;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.auth.SecurityCheckAuthenticationPlugin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

  private int                     maxAuthenticationAttempts   = 5;

  private int                     blockingTime                = 10;

  private OrganizationService     organizationService;

  private ListenerService         listenerService;

  public AntiBruteForceAuthenticationPlugin(InitParams initParams,
                                            OrganizationService organizationService,
                                            ListenerService listenerService) {
    this.organizationService = organizationService;
    this.listenerService = listenerService;
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
      if (profile != null) {
        int currentNbFail =
                          profile.getAttribute(AUTHENTICATION_ATTEMPTS) != null ? Integer.parseInt(profile.getAttribute(AUTHENTICATION_ATTEMPTS))
                                                                                : 0;
        Instant latestAuthFailureTime =
                                      Instant.ofEpochMilli(profile.getAttribute(LATEST_AUTH_TIME) != null ? Long.parseLong(profile.getAttribute(LATEST_AUTH_TIME))
                                                                                                          : Instant.EPOCH.toEpochMilli());
        if (currentNbFail >= this.maxAuthenticationAttempts
            && latestAuthFailureTime.plus(this.blockingTime, ChronoUnit.MINUTES).isAfter(Instant.now())) {

          LOG.warn(SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " " + STATUS + "=" + STATUS_NOT_OK
              + " parameters=\"username:{}, authenticationAttempts:{}, maxAuthenticationAttempts:{}, latestAuthFailureTime={}, "
              + "lockTimeInMinutes={}, unlockTime={}\"" + " error_msg=\"Account is locked\"",
                   user.getUserName(),
                   currentNbFail,
                   this.maxAuthenticationAttempts,
                   latestAuthFailureTime,
                   this.blockingTime,
                   latestAuthFailureTime.plus(this.blockingTime, ChronoUnit.MINUTES));
          broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, ACCOUNT_LOCKED);
          throw new AccountTemporaryLockedException(user.getUserName(),
                                                    latestAuthFailureTime.plus(this.blockingTime, ChronoUnit.MINUTES));
        }
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
        UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(userName);
        if (profile == null) {
          profile = organizationService.getUserProfileHandler().createUserProfileInstance(userName);
        }
        int currentNbFail =
                          profile.getAttribute(AUTHENTICATION_ATTEMPTS) != null ? Integer.parseInt(profile.getAttribute(AUTHENTICATION_ATTEMPTS))
                                                                                : 0;
        currentNbFail++;
        profile.setAttribute(AUTHENTICATION_ATTEMPTS, String.valueOf(currentNbFail));
        Instant now = Instant.now();
        profile.setAttribute(LATEST_AUTH_TIME, String.valueOf(now.toEpochMilli()));
        organizationService.getUserProfileHandler().saveUserProfile(profile, true);

        if (currentNbFail >= this.maxAuthenticationAttempts) {
          LOG.warn(SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " " + STATUS + "=" + STATUS_NOT_OK
              + " parameters=\"username:{}, authenticationAttempts:{}, maxAuthenticationAttempts:{}, latestAuthFailureTime={}, "
              + "lockTimeInMinutes={}, unlockTime={}\"" + " error_msg=\"Account is locked\"",
                   user.getUserName(),
                   currentNbFail,
                   this.maxAuthenticationAttempts,
                   now,
                   this.blockingTime,
                   now.plus(this.blockingTime, ChronoUnit.MINUTES));

          broadcastFailedLoginEvent(user.getUserName(), STATUS_NOT_OK, ACCOUNT_LOCKED);
          Utils.sendAccountLockedEmail(user, Locale.ENGLISH, organizationService);

        } else {
          LOG.warn(SERVICE + "=" + LOGIN + " " + OPERATION + "=" + LOGIN + " " + STATUS + "=" + STATUS_NOT_OK
              + " parameters=\"username:{}, authenticationAttempts:{}, latestAuthFailureTime:{}, maxAuthenticationAttempts:{}\""
              + " error_msg=\"Login failed\"", userName, currentNbFail, now, this.maxAuthenticationAttempts);
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
        UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(userName);
        if (profile == null) {
          profile = organizationService.getUserProfileHandler().createUserProfileInstance(userName);
        }
        profile.setAttribute(AUTHENTICATION_ATTEMPTS, String.valueOf(0));
        organizationService.getUserProfileHandler().saveUserProfile(profile, true);
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

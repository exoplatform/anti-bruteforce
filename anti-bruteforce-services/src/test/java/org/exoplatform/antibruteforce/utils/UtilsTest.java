package org.exoplatform.antibruteforce.utils;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.MailUtils;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfileHandler;
import org.exoplatform.services.organization.idm.UserImpl;
import org.exoplatform.services.organization.impl.UserProfileImpl;
import org.exoplatform.services.resources.ExoResourceBundle;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.web.WebAppController;
import org.exoplatform.web.controller.metadata.ControllerDescriptor;
import org.exoplatform.web.controller.router.Router;

@RunWith(MockitoJUnitRunner.class)
public class UtilsTest {

  private static final MockedStatic<CommonsUtils> COMMONS_UTILS = mockStatic(CommonsUtils.class);

  private static final MockedStatic<MailUtils>    MAIL_UTILS    = mockStatic(MailUtils.class);

  User user;
  OrganizationService organizationService;

  MailService mailService;

  @AfterClass
  public static void afterRunBare() throws Exception { // NOSONAR
    COMMONS_UTILS.close();
    MAIL_UTILS.close();
  }

  @Before
  public void setUp() throws Exception {
    organizationService = mock(OrganizationService.class);

    String userName = "ali";
    user = new UserImpl(userName);
    user.setFirstName("Ali");
    user.setEmail("ali@exo.com");
    UserProfileHandler userProfileHandler = mock(UserProfileHandler.class);
    when(userProfileHandler.findUserProfileByName(any())).thenReturn(new UserProfileImpl(userName));
    when(organizationService.getUserProfileHandler()).thenReturn(userProfileHandler);

    mailService = mock(MailService.class);
    COMMONS_UTILS.when(() -> CommonsUtils.getService(MailService.class)).thenReturn(mailService);

    MAIL_UTILS.when(() -> MailUtils.getSenderEmail()).thenReturn("security@exo.com");
    MAIL_UTILS.when(() -> MailUtils.getSenderName()).thenReturn("Security Team");

    ResourceBundle bundle = new ExoResourceBundle("antibruteforce.accountLocked.email.subject=Locked email\r\n" + "key2=value");
    ResourceBundleService resourceBundleService = mock(ResourceBundleService.class);
    when(resourceBundleService.getResourceBundle(anyString(), any())).thenReturn(bundle);
    COMMONS_UTILS.when(() -> CommonsUtils.getService(ResourceBundleService.class)).thenReturn(resourceBundleService);

    WebAppController webAppController = mock(WebAppController.class);
    when(webAppController.getRouter()).thenReturn(new Router(new ControllerDescriptor()));
    COMMONS_UTILS.when(() -> CommonsUtils.getService(WebAppController.class)).thenReturn(webAppController);
  }

  @Test
  public void testSendAccountLockedEmail() {
    try {
      Utils.sendAccountLockedEmail(user, Locale.getDefault(), organizationService);
      Mockito.verify(mailService,times(1)).sendMessage((Message) any());
    } catch (Exception e) {
      fail();
    }
  }
}

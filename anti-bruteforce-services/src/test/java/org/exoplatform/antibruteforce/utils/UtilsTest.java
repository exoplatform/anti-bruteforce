package org.exoplatform.antibruteforce.utils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.MailUtils;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.UserProfileHandler;
import org.exoplatform.services.organization.idm.UserImpl;
import org.exoplatform.services.organization.impl.UserProfileImpl;
import org.exoplatform.services.resources.ExoResourceBundle;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.web.WebAppController;
import org.exoplatform.web.controller.metadata.ControllerDescriptor;
import org.exoplatform.web.controller.router.Router;
import org.gatein.common.util.EmptyResourceBundle;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.powermock.api.mockito.PowerMockito.*;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"javax.management.*", "javax.xml.*", "org.xml.*"})
public class UtilsTest {

  User user;
  OrganizationService organizationService;

  MailService mailService;

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

    mockStatic(CommonsUtils.class);
    mailService = mock(MailService.class);
    when(CommonsUtils.getService(MailService.class)).thenReturn(mailService);

    mockStatic(MailUtils.class);
    when(MailUtils.getSenderEmail()).thenReturn("security@exo.com");
    when(MailUtils.getSenderName()).thenReturn("Security Team");

    ResourceBundle bundle = new ExoResourceBundle("antibruteforce.accountLocked.email.subject=Locked email\r\n" + "key2=value");
    ResourceBundleService resourceBundleService = mock(ResourceBundleService.class);
    when(resourceBundleService.getResourceBundle(anyString(), any())).thenReturn(bundle);
    when(CommonsUtils.getService(ResourceBundleService.class)).thenReturn(resourceBundleService);

    WebAppController webAppController = mock(WebAppController.class);
    when(webAppController.getRouter()).thenReturn(new Router(new ControllerDescriptor()));
    when(CommonsUtils.getService(WebAppController.class)).thenReturn(webAppController);
  }

  @Test
  @PrepareForTest({CommonsUtils.class, MailUtils.class})
  public void testSendAccountLockedEmail() {
    try {
      Utils.sendAccountLockedEmail(user, Locale.getDefault(), organizationService);
      Mockito.verify(mailService,times(1)).sendMessage((Message) any());
    } catch (Exception e) {
      fail();
    }
  }
}

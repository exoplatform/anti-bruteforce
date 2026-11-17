package org.exoplatform.antibruteforce.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.MailUtils;
import org.exoplatform.portal.branding.BrandingService;
import org.exoplatform.portal.branding.MailDocumentShell;
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
    COMMONS_UTILS.when(() -> CommonsUtils.getService(BrandingService.class)).thenReturn(null);

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

  @Test
  public void testAccountLockedEmailIsALightDocumentBeforeTheThemeServices() throws Exception {
    String body = sentBody();
    assertTrue(body, body.startsWith("<!DOCTYPE html>"));
    assertTrue(body, body.contains("<meta name=\"color-scheme\" content=\"light\">"));
    assertFalse(body, body.contains("<style>"));
    assertFalse(body, body.contains("${MAIL_"));
    assertTrue(body, body.contains("Ali"));
  }

  @Test
  public void testAccountLockedEmailFollowsTheRecipientsAutomaticMode() throws Exception {
    bindBranding("auto");
    String body = sentBody();
    assertTrue(body, body.contains("<meta name=\"color-scheme\" content=\"light dark\">"));
    // the light palette is inline, the dark one comes from the media query
    assertTrue(body, body.contains("bgcolor=\"#ABCDE1\""));
    assertTrue(body, body.contains("border: solid 1px #ABCDE5"));
    assertTrue(body, body.contains("bgcolor=\"#ABCDE6\""));
    assertTrue(body, body.contains("class=\"mail-surface\""));
    assertTrue(body, body.contains(".mail-surface{background-color:#1E2328 !important;}"));
    assertTrue(body, body.contains(".mail-link{color:#7FC8CB !important;}"));
    assertFalse(body, body.contains("${MAIL_"));
  }

  /**
   * The palette the body is written with is the one whose mode wraps the
   * document: the recipient's mode is read once for the mail.
   */
  @Test
  public void testAccountLockedEmailReadsTheRecipientsModeOnce() throws Exception {
    BrandingService brandingService = bindBranding("auto");
    sentBody();
    Mockito.verify(brandingService, Mockito.times(1)).getDisplayMode("ali");
  }

  @Test
  public void testAccountLockedEmailIsWrittenOnTheDarkPaletteInDarkMode() throws Exception {
    bindBranding("dark");
    String body = sentBody();
    assertTrue(body, body.contains("<meta name=\"color-scheme\" content=\"dark\">"));
    assertTrue(body, body.contains("bgcolor=\"#1E2328\""));
    assertTrue(body, body.contains("color: #7FC8CB;"));
    assertFalse(body, body.contains("<style>"));
    assertFalse(body, body.contains("#ABCDE1"));
  }

  @Test
  public void testAccountLockedEmailNeverWritesANonColorPaletteValue() throws Exception {
    BrandingService brandingService = mock(BrandingService.class);
    when(brandingService.getDisplayMode("ali")).thenReturn("light");
    when(brandingService.getMailPalette(false)).thenReturn(Map.of(MailDocumentShell.LINK, "red\" onmouseover=\"x"));
    COMMONS_UTILS.when(() -> CommonsUtils.getService(BrandingService.class)).thenReturn(brandingService);
    String body = sentBody();
    assertFalse(body, body.contains("onmouseover"));
    assertTrue(body, body.contains("color: #3F8487;"));
  }

  private BrandingService bindBranding(String mode) {
    BrandingService brandingService = mock(BrandingService.class);
    when(brandingService.getDisplayMode("ali")).thenReturn(mode);
    when(brandingService.getMailPalette(false)).thenReturn(Map.of(MailDocumentShell.BACKGROUND,
                                                                  "#ABCDE1",
                                                                  MailDocumentShell.SURFACE,
                                                                  "#ABCDE2",
                                                                  MailDocumentShell.TEXT,
                                                                  "#ABCDE3",
                                                                  MailDocumentShell.LINK,
                                                                  "#ABCDE4",
                                                                  MailDocumentShell.BORDER,
                                                                  "#ABCDE5",
                                                                  MailDocumentShell.PRIMARY,
                                                                  "#ABCDE6"));
    when(brandingService.getMailPalette(true)).thenReturn(Map.of(MailDocumentShell.BACKGROUND,
                                                                 "#14181C",
                                                                 MailDocumentShell.SURFACE,
                                                                 "#1E2328",
                                                                 MailDocumentShell.TEXT,
                                                                 "#E6EAED",
                                                                 MailDocumentShell.LINK,
                                                                 "#7FC8CB",
                                                                 MailDocumentShell.BORDER,
                                                                 "#3A4148",
                                                                 MailDocumentShell.PRIMARY,
                                                                 "#2D6A6D"));
    COMMONS_UTILS.when(() -> CommonsUtils.getService(BrandingService.class)).thenReturn(brandingService);
    return brandingService;
  }

  private String sentBody() throws Exception {
    Utils.sendAccountLockedEmail(user, Locale.ENGLISH, organizationService);
    ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
    Mockito.verify(mailService).sendMessage(message.capture());
    return message.getValue().getBody();
  }
}

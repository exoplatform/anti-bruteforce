package org.exoplatform.antibruteforce.utils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.MailUtils;
import org.exoplatform.portal.Constants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.resources.LocaleContextInfo;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.web.WebAppController;
import org.exoplatform.web.controller.QualifiedName;
import org.exoplatform.web.controller.router.Router;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

  private static final Log     LOG                                  = ExoLogger.getLogger(Utils.class);

  private static final String  CONFIGURED_DOMAIN_URL_KEY            = "gatein.email.domain.url";

  private static final String  ANTI_BRUTEFORCE_RESOURCE_BUNDLE_NAME = "locale.Antibruteforce";

  private static final String  ACCOUNT_LOCKED_MAIL_SUBJECT_KEY      = "antibruteforce.accountLocked.email.subject";

  private static final String  ACCOUNT_LOCKED_MAIL_TEMPLATE_PATH    = "template/account_locked_email_template.html";

  private static final Pattern PATTERN                              = Pattern.compile("&\\{([a-zA-Z\\d\\.]+)\\}");

  public static void sendAccountLockedEmail(User user,
                                            Locale defaultLocale,
                                            OrganizationService organizationService) throws Exception {
    if (user == null) {
      throw new IllegalArgumentException("User or Locale must not be null");
    }
    ResourceBundleService resourceBundleService = CommonsUtils.getService(ResourceBundleService.class);
    WebAppController webAppController = CommonsUtils.getService(WebAppController.class);
    MailService mailService = CommonsUtils.getService(MailService.class);
    UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(user.getUserName());
    String lang = profile == null ? null : profile.getUserInfoMap().get(Constants.USER_LANGUAGE);
    Locale locale = (lang != null) ? LocaleContextInfo.getLocale(lang) : defaultLocale;

    ResourceBundle bundle = resourceBundleService.getResourceBundle(ANTI_BRUTEFORCE_RESOURCE_BUNDLE_NAME, locale);

    Router router = webAppController.getRouter();
    Map<QualifiedName, String> params = new HashMap<>();
    params.put(WebAppController.HANDLER_PARAM, "forgot-password");

    String url = System.getProperty(CONFIGURED_DOMAIN_URL_KEY) + "/portal" + router.render(params);

    String emailBody = buildAccountLockedEmailBody(user, bundle, url);
    String emailSubject = bundle.getString(ACCOUNT_LOCKED_MAIL_SUBJECT_KEY);

    String senderName = MailUtils.getSenderName();
    String from = MailUtils.getSenderEmail();
    if (senderName != null && !senderName.trim().isEmpty()) {
      from = senderName + " <" + from + ">";
    }

    Message message = new Message();
    message.setFrom(from);
    message.setTo(user.getEmail());
    message.setSubject(emailSubject);
    message.setBody(emailBody);
    message.setMimeType("text/html");

    try {
      mailService.sendMessage(message);
    } catch (Exception ex) {
      LOG.error("Failure to send account locked email", ex);
    }
  }

  private static String buildAccountLockedEmailBody(User user, ResourceBundle bundle, String link) {
    String content;
    InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(ACCOUNT_LOCKED_MAIL_TEMPLATE_PATH);
    if (input == null) {
      content = "";
    } else {
      content = resolveLanguage(input, bundle);
    }
    content = content.replace("${FIRST_NAME}", user.getFirstName());
    content = content.replace("${USERNAME}", user.getUserName());
    content = content.replace("${FORGOT_PASSWORD_LINK}", link);

    return content;
  }

  private static String resolveLanguage(InputStream input, ResourceBundle bundle) {
    StringBuffer content = new StringBuffer();
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(input));
      String line;
      while ((line = reader.readLine()) != null) {
        if (content.length() > 0) {
          content.append("\n");
        }
        parseKeys(content, line, bundle);
      }
    } catch (IOException ex) {
      LOG.error(ex);
    }
    return content.toString();
  }

  private static void parseKeys(StringBuffer sb, String input, ResourceBundle bundle) {
    Matcher matcher = PATTERN.matcher(input);
    while (matcher.find()) {
      String key = matcher.group(1);
      String resource;
      try {
        resource = bundle.getString(key);
      } catch (MissingResourceException e) {
        resource = key;
      }
      matcher.appendReplacement(sb, resource);
    }
    matcher.appendTail(sb);
  }
}

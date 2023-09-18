package fmfi;

import com.sun.mail.smtp.SMTPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.util.Properties;

public class UnibaMailer {
    private static final Logger logger = LoggerFactory.getLogger(UnibaMailer.class);
    private final Session mailSession;
    private final InternetAddress mailFrom;

    UnibaMailer() {
        final String smtpHost = System.getProperty("smtp.host");
        final String smtpPort = System.getProperty("smtp.port");
        final String smtpMail = System.getProperty("smtp.mail");
        final String smtpPass = System.getProperty("smtp.pass");

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        mailSession = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpMail, smtpPass);
            }
        });
        try {
            mailFrom = new InternetAddress(smtpMail);
        } catch (AddressException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param aisName
     * @param subject
     * @param body
     * @return if sending mail was successful
     */
    public boolean sendMail(String aisName, String subject, String body) {
        var mailTo = new InternetAddress[0]; // can be any email id
        try {
            String commaFreeAisName = aisName.replaceAll(",", "");
            mailTo = InternetAddress.parse(commaFreeAisName + "@uniba.sk");
        } catch (AddressException e) {
            logger.warn("Failed to parse an email address", e);
            return false;
        }
        try {
            Message message = new SMTPMessage(mailSession);
            message.setFrom(mailFrom);
            message.setRecipients(Message.RecipientType.TO, mailTo);
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);

            return true;
        } catch (MessagingException e) {
            logger.warn("Failed to send an email", e);
            return false;
        }
    }
}

package dev.tlang.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import dev.tlang.lexer.Token;
import dev.tlang.types.Type;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;

public final class MailModule implements NativeModule {
    private final Map<String, Object> exports = new LinkedHashMap<>();

    public MailModule() {
        exports.put("send", new NativeFunction("send", 3) {
            @Override
            public Object call(List<Object> args, Token token) {
                Object toObj = args.get(0);
                Object subjectObj = args.get(1);
                Object bodyObj = args.get(2);

                if (Type.of(toObj) != Type.STRING) {
                    throw new RuntimeError(token, "First argument to 'send' must be a string.");
                }
                if (Type.of(subjectObj) != Type.STRING) {
                    throw new RuntimeError(token, "Second argument to 'send' must be a string.");
                }
                if (Type.of(bodyObj) != Type.STRING) {
                    throw new RuntimeError(token, "Third argument to 'send' must be a string.");
                }

                String to = (String) toObj;
                String subject = (String) subjectObj;
                String body = (String) bodyObj;

                // Load config from ConfigModule
                Map<String, Object> configExports = ModuleRegistry.getModule("config");
                if (configExports == null) {
                    throw new RuntimeError(token, "Required module 'config' is not registered.");
                }
                NativeFunction getFn = (NativeFunction) configExports.get("get");
                NativeFunction requireFn = (NativeFunction) configExports.get("require");
                if (getFn == null || requireFn == null) {
                    throw new RuntimeError(token, "Required config functions ('get', 'require') not found.");
                }

                String host = (String) requireFn.call(List.of("SMTP_HOST"), token);
                String portStr = (String) requireFn.call(List.of("SMTP_PORT"), token);
                String username = (String) getFn.call(List.of("SMTP_USERNAME"), token);
                String password = (String) getFn.call(List.of("SMTP_PASSWORD"), token);
                String secure = (String) getFn.call(List.of("SMTP_SECURE"), token);

                int port;
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    throw new RuntimeError(token, "Invalid SMTP_PORT: must be an integer.");
                }

                Properties props = new Properties();
                props.put("mail.smtp.host", host);
                props.put("mail.smtp.port", String.valueOf(port));

                boolean useAuth = (username != null && !username.isEmpty());
                if (useAuth) {
                    props.put("mail.smtp.auth", "true");
                }

                if ("ssl".equalsIgnoreCase(secure)) {
                    props.put("mail.smtp.ssl.enable", "true");
                    props.put("mail.smtp.socketFactory.port", String.valueOf(port));
                    props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                    props.put("mail.smtp.socketFactory.fallback", "false");
                } else if ("tls".equalsIgnoreCase(secure) || "starttls".equalsIgnoreCase(secure)) {
                    props.put("mail.smtp.starttls.enable", "true");
                    props.put("mail.smtp.starttls.required", "true");
                }

                Authenticator authenticator = null;
                if (useAuth) {
                    authenticator = new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(username, password);
                        }
                    };
                }

                Session session = Session.getInstance(props, authenticator);

                try {
                    Message message = new MimeMessage(session);
                    String from = (username != null && username.contains("@")) ? username : "noreply@tlang.dev";
                    message.setFrom(new InternetAddress(from));
                    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                    message.setSubject(subject);
                    message.setText(body);

                    Transport.send(message);
                } catch (javax.mail.AuthenticationFailedException e) {
                    throw new RuntimeError(token, "SMTP authentication failed: " + e.getMessage());
                } catch (javax.mail.MessagingException e) {
                    Throwable cause = e.getCause();
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException || msg.contains("Could not connect to SMTP host")) {
                        throw new RuntimeError(token, "SMTP connection failed: " + msg);
                    } else {
                        throw new RuntimeError(token, "SMTP message sending failed: " + msg);
                    }
                } catch (Exception e) {
                    throw new RuntimeError(token, "SMTP message sending failed: " + e.getMessage());
                }

                return null;
            }
        });
    }

    @Override
    public Map<String, Object> getExports() {
        return exports;
    }
}

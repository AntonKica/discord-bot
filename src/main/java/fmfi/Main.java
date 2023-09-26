package fmfi;


import com.sun.mail.smtp.SMTPMessage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.Message;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.security.SecureRandom;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Main extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String CommandAuth = "auth";
    private static final String CommandAuthAisName = "ais-name";
    private static final String CommandAuthRole = "role";
    private static final String CommandConf = "conf";
    private static final String CommandConfCode = "code";
    private static final int randomCodeLength = 60;

    private static final List<String> acceptedRoles = List.of("Informatika", "Matematika", "Fyzika");

    static Connection connection;
    static UnibaMailer mailer;

    public static void main(String[] args) throws InterruptedException, SQLException {
        mailer = new UnibaMailer();

        final String jdbcUrl = System.getProperty("jdbc.url");
        connection = DriverManager.getConnection(jdbcUrl);
        connection.setAutoCommit(true);

        // We don't need any intents for this bot. Slash commands work without any intents!
        JDA jda = JDABuilder.createLight(System.getProperty("discord.token"), Collections.emptyList())
                .addEventListeners(new Main())
                .setActivity(Activity.playing("Chilling"))
                .build();

        jda.awaitReady();
        // Sets the global command list to the provided commands (removing all others)
        jda.updateCommands().addCommands(
                Commands.slash(CommandAuth, "Request an authentication")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                        .addOption(OptionType.STRING, CommandAuthAisName, "Your AIS name", true)
                        .addOption(OptionType.ROLE, CommandAuthRole, "Requested role", true),
                Commands.slash(CommandConf, "Confirm the authentication")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_ROLES))
                        .addOption(OptionType.STRING, CommandConfCode, "Received code", true)
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        // make sure we handle the right command
        switch (event.getName()) {
            case CommandAuth -> {
                Role role = event.getOption(CommandAuthRole, OptionMapping::getAsRole);
                String discordName = event.getUser().getName();
                String aisName = event.getOption(CommandAuthAisName, OptionMapping::getAsString);
                logger.info("[AUTH REQUEST] User: {} | Ais: {} | Role: {} ", discordName, aisName, role.getName());

                if (!acceptedRoles.contains(role.getName())) {
                    logger.info("User {} requested invalid role {}", discordName, role);
                    hook.sendMessage("Requested role is not permitted, request following roles: " + String.join(", ", acceptedRoles)).setEphemeral(true).queue();
                    break;
                }

                // check if user already has assigned role
                List<String> commonRoles = event.getMember().getRoles().stream()
                        .map(Role::getName)
                        .filter(acceptedRoles::contains)
                        .toList();
                if (!commonRoles.isEmpty()) {
                    logger.info("User {} already has roles {}", discordName, commonRoles);
                    event.getHook().sendMessage("You already have assigned role: " + String.join(", ", commonRoles)).setEphemeral(true).queue();
                    break;
                }

                if(hasExistingRequest(discordName, hook)) {
                    break;
                }

                if(checkForTimeout(discordName, hook) || checkForTimeout(aisName, hook))
                    break;


                String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                var random = new SecureRandom();
                var sb = new StringBuilder();
                for(int i = 0; i  < randomCodeLength; ++i) {
                    sb.append(characters.charAt(random.nextInt(characters.length())));
                }
                String randomCode = sb.toString();

                try {
                    String query = "insert into VerificationRequest(discordName, roleId, code) values(?,?,?)";
                    PreparedStatement preparedStatement = connection.prepareStatement(query);
                    preparedStatement.setString(1, discordName);
                    preparedStatement.setString(2, role.getId());
                    preparedStatement.setString(3, randomCode);
                    preparedStatement.executeUpdate();
                    preparedStatement.close();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

                if(!mailer.sendMail(aisName, "FMFI Discord server verification code", randomCode)) {
                    event.getHook().sendMessage("Failed to send mail to your account").setEphemeral(true).queue();
                    break;
                }
                logger.info("Verification request for account {} has been sent to {}.", discordName, aisName);
                event.getHook().sendMessage("Your random code has been sent your university email").setEphemeral(true).queue();
            }
            case CommandConf -> {
                User discordUser = event.getUser();
                String discordName = discordUser.getName();
                String code = event.getOption(CommandConfCode, OptionMapping::getAsString);
                try {
                    String query = "select roleId from VerificationRequest where discordName = ? and code = ?";
                    PreparedStatement preparedStatement = connection.prepareStatement(query);
                    preparedStatement.setString(1, discordName);
                    preparedStatement.setString(2, code);
                    ResultSet resultSet = preparedStatement.executeQuery();

                    if(!resultSet.next()) {
                        logger.info("Failed to verify {} with wrong code {}", discordName, code);
                        event.getHook().sendMessage("Invalid verification code").setEphemeral(true).queue();
                        break;
                    }
                    String roleId = resultSet.getString("roleId");

                    resultSet.close();
                    preparedStatement.close();


                    Guild guild = event.getGuild();
                    Role role = guild.getRoleById(roleId);
                    guild.addRoleToMember(discordUser, role).queue();

                    event.getHook().sendMessage("Your role has been set to " + role.getName()).setEphemeral(true).queue();


                    query = "delete from VerificationRequest where discordName = ? and code = ?";
                    preparedStatement = connection.prepareStatement(query);
                    preparedStatement.setString(1, discordName);
                    preparedStatement.setString(2, code);
                    preparedStatement.execute();
                    preparedStatement.close();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            default -> {
                logger.warn("Received unknown slash command {}", event.getName());

            }
        }
    }

    /**
     * May be extended to check for AIS name as well
     * @param aisName
     * @param discordName
     * @param hook
     * @return
     */
    private boolean hasExistingRequest(String discordName, InteractionHook hook) {
        try{
            String query = "select 1 from VerificationRequest where discordName = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, discordName);
            ResultSet resultSet = preparedStatement.executeQuery();

            if(resultSet.next()) {
                hook.sendMessage("There's already a pending request for your discord account, check your email.").queue();
                return true;
            } else {
                return false;
            }

        } catch (SQLException e) {
            logger.error("Exception in checking for existing request.", e);
            hook.sendMessage("There was an error while processing your request.").queue();
            return true;
        }
    }

    private boolean checkForTimeout(String name, InteractionHook hook) {
        try {
            String query = "select suspendedFrom, suspendedCount from AccountTimeout where accountName = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, name);
            ResultSet resultSet = preparedStatement.executeQuery();

            int suspendedCount;
            LocalDateTime suspendedFrom;
            if(!resultSet.next()) {
                suspendedCount = 0;
                suspendedFrom = LocalDateTime.now();
                query = "insert into AccountTimeout(suspendedFrom, suspendedCount, accountName) values(?,?,?)";
            } else {
                suspendedCount = Math.min(resultSet.getInt("suspendedCount") + 1, 4);
                suspendedFrom = resultSet.getTimestamp("suspendedFrom").toLocalDateTime();
                query = "update AccountTimeout set suspendedFrom = ?, suspendedCount = ? where accountName = ?";
            }
            preparedStatement = connection.prepareStatement(query);
            preparedStatement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            preparedStatement.setInt(2, suspendedCount);
            preparedStatement.setString(3, name);
            preparedStatement.execute();
            preparedStatement.close();

            LocalDateTime suspendedUntil = getSuspendedTime(suspendedFrom, suspendedCount);
            if(suspendedUntil.isAfter(LocalDateTime.now())) {
                String suspendedUntilFormatted = suspendedUntil.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String message = MessageFormat.format("The account {0} is suspended until {1}.", name, suspendedUntilFormatted);
                hook.sendMessage(message).queue();
                return true;
            } else {
                return false;
            }
        } catch (SQLException e) {
            logger.error("Exception in checking for timeout.", e);
            hook.sendMessage("There was an error while processing your request.").queue();
            return true;
        }
    }

    @NotNull
    private static LocalDateTime getSuspendedTime(LocalDateTime suspendedFrom, int suspendedCount) {
        LocalDateTime suspendedUntil;
        switch (suspendedCount) {
            case 0 -> {
                suspendedUntil = LocalDateTime.MIN;
            }
            case 1 -> {
                suspendedUntil = suspendedFrom.plusMinutes(5);
            }
            case 2 -> {
                suspendedUntil = suspendedFrom.plusHours(1);
            }
            case 3 -> {
                suspendedUntil = suspendedFrom.plusDays(1);
            }
            default -> {
                suspendedUntil = LocalDateTime.MAX;
            }
        }
        return suspendedUntil;
    }
}
package fmfi;


import com.sun.mail.smtp.SMTPMessage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.Message;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.security.SecureRandom;
import java.sql.*;
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
        // make sure we handle the right command
        switch (event.getName()) {
            case CommandAuth -> {
                Role role = event.getOption(CommandAuthRole, OptionMapping::getAsRole);
                String discordName = event.getUser().getName();
                String aisName = event.getOption(CommandAuthAisName, OptionMapping::getAsString);
                logger.info("[AUTH REQUEST] User: {} | Ais: {} | Role: {} ", discordName, aisName, role.getName());

                if (!acceptedRoles.contains(role.getName())) {
                    logger.info("User {} requested invalid role {}", discordName, role);
                    event.getHook().sendMessage("Requested role is not permitted, request following roles: " + String.join(", ", acceptedRoles)).setEphemeral(true).queue();
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

                // todo verify repeated requests for Discord user and ais user as well

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
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            default -> {
                logger.warn("Received unknown slash command {}", event.getName());

            }
        }
    }
}
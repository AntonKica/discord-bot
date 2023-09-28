package fmfi;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CommandProcessorAuth implements CommandProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessorAuth.class);
    private static final int randomCodeLength = 60;
    private static final List<String> acceptedRoles = List.of("Informatika", "Matematika", "Fyzika");

    private final Connection connection;
    private final UnibaMailer mailer;
    CommandProcessorAuth(Connection connection, UnibaMailer mailer) {
        this.connection = connection;
        this.mailer = mailer;
    }

    @Override
    public boolean shouldHandle(String commandName) {
        return commandName.equals("auth");
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash("auth", "Request an authentication")
                .setGuildOnly(true)
                .addOption(OptionType.STRING, "ais-name", "Your AIS name", true)
                .addOption(OptionType.ROLE, "role", "Requested role", true);
    }

    public void process(SlashCommandInteractionEvent event) {
        InteractionHook hook = event.getHook();

        Role role = event.getOption("role", OptionMapping::getAsRole);
        String discordName = event.getUser().getName();
        String aisName = event.getOption("ais-name", OptionMapping::getAsString);
        logger.info("[AUTH REQUEST] User: {} | Ais: {} | Role: {} ", discordName, aisName, role.getName());
        if (!acceptedRoles.contains(role.getName())) {
            logger.info("User {} requested invalid role {}", discordName, role);
            hook.sendMessage("Requested role is not permitted, request following roles: " + String.join(", ", acceptedRoles)).setEphemeral(true).queue();
            return;
        }

        // check if user already has assigned role
        List<String> commonRoles = event.getMember().getRoles().stream()
                .map(Role::getName)
                .filter(acceptedRoles::contains)
                .toList();
        if (!commonRoles.isEmpty()) {
            logger.info("User {} already has roles {}", discordName, commonRoles);
            hook.sendMessage("You already have assigned role: " + String.join(", ", commonRoles)).setEphemeral(true).queue();
            return;
        }

        if(hasExistingRequest(discordName, hook)) {
            return;
        }

        if(checkForTimeout(discordName, hook) || checkForTimeout(aisName, hook))
            return;


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
            return;
        }
        logger.info("Verification request for account {} has been sent to {}.", discordName, aisName);
        event.getHook().sendMessage("Your random code has been sent your university email").setEphemeral(true).queue();
    }
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
                String message = MessageFormat.format("The account {0} is suspended until {1}.",
                        name,
                        formatLocalDateTime(suspendedUntil));
                hook.sendMessage(message).setEphemeral(true).queue();
                return true;
            } else {
                return false;
            }
        } catch (SQLException e) {
            logger.error("Exception in checking for timeout.", e);
            hook.sendMessage("There was an error while processing your request.").setEphemeral(true).queue();
            return true;
        }
    }
    @NotNull
    public static LocalDateTime getSuspendedTime(LocalDateTime suspendedFrom, int suspendedCount) {
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

    public static String formatLocalDateTime(LocalDateTime localDateTime) {
        return localDateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    }
}

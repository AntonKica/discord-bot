package fmfi;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class CommandProcessorConf implements CommandProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessorAuth.class);
    private final Connection connection;

    public CommandProcessorConf(Connection connection) {
        this.connection = connection;
    }

    @Override
    public boolean shouldHandle(String commandName) {
        return commandName.equals("conf");
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash("conf", "Confirm the authentication")
                .setGuildOnly(true)
                .addOption(OptionType.STRING, "code", "Received code", true);
    }

    @Override
    public void process(SlashCommandInteractionEvent event) {
        InteractionHook hook = event.getHook();

        User discordUser = event.getUser();
        String discordName = discordUser.getName();
        String code = event.getOption("code", OptionMapping::getAsString);
        try {
            String query = "select roleId from VerificationRequest where discordName = ? and code = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, discordName);
            preparedStatement.setString(2, code);
            ResultSet resultSet = preparedStatement.executeQuery();

            if(!resultSet.next()) {
                logger.info("Failed to verify {} with wrong code {}", discordName, code);
                hook.sendMessage("Invalid verification code").setEphemeral(true).queue();
                return;
            }
            String roleId = resultSet.getString("roleId");

            resultSet.close();
            preparedStatement.close();


            Guild guild = event.getGuild();
            Role role = guild.getRoleById(roleId);
            guild.addRoleToMember(discordUser, role).queue();

            hook.sendMessage("Your role has been set to " + role.getName()).setEphemeral(true).queue();

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
}

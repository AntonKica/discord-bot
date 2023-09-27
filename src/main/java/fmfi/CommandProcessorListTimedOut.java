package fmfi;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CommandProcessorListTimedOut implements CommandProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessorListTimedOut.class);
    private final Connection connection;

    public CommandProcessorListTimedOut(Connection connection) {
        this.connection = connection;
    }

    @Override
    public boolean shouldHandle(String commandName) {
        return commandName.equals("list-timed-out");
    }

    @Override
    public CommandData getCommandData() {
        return Commands.slash("list-timed-out", "List timed out")
                .setGuildOnly(true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    @Override
    public void process(SlashCommandInteractionEvent event) {
        InteractionHook hook = event.getHook();

        String query = "select accountName, suspendedFrom, suspendedCount from AccountTimeout order by accountName";
        try {
            var message = new StringBuilder();
            message.append("Account | Suspended from | Suspended count\n" +
                    "-------------------------------------------\n");
            ResultSet result = connection.createStatement().executeQuery(query);

            while(result.next()) {
                String accountName = result.getString(1);
                Timestamp suspendedFrom = result.getTimestamp(2);
                int suspendedCount = result.getInt(3);

                LocalDateTime suspendedUntil =
                        CommandProcessorAuth.getSuspendedTime(suspendedFrom.toLocalDateTime(), suspendedCount);

                String row = MessageFormat.format("{0} | {1} | {2}\n",
                        accountName,
                        CommandProcessorAuth.formatLocalDateTime(suspendedUntil),
                        suspendedCount);

                if(message.length() + row.length() >= Message.MAX_CONTENT_LENGTH) {
                    hook.sendMessage(message.toString()).setEphemeral(true).queue();
                    message.setLength(0);
                }
                message.append(row);
            }

            if(!message.isEmpty())
                hook.sendMessage(message.toString()).setEphemeral(true).queue();

        } catch (SQLException e) {
            hook.sendMessage("There was an error in listing timed out accounts.").queue();
            logger.error("Error in listing timed out accounts.", e);
        }
    }
}

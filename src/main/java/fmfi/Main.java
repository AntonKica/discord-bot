package fmfi;


import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class Main extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    static Connection connection;
    static UnibaMailer mailer;
    static List<CommandProcessor> commandProcessors;

    public static void main(String[] args) throws InterruptedException, SQLException {
        mailer = new UnibaMailer();

        final String jdbcUrl = System.getProperty("jdbc.url");
        connection = DriverManager.getConnection(jdbcUrl);
        connection.setAutoCommit(true);

        commandProcessors = List.of(
                new CommandProcessorAuth(connection, mailer),
                new CommandProcessorConf(connection)
        );
        // We don't need any intents for this bot. Slash commands work without any intents!
        JDA jda = JDABuilder.createLight(System.getProperty("discord.token"), Collections.emptyList())
                .addEventListeners(new Main())
                .setActivity(Activity.playing("Chilling"))
                .build();

        jda.awaitReady();
        // Sets the global command list to the provided commands (removing all others)
        List<CommandData> commandData = commandProcessors.stream().map(CommandProcessor::getCommandData).toList();
        jda.updateCommands().addCommands(commandData).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        event.deferReply(true).queue();

        commandProcessors.stream()
                .filter(c -> c.shouldHandle(event.getName()))
                .forEach(c -> c.process(event));
    }

}
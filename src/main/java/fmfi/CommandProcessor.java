package fmfi;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

public interface CommandProcessor {
    boolean shouldHandle(String commandName);
    CommandData getCommandData();
    void process(SlashCommandInteractionEvent event);
}

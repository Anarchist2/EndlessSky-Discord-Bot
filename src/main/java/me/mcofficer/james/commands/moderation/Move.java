package me.mcofficer.james.commands.moderation;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import me.mcofficer.james.James;
import me.mcofficer.james.Util;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Move extends Command {

    private static final Logger log = LoggerFactory.getLogger(Move.class);

    public Move() {
        name = "move";
        help = "Moves X messages to Channel C. Removes Embeds in the process.";
        arguments = "C X";
        aliases = new String[]{"wormhole"};
        category = James.moderation;
    }

    @Override
    protected void execute(CommandEvent event) {
        String[] args = event.getArgs().split(" ");
        int amount;
        TextChannel dest = event.getMessage().getMentionedChannels().get(0);
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            event.reply("Failed to parse \"" + args[1] + "\"as Integer!");
            return;
        }

        if (event.getMember().hasPermission(event.getTextChannel(), Permission.MESSAGE_MANAGE)
                && event.getMember().hasPermission(dest, Permission.MESSAGE_WRITE)
                && event.getGuild().getSelfMember().hasPermission(dest, Permission.MESSAGE_WRITE)) {
            event.getMessage().delete().complete();

            // Use a lambda to asynchronously perform this request:
            event.getTextChannel().getIterableHistory().takeAsync(amount).thenAccept(toDelete -> {
                if (toDelete.isEmpty())
                    return;
                LinkedList<String> toMove = new LinkedList<>();
                for (Message m : toDelete) {
                    String authorName = Optional.ofNullable(m.getMember()).map(Member::getEffectiveName).orElse("unknown author");
                    String content = m.getContentStripped().trim();
                    if (content.isEmpty())
                        continue;
                    toMove.addFirst(m.getTimeCreated()
                            .format(DateTimeFormatter.ISO_INSTANT).substring(11, 19)
                            + "Z " + authorName + ": " + content + "\n"
                    );
                }
                // Remove the messages from the original channel and log the move.
                AtomicInteger counter = new AtomicInteger();
                toDelete.stream()
                        .collect(Collectors.groupingBy(x -> counter.getAndIncrement() / 100))
                        .values()
                        .forEach(chunk -> {
                            try {
                                event.getTextChannel().deleteMessages(chunk).complete();
                            } catch (IllegalArgumentException e) {
                                event.reply("Encountered an error while moving messages: " + e.getMessage());
                            }
                        });

                EmbedBuilder log = new EmbedBuilder();
                log.setDescription(dest.getAsMention());
                log.setThumbnail("https://cdn.discordapp.com/emojis/344684586904584202.png");
                log.appendDescription("\n(" + toMove.size() + " messages await)");
                if (toDelete.size() - toMove.size() > 0)
                    log.appendDescription("\n(Some embeds were eaten)");
                event.getTextChannel().sendMessage(log.build()).queue();

                // Transport the message content to the new channel.
                if (!toMove.isEmpty())
                    Util.sendInChunks(dest, toMove, "Incoming wormhole content from " + event.getTextChannel().getAsMention() + ":\n```", "```");

                // Log the move in mod-log.
                String report = "Moved " + toMove.size() +
                        " messages from " + event.getTextChannel().getAsMention() +
                        " to " + dest.getAsMention() + ", ordered by `" +
                        event.getMember().getEffectiveName() + "`.";
                Util.log(event.getGuild(), report);
            }).exceptionally(e -> {
                        log.error("Failed to to move messages", e);
                        return null;
                    }
            );
        } else
            event.reply(Util.getRandomDeniedMessage());
    }
}

package com.workingdead.config;

import com.workingdead.chatbot.discord.command.DiscordWendyCommand;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscordBotConfig {

    @Value("${discord.token}")
    private String discordToken;

    private JDA jda;

    @Bean
    public JDA jda(DiscordWendyCommand discordWendyCommand) throws Exception {
        jda = JDABuilder.createDefault(discordToken)
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS,
                GatewayIntent.GUILD_MEMBERS
            )
            .setMemberCachePolicy(MemberCachePolicy.ALL)
            .addEventListeners(discordWendyCommand)
            .build()
            .awaitReady();

        System.out.println("[Discord Wendy Bot] Started! Server count: " + jda.getGuilds().size());
        return jda;
    }

    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
            System.out.println("[Discord Wendy Bot] Shutdown");
        }
    }
}

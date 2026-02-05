package com.workingdead.chatbot.discord.scheduler;

import com.workingdead.chatbot.discord.service.DiscordWendyNotifier;
import com.workingdead.chatbot.discord.service.DiscordWendyNotifier.RemindTiming;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class DiscordWendyScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final DiscordWendyNotifier notifier;
    private final Map<String, List<ScheduledFuture<?>>> channelTasks = new ConcurrentHashMap<>();

    public DiscordWendyScheduler(DiscordWendyNotifier notifier) {
        this.notifier = notifier;
    }

    public void startSchedule(TextChannel channel) {
        String channelId = channel.getId();
        stopSchedule(channelId);

        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

        // 투표 현황: 10분 후 첫 공유
        tasks.add(scheduler.schedule(() -> notifier.shareVoteStatus(channel), 10, TimeUnit.MINUTES));

        // 미투표자 독촉
        tasks.add(scheduler.schedule(() -> notifier.remindNonVoters(channel, RemindTiming.MIN_15), 15, TimeUnit.MINUTES));
        tasks.add(scheduler.schedule(() -> notifier.remindNonVoters(channel, RemindTiming.HOUR_1), 1, TimeUnit.HOURS));
        tasks.add(scheduler.schedule(() -> notifier.remindNonVoters(channel, RemindTiming.HOUR_6), 6, TimeUnit.HOURS));
        tasks.add(scheduler.schedule(() -> notifier.remindNonVoters(channel, RemindTiming.HOUR_12), 12, TimeUnit.HOURS));
        tasks.add(scheduler.schedule(() -> notifier.remindNonVoters(channel, RemindTiming.HOUR_24), 24, TimeUnit.HOURS));

        channelTasks.put(channelId, tasks);
        System.out.println("[Discord Scheduler] Schedule started: " + channelId);
    }

    public void stopSchedule(String channelId) {
        List<ScheduledFuture<?>> tasks = channelTasks.remove(channelId);
        if (tasks != null) {
            tasks.forEach(task -> task.cancel(false));
            System.out.println("[Discord Scheduler] Schedule stopped: " + channelId);
        }
    }
}
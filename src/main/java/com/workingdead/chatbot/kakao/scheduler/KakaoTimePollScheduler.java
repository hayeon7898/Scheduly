package com.workingdead.chatbot.kakao.scheduler;

import com.workingdead.chatbot.kakao.service.KakaoTimePollNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
@Slf4j
public class KakaoTimePollScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final KakaoTimePollNotifier notifier;
    private final Map<Long, List<ScheduledFuture<?>>> tasks = new ConcurrentHashMap<>();

    public KakaoTimePollScheduler(@Lazy KakaoTimePollNotifier notifier) {
        this.notifier = notifier;
    }

    public void startSchedule(Long timePollId, String botGroupKey) {
        if (tasks.containsKey(timePollId)) {
            log.warn("[TimePollScheduler] Already scheduled. Skip. timePollId={}", timePollId);
            return;
        }

        CopyOnWriteArrayList<ScheduledFuture<?>> list = new CopyOnWriteArrayList<>();
        // 3분 후 집계 시작
        list.add(scheduler.schedule(
                () -> notifier.shareTimePollStatus(timePollId, botGroupKey),
                3, TimeUnit.MINUTES
        ));

        // 3분마다 과반 체크 (3분 안에 과반 시 조기 집계)
        // list.add(scheduler.scheduleAtFixedRate(
        //         () -> notifier.checkMajorityVoted(timePollId, botGroupKey),
        //         1, 1, TimeUnit.MINUTES
        // ));

        // 독촉: 30분, 2시간, 6시간, 12시간
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "30min"),
                4, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "2hour"),
                5, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "6hour"),
                6, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "12hour"),
                7, TimeUnit.MINUTES
        ));

        // 최후통첩: 24시간
        list.add(scheduler.schedule(
                () -> notifier.sendUltimatum(timePollId, botGroupKey),
                8, TimeUnit.MINUTES
        ));

        // 최후통첩 후 60분 → 자동 확정
        list.add(scheduler.schedule(
                () -> notifier.finalizeIfNoResponse(timePollId, botGroupKey),
                11, TimeUnit.MINUTES
        ));

        // 5분마다 전원 투표 완료 체크
        list.add(scheduler.scheduleAtFixedRate(
                () -> notifier.checkAllVoted(timePollId, botGroupKey),
                5, 5, TimeUnit.MINUTES
        ));

        tasks.put(timePollId, list);
        log.info("[TimePollScheduler] Schedule started: timePollId={}, botGroupKey={}", timePollId, botGroupKey);
    }

    public void stopSchedule(Long timePollId) {
        List<ScheduledFuture<?>> list = tasks.remove(timePollId);
        if (list != null) {
            list.forEach(t -> t.cancel(false));
            log.info("[TimePollScheduler] Schedule stopped: timePollId={}", timePollId);
        }
    }

    public boolean hasActiveSchedule(Long timePollId) {
        return tasks.containsKey(timePollId);
    }
}
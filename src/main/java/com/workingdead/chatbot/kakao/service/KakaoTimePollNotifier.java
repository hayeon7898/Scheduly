package com.workingdead.chatbot.kakao.service;

import com.workingdead.chatbot.kakao.dto.KakaoResponse;
import com.workingdead.chatbot.kakao.scheduler.KakaoTimePollScheduler;
import com.workingdead.meet.entity.Participant;
import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.repository.TimePollRepository;
import com.workingdead.meet.dto.response.TimePollStatusResponse;
import com.workingdead.meet.service.TimePollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class KakaoTimePollNotifier {

    private final KakaoBotApiClient kakaoBotApiClient;
    private final TimePollService timePollService;
    private final TimePollRepository timePollRepository;
    private final KakaoTimePollScheduler kakaoTimePollScheduler;

    public KakaoTimePollNotifier(
            KakaoBotApiClient kakaoBotApiClient,
            TimePollService timePollService,
            TimePollRepository timePollRepository,
            @Lazy KakaoTimePollScheduler kakaoTimePollScheduler) {
        this.kakaoBotApiClient = kakaoBotApiClient;
        this.timePollService = timePollService;
        this.timePollRepository = timePollRepository;
        this.kakaoTimePollScheduler = kakaoTimePollScheduler;
    }
    /**
     * 3분 후 집계 시작 (과반 체크 포함)
     */
    public void shareTimePollStatus(Long timePollId, String botGroupKey) {
        try {
            TimePollStatusResponse status = timePollService.getStatus(timePollId);

            // 아무도 안 투표
            if (status.getSubmittedCount() == 0) {
                sendEventToGroup(botGroupKey, "status_nobody_voted_T");
                return;
            }

            // 전원 완료
            if (status.isAllSubmitted()) {
                timePollService.finalize(timePollId);
                sendEventToGroup(botGroupKey, "finish_T");
                kakaoTimePollScheduler.stopSchedule(timePollId);
                return;
            }

            // 일부 투표 → 현황 공유
            sendEventToGroup(botGroupKey, "status_T");

        } catch (Exception e) {
            log.error("[TimePollNotifier] shareTimePollStatus failed: {}", e.getMessage());
        }
    }

    /**
     * 과반 조기 체크 (1분마다)
     */
    public void checkMajorityVoted(Long timePollId, String botGroupKey) {
        try {
            if (timePollService.isMajoritySubmitted(timePollId)) {
                sendEventToGroup(botGroupKey, "status_T");
            }
        } catch (Exception e) {
            log.error("[TimePollNotifier] checkMajorityVoted failed: {}", e.getMessage());
        }
    }

    public void remindNonVoters(Long timePollId, String botGroupKey, String timing) {
        try {
            List<Participant> pending = timePollService.getPendingParticipants(timePollId);
            if (pending.isEmpty()) {
                log.info("[TimePollNotifier] No pending. Skip. timePollId={}", timePollId);
                return;
            }

            String eventName = switch (timing) {
                case "30min" -> "remind_T_30M";
                case "2hour" -> "remind_T_2H";
                case "6hour" -> "remind_T_6H";
                case "12hour" -> "remind_T_12H";
                default -> "remind_T_30M";
            };

            sendEventToGroup(botGroupKey, eventName);
            log.info("[TimePollNotifier] Remind sent: timePollId={}, timing={}", timePollId, timing);

        } catch (Exception e) {
            log.error("[TimePollNotifier] remindNonVoters failed: {}", e.getMessage());
        }
    }

    public void sendUltimatum(Long timePollId, String botGroupKey) {
        try {
            List<Participant> pending = timePollService.getPendingParticipants(timePollId);
            if (pending.isEmpty()) return;

            sendEventToGroup(botGroupKey, "final_T");
            Thread.sleep(2000);
            sendEventToGroup(botGroupKey, "final_T_buttons");
            //kakaoTimePollScheduler.stopSchedule(timePollId);
            log.info("[TimePollNotifier] Ultimatum sent: timePollId={}", timePollId);

        } catch (Exception e) {
            log.error("[TimePollNotifier] sendUltimatum failed: {}", e.getMessage());
        }
    }

    public void finalizeIfNoResponse(Long timePollId, String botGroupKey) {
        try {
            List<Participant> pending = timePollService.getPendingParticipants(timePollId);
            if (pending.isEmpty()) return;

            // timePollService.finalize(timePollId);
            // sendEventToGroup(botGroupKey, "finish_T");
            // kakaoTimePollScheduler.stopSchedule(timePollId);

            timePollService.finalize(timePollId);
            kakaoTimePollScheduler.stopSchedule(timePollId); // 먼저 중단
            sendEventToGroup(botGroupKey, "finish_T");       // 그 다음 이벤트 전송
            log.info("[TimePollNotifier] Auto-finalized: timePollId={}", timePollId);

        } catch (Exception e) {
            log.error("[TimePollNotifier] finalizeIfNoResponse failed: {}", e.getMessage());
        }
    }

    public void checkAllVoted(Long timePollId, String botGroupKey) {
        try {
            List<Participant> pending = timePollService.getPendingParticipants(timePollId);
            if (pending.isEmpty()) {
                timePollService.finalize(timePollId);
                sendEventToGroup(botGroupKey, "finish_T");
                kakaoTimePollScheduler.stopSchedule(timePollId);
                log.info("[TimePollNotifier] All voted! timePollId={}", timePollId);
            }
        } catch (Exception e) {
            log.error("[TimePollNotifier] checkAllVoted failed: {}", e.getMessage());
        }
    }

    private void sendEventToGroup(String botGroupKey, String eventName) {
        if (botGroupKey == null || botGroupKey.isBlank()) {
            log.warn("[TimePollNotifier] botGroupKey is empty.");
            return;
        }
        try {
            kakaoBotApiClient.sendEventMessage(botGroupKey, eventName);
            log.info("[TimePollNotifier] Event sent: botGroupKey={}, eventName={}", botGroupKey, eventName);
        } catch (Exception e) {
            log.error("[TimePollNotifier] sendEventToGroup failed: {}", e.getMessage());
        }
    }
}
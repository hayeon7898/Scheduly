package com.workingdead.chatbot.kakao.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workingdead.chatbot.kakao.dto.KakaoRequest;
import com.workingdead.chatbot.kakao.dto.KakaoResponse;
import com.workingdead.chatbot.kakao.service.KakaoWendyService;
import com.workingdead.chatbot.kakao.service.KakaoWendyService.SessionState;
import com.workingdead.chatbot.kakao.scheduler.KakaoTimePollScheduler;
import com.workingdead.meet.dto.response.TimePollStatusResponse;
import com.workingdead.meet.service.TimePollService;
import com.workingdead.timepoll.enums.Period;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalTime;

/**
 * 카카오 i 오픈빌더 스킬 서버 컨트롤러
 *
 * 카카오톡 챗봇에서 발화를 받아 처리하고 응답을 반환합니다. 
 * - 개인챗: userKey 기반 세션 
 * - 그룹챗: botGroupKey 기반 세션
 */
@Tag(name = "Kakao Chatbot", description = "카카오 챗봇 스킬 API")
@RestController
@RequestMapping("/kakao/skill")
@RequiredArgsConstructor
@Slf4j
public class KakaoSkillController {

    private final KakaoWendyService kakaoWendyService;
    private final TimePollService timePollService;
    private final ObjectMapper objectMapper;
    private final KakaoTimePollScheduler kakaoTimePollScheduler;

    /**
     * 세션 키 결정 (그룹챗이면 botGroupKey, 개인챗이면 userKey)
     */
    private String getSessionKey(KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        if (botGroupKey != null && !botGroupKey.isBlank()) {
            return botGroupKey;
        }
        return request.getUserKey();
    }

    /**
     * 메인 스킬 엔드포인트 (폴백 블록) 모든 발화를 여기서 처리
     */
    @Operation(summary = "메인 스킬 (폴백)")
    @PostMapping("/main")
    public ResponseEntity<KakaoResponse> handleMain(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        String botGroupKey = request.getBotGroupKey();
        String botUserKey = request.getBotUserKey();
        String utterance = request.getUtterance();

        try {
            log.info("[Kakao Skill Raw Request] {}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        } catch (Exception e) {
            log.warn("[Kakao Skill] Failed to log raw request: {}", e.getMessage());
        }

        log.info("[Kakao Skill] sessionKey={}, botGroupKey={}, botUserKey={}, utterance={}",
                sessionKey, botGroupKey, botUserKey, utterance);

        if (sessionKey == null || sessionKey.isBlank()) {
            log.warn("[Kakao Skill] Missing sessionKey. botGroupKey={}, userKey={}, botUserKey={}",
                    botGroupKey, request.getUserKey(), botUserKey);
            return ResponseEntity.ok(kakaoWendyService.help());
        }

        if (utterance == null || utterance.isBlank()) {
            return ResponseEntity.ok(kakaoWendyService.help());
        }

        // 그룹챗에서는 버튼 클릭도 "@봇이름 내용" 형태로 발화가 오므로, 앞의 멘션은 떼고 매칭한다.
        // 예: "@스케쥴리 참여할래요" → "참여할래요", "@스케쥴리"(멘션만) → ""
        String trimmed = utterance.trim().replaceFirst("^@\\S+\\s*", "");

        // 멘션만 하고 아무 내용도 없으면 도움말을 보여준다.
        if (trimmed.isBlank()) {
            return ResponseEntity.ok(kakaoWendyService.help());
        }

        // 0. 참여 등록 ("참여" 버튼 클릭 시 오는 발화. 24시간 수집 창 동안에만 의미가 있음)
        //    각 사용자가 버튼을 누르면 "그 사람만의" 요청이 오므로, botUserKey로 그 사람을 식별해 누적한다.
        //    버튼의 messageText가 무시되고 label("참여할래요")이 그대로 전송되는 것으로 보여, 둘 다 받아준다.
        if (trimmed.equals("참여") || trimmed.equals("참여할래요")) {
            return ResponseEntity.ok(kakaoWendyService.joinPendingSession(sessionKey, botUserKey));
        }

        // 0-1. 모집 조기 종료 ("모집 종료할게요" 버튼 클릭 시)
        if (trimmed.equals("모집종료") || trimmed.equals("모집 종료할게요")) {
            return ResponseEntity.ok(kakaoWendyService.closeCollectionNow(sessionKey));
        }

        // 1. 시작
        if (trimmed.equals("시작")) {
            return ResponseEntity.ok(kakaoWendyService.startSession(sessionKey, botGroupKey));
        }

        // 2. 도움말
        if (trimmed.equals("도움말")) {
            return ResponseEntity.ok(kakaoWendyService.help());
        }

        // 3. 종료
        if (trimmed.equals("종료")) {
            return ResponseEntity.ok(kakaoWendyService.endSession(sessionKey));
        }

        // 4. 결과
        if (trimmed.equals("결과")) {
            return ResponseEntity.ok(kakaoWendyService.getVoteResult(sessionKey));
        }

        // 5. 재투표
        if (trimmed.equals("재투표") || trimmed.equals("재투표할래요")) {
            return ResponseEntity.ok(kakaoWendyService.revote(sessionKey));
        }

        // 세션 상태에 따른 처리 ("시작" 이후 WAITING_WEEKS 상태에서 "이번"/"1주" 등
        // 기간 텍스트가 오면 여기서 주차로 파싱함)
        SessionState state = kakaoWendyService.getSessionState(sessionKey);

        switch (state) {
            case WAITING_PARTICIPANTS:
                // 24시간 참여자 수집 창이 열려있는 동안, "참여" 외의 다른 발화가 들어온 경우
                return ResponseEntity.ok(KakaoResponse.simpleText(
                        "지금은 참여자를 모으는 중이에요! \"참여\" 버튼을 눌러주세요 :D"
                ));

            case WAITING_WEEKS:
                // 주차 선택
                Integer weeks = kakaoWendyService.parseWeeks(trimmed);
                if (weeks != null) {
                    KakaoResponse response = kakaoWendyService.startCollecting(sessionKey, weeks, botGroupKey);
                    return ResponseEntity.ok(response);
                }
                break;

            default:
                break;
        }

        // 알 수 없는 입력
        return ResponseEntity.ok(kakaoWendyService.unknownInput(sessionKey));
    }

    /**
     * 웬디 시작 스킬 (전용 블록)
     */
    @Operation(summary = "웬디 시작")
    @PostMapping("/start")
    public ResponseEntity<KakaoResponse> handleStart(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        String botGroupKey = request.getBotGroupKey();
        try {
            log.info("[Kakao Skill Raw Request - START] {}",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        } catch (Exception e) {
            log.warn("Failed to log raw request: {}", e.getMessage());
        }
        log.info("[Kakao Skill] START - sessionKey={}, botGroupKey={}", sessionKey, botGroupKey);
        return ResponseEntity.ok(kakaoWendyService.startSession(sessionKey, botGroupKey));
    }

    /**
     * 참여 등록 스킬 (전용 블록으로 분리하고 싶다면 이걸 사용. 지금은 handleMain의 "참여" 분기가 처리 중)
     */
    @Operation(summary = "참여 등록")
    @PostMapping("/participants")
    public ResponseEntity<KakaoResponse> handleParticipants(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        String botUserKey = request.getBotUserKey();
        log.info("[Kakao Skill] PARTICIPANTS - sessionKey={}, botUserKey={}", sessionKey, botUserKey);
        return ResponseEntity.ok(kakaoWendyService.joinPendingSession(sessionKey, botUserKey));
    }

    /**
     * 웬디 종료 스킬 (전용 블록)
     */
    @Operation(summary = "웬디 종료")
    @PostMapping("/end")
    public ResponseEntity<KakaoResponse> handleEnd(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] END - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.endSession(sessionKey));
    }

    /**
     * 주차 선택 스킬 (전용 블록) params에서 weeks 값을 받음
     */
    @Operation(summary = "주차 선택")
    @PostMapping("/select-week")
    public ResponseEntity<KakaoResponse> handleSelectWeek(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        String botGroupKey = request.getBotGroupKey();
        String weeksParam = request.getParam("weeks");

        log.info("[Kakao Skill] SELECT_WEEK - sessionKey={}, botGroupKey={}, weeksParam={}", sessionKey, botGroupKey, weeksParam);

        if (sessionKey == null || sessionKey.isBlank()) {
            log.warn("[Kakao Skill] SELECT_WEEK missing sessionKey. botGroupKey={}, userKey={}",
                    botGroupKey, request.getUserKey());
            return ResponseEntity.ok(KakaoResponse.simpleText("세션 정보를 확인하지 못했어요. 다시 시작해 주세요."));
        }
        // 상태 검증: 주차 선택 단계에서만 허용
        SessionState state = kakaoWendyService.getSessionState(sessionKey);
        if (state != SessionState.WAITING_WEEKS) {
            log.warn("[Kakao Skill] SELECT_WEEK called in invalid state. sessionKey={}, state={}", sessionKey, state);
            return ResponseEntity.ok(kakaoWendyService.unknownInput(sessionKey));
        }

        // weeks 파싱 (param 우선, 없으면 utterance로 보조)
        String candidate = (weeksParam != null && !weeksParam.isBlank()) ? weeksParam : request.getUtterance();
        Integer weeks = (candidate == null) ? null : kakaoWendyService.parseWeeks(candidate.trim());

        if (weeks == null || weeks < 0) {
            log.warn("[Kakao Skill] SELECT_WEEK invalid weeks. sessionKey={}, candidate={}", sessionKey, candidate);
            return ResponseEntity.ok(KakaoResponse.simpleText("주차 선택 값을 확인하지 못했어요. 다시 선택해 주세요."));
        }

        // 주차를 선택하면 즉시 투표를 만들지 않고, 24시간 참여자 수집을 시작한다.
        KakaoResponse response = kakaoWendyService.startCollecting(sessionKey, weeks, botGroupKey);
        return ResponseEntity.ok(response);
    }

    /**
     * 결과 조회 스킬 (전용 블록)
     */
    @Operation(summary = "투표 결과 조회")
    @PostMapping("/result")
    public ResponseEntity<KakaoResponse> handleResult(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] RESULT - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.getVoteResult(sessionKey));
    }

    /**
     * 재투표 스킬 (전용 블록)
     */
    @Operation(summary = "재투표")
    @PostMapping("/revote")
    public ResponseEntity<KakaoResponse> handleRevote(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] REVOTE - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.revote(sessionKey));
    }

    /**
     * 독촉 알림 스킬 (전용 블록)
     */
    @Operation(summary = "독촉 알림")
    @PostMapping("/notify/remind")
    public ResponseEntity<KakaoResponse> handleRemind(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        String botGroupKey = request.getBotGroupKey();
        String timing = request.getParam("timing");

        log.info("[Kakao Skill] REMIND - sessionKey={}, timing={}, botGroupKey={}", sessionKey, timing, botGroupKey);
        return ResponseEntity.ok(kakaoWendyService.buildRemindResponse(sessionKey, timing));
    }

    /**
     * 참여자 수집 완료 알림 (6시간 경과 후 Event API가 트리거하는 블록의 스킬)
     */
    @Operation(summary = "참여자 수집 완료 알림")
    @PostMapping("/notify/vote-created")
    public ResponseEntity<KakaoResponse> handleVoteCreatedNotice(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] VOTE_CREATED - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.buildVoteCreatedResponse(sessionKey));
    }

    /**
     * 최후통첩 (24시간)
     */
    @PostMapping("/notify/final-notice")
    public ResponseEntity<KakaoResponse> handleFinalNotice(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] FINAL_NOTICE - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.buildFinalNoticeResponse(sessionKey));
    }

    /**
     * 완료 (25시간)
     */
    @PostMapping("/notify/final")
    public ResponseEntity<KakaoResponse> handleFinalize(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] FINALIZE - sessionKey={}", sessionKey);
        return ResponseEntity.ok(kakaoWendyService.buildCompletionResponse(sessionKey));
    }

    /**
     * 도움말 스킬 (전용 블록)
     */
    @Operation(summary = "도움말")
    @PostMapping("/help")
    public ResponseEntity<KakaoResponse> handleHelp(@RequestBody KakaoRequest request) {
        log.info("[Kakao Skill] HELP - sessionKey={}", getSessionKey(request));
        return ResponseEntity.ok(kakaoWendyService.help());
    }

    /**
     * 헬스체크 (카카오 스킬 서버 상태 확인용)
     */
    @Operation(summary = "헬스체크")
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 참석자 botUserKey 목록 추출 - 오픈빌더의 "발화에서 멘션된 유저 식별" 결과를 params로 전달받는 것을 1순위로 사용
     * - 지원 키: botUserKeys / participants / mentionedUserKeys - 값 형태: "k1,k2,k3"
     * 또는 "k1 k2 k3" 등(구분자는 콤마/공백/개행 모두 허용)
     *
     * (참고: 현재 참여자 수집은 handleMain의 "참여" 분기로 대체되어, 이 헬퍼는 더 이상 호출되지 않습니다.
     *  다른 용도로 재활용할 수 있어 남겨둡니다.)
     */
    private String extractParticipantKeys(KakaoRequest request, String fallbackUtterance) {
        String raw = firstNonBlank(
                request.getParam("botUserKeys"),
                request.getParam("participants"),
                request.getParam("mentionedUserKeys")
        );

        if (raw == null || raw.isBlank()) {
            raw = (fallbackUtterance == null) ? "" : fallbackUtterance;
        }

        return normalizeKeys(raw);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 다양한 구분자(콤마/공백/개행)를 콤마 구분 문자열로 정규화
     */
    private String normalizeKeys(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        String[] parts = trimmed.split("[\\s,]+");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String s = p.trim();
            if (!s.isEmpty()) {
                set.add(s);
            }
        }
        return String.join(",", set);
    }

    @PostMapping("/time-poll")
    public ResponseEntity<KakaoResponse> handleTimePoll(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        Long voteId = kakaoWendyService.getVoteIdBySessionKey(sessionKey);
        if (voteId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 투표가 없어요."));
        }

        String timePollUrl;
        try {
            timePollUrl = kakaoWendyService.createTimePoll(sessionKey, voteId);
        } catch (RuntimeException e) {
            log.warn("[TimePoll] 생성 실패: {}", e.getMessage());
            return ResponseEntity.ok(KakaoResponse.simpleText("아직 투표 결과가 없어요. 잠시 후 다시 시도해주세요."));
        }

        return ResponseEntity.ok(KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .textCard(KakaoResponse.BasicCard.builder()
                                                .title("그럼 이제 몇 시에 만날지 정해보죠!")
                                                .description("투표를 만들어드렸어요🙂")
                                                .buttons(List.of(
                                                        KakaoResponse.Button.builder()
                                                                .label("투표하기")
                                                                .action("webLink")
                                                                .webLinkUrl(timePollUrl)
                                                                .build()
                                                ))
                                                .build())
                                        .build()
                        ))
                        .build())
                .build());
    }

    @PostMapping("/time-poll/notify/remind")
    public ResponseEntity<KakaoResponse> handleTimePollRemind(@RequestBody KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        String timing = request.getParam("timing");

        Long timePollId = kakaoWendyService.getTimePollIdByBotGroupKey(botGroupKey);
        if (timePollId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 시간 투표가 없어요."));
        }

        return ResponseEntity.ok(kakaoWendyService.buildTimeRemindResponse(timePollId, botGroupKey, timing));
    }

    @PostMapping("/time-poll/notify/final-notice")
    public ResponseEntity<KakaoResponse> handleTimePollFinalNotice(@RequestBody KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        Long timePollId = kakaoWendyService.getTimePollIdByBotGroupKey(botGroupKey);
        if (timePollId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 시간 투표가 없어요."));
        }
        return ResponseEntity.ok(kakaoWendyService.buildTimeFinalNoticeResponse(timePollId, botGroupKey));
    }

    @PostMapping("/time-poll/notify/status")
    public ResponseEntity<KakaoResponse> handleTimePollStatus(@RequestBody KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        Long timePollId = kakaoWendyService.getTimePollIdByBotGroupKey(botGroupKey);

        if (timePollId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 시간 투표가 없어요."));
        }

        TimePollStatusResponse status;
        try {
            status = timePollService.getStatus(timePollId);
        } catch (IllegalArgumentException e) {
            log.warn("[TimePoll] 상태 조회 실패: timePollId={}", timePollId);
            return ResponseEntity.ok(KakaoResponse.simpleText("투표 정보를 찾을 수 없어요."));
        }

        if (status.getSubmittedCount() == 0) {
            return ResponseEntity.ok(KakaoResponse.simpleText(
                    "스케쥴리가 투표 현황을 공유드려요! :D\n\n엥 아직 아무도 투표를 안 했네요 :("
            ));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("스케쥴리가 투표 현황을 공유드려요! :D\n\n");
        sb.append("[").append(status.getConfirmedDate()).append(" ")
                .append("LUNCH".equals(status.getPeriod().name()) ? "점심" : "저녁").append("]\n");
        for (TimePollStatusResponse.EntryDto entry : status.getEntries()) {
            sb.append(entry.getDisplayName()).append(": ")
                    .append(formatTime(entry.getSelectedTime())).append("\n");
        }

        return ResponseEntity.ok(KakaoResponse.simpleText(sb.toString().trim()));
    }

    private String formatTime(LocalTime time) {
        int hour = time.getHour();
        int minute = time.getMinute();
        String suffix = hour >= 12 ? "오후 " : "오전 ";
        int displayHour = hour > 12 ? hour - 12 : hour;
        if (minute == 30) {
            return suffix + displayHour + "시반";
        }
        if (minute == 0) {
            return suffix + displayHour + "시";
        }
        return suffix + displayHour + "시 " + minute + "분";
    }

    @PostMapping("/time-poll/notify/finish")
    public ResponseEntity<KakaoResponse> handleTimePollFinish(@RequestBody KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        Long timePollId = kakaoWendyService.getTimePollIdByBotGroupKey(botGroupKey);

        if (timePollId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 시간 투표가 없어요."));
        }

        timePollService.finalize(timePollId);
        kakaoTimePollScheduler.stopSchedule(timePollId);

        TimePollStatusResponse status;
        try {
            status = timePollService.getStatus(timePollId);
        } catch (IllegalArgumentException e) {
            log.warn("[TimePoll] 상태 조회 실패: timePollId={}", timePollId);
            return ResponseEntity.ok(KakaoResponse.simpleText("투표 정보를 찾을 수 없어요."));
        }

        String confirmedDate = status.getConfirmedDate();
        String finalizedTime = formatTime(status.getFinalizedTime());

        if (finalizedTime == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("아직 투표가 완료되지 않았어요."));
        }

        String message = "투표가 완료됐어요! :D\n\n"
                + confirmedDate + " " + finalizedTime + "에 만나기로 정해졌어요🙂";

        return ResponseEntity.ok(KakaoResponse.simpleText(message));
    }

    @PostMapping("/time-poll/notify/final-buttons")
    public ResponseEntity<KakaoResponse> handleTimePollFinalButtons(@RequestBody KakaoRequest request) {
        String botGroupKey = request.getBotGroupKey();
        Long timePollId = kakaoWendyService.getTimePollIdByBotGroupKey(botGroupKey);
        if (timePollId == null) {
            return ResponseEntity.ok(KakaoResponse.simpleText("진행 중인 시간 투표가 없어요."));
        }

        String timePollUrl = "https://schedulyy.netlify.app/time/" + timePollId;

        return ResponseEntity.ok(KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .textCard(KakaoResponse.BasicCard.builder()
                                                .title("어떻게 하실 건가요? 🙂")
                                                .buttons(List.of(
                                                        KakaoResponse.messageButton("저도 그때 좋아요", "저도 그때 좋아요"),
                                                        KakaoResponse.Button.builder()
                                                                .label("투표할래요")
                                                                .action("webLink")
                                                                .webLinkUrl(timePollUrl)
                                                                .build()
                                                ))
                                                .build())
                                        .build()
                        ))
                        .build())
                .build());
    }
}
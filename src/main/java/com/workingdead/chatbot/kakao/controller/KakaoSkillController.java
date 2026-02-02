package com.workingdead.chatbot.kakao.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workingdead.chatbot.kakao.dto.KakaoRequest;
import com.workingdead.chatbot.kakao.dto.KakaoResponse;
import com.workingdead.chatbot.kakao.service.KakaoWendyService;
import com.workingdead.chatbot.kakao.service.KakaoWendyService.SessionState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final ObjectMapper objectMapper;

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
     * 메인 스킬 엔드포인트 (폴백 블록)
     * 모든 발화를 여기서 처리
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

        String trimmed = utterance.trim();

        // 1. 웬디 시작
        if (trimmed.equals("웬디 시작") || trimmed.equals("시작")) {
            return ResponseEntity.ok(kakaoWendyService.startSession(sessionKey, botGroupKey));
        }

        // 2. 도움말
        if (trimmed.equals("웬디 도움말") || trimmed.equals("도움말") || trimmed.equals("/help")) {
            return ResponseEntity.ok(kakaoWendyService.help());
        }

        // 3. 웬디 종료
        if (trimmed.equals("웬디 종료") || trimmed.equals("종료")) {
            return ResponseEntity.ok(kakaoWendyService.endSession(sessionKey));
        }

        // 4. 웬디 결과
        if (trimmed.equals("웬디 결과") || trimmed.equals("결과") || trimmed.equals("결과 확인")) {
            return ResponseEntity.ok(kakaoWendyService.getVoteResult(sessionKey));
        }

        // 5. 웬디 재투표
        if (trimmed.equals("웬디 재투표") || trimmed.equals("재투표")) {
            return ResponseEntity.ok(kakaoWendyService.revote(sessionKey));
        }

        // 6. 웬디 {기간} (예: "웬디 2주 후", "웬디 이번주")
        // 멘션/푸시 기능 없이, 기간 입력을 받으면 바로 투표 URL을 생성해 반환
        if (trimmed.startsWith("웬디 ")) {
            String arg = trimmed.substring("웬디 ".length()).trim();
            // 예약어는 위에서 이미 처리했지만, 안전하게 한 번 더 방어
            if (!arg.isBlank()
                    && !arg.equals("시작")
                    && !arg.equals("도움말")
                    && !arg.equals("종료")
                    && !arg.equals("결과")
                    && !arg.equals("재투표")
                    && !arg.equals("독촉")) {
                Integer weeks = kakaoWendyService.parseWeeks(arg);
                if (weeks != null) {
                    KakaoResponse response = kakaoWendyService.createVote(sessionKey, weeks, botGroupKey);
                    return ResponseEntity.ok(response);
                }
            }
        }


        // 세션 상태에 따른 처리
        SessionState state = kakaoWendyService.getSessionState(sessionKey);

        switch (state) {
            case WAITING_PARTICIPANTS:
                // 참석자 입력: PRD 기준으로 botUserKey(멘션된 유저 키) 기반을 우선 사용
                // 멘션 기반 참석자 수집 기능을 사용하지 않는 정책으로 전환
                return ResponseEntity.ok(KakaoResponse.simpleText(
                        "\"@웬디 2주 후\"처럼 기간을 입력하면 바로 날짜 투표 링크를 만들어드릴게요!"
                ));

            case WAITING_WEEKS:
                // 주차 선택
                Integer weeks = kakaoWendyService.parseWeeks(trimmed);
                if (weeks != null) {
                    KakaoResponse response = kakaoWendyService.createVote(sessionKey, weeks, botGroupKey);
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
        log.info("[Kakao Skill] START - sessionKey={}, botGroupKey={}", sessionKey, botGroupKey);
        return ResponseEntity.ok(kakaoWendyService.startSession(sessionKey, botGroupKey));
    }

    /**
     * 참석자 등록 스킬 (전용 블록)
     * PRD 기준: 발화에서 멘션된 유저 식별 결과로 botUserKey 목록을 params로 전달받는 형태를 우선 지원
     * - 지원 params 키 예시: botUserKeys / participants / mentionedUserKeys
     */
    @Operation(summary = "참석자 등록")
    @PostMapping("/participants")
    public ResponseEntity<KakaoResponse> handleParticipants(@RequestBody KakaoRequest request) {
        String sessionKey = getSessionKey(request);
        log.info("[Kakao Skill] PARTICIPANTS - disabled. sessionKey={}", sessionKey);
        return ResponseEntity.ok(KakaoResponse.simpleText(
                "\"@웬디 2주 후\"처럼 기간을 입력하면 날짜 투표 링크를 만들어드릴게요!"
        ));
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
     * 주차 선택 스킬 (전용 블록)
     * params에서 weeks 값을 받음
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

        KakaoResponse response = kakaoWendyService.createVote(sessionKey, weeks, botGroupKey);
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
     * 참석자 botUserKey 목록 추출
     * - 오픈빌더의 "발화에서 멘션된 유저 식별" 결과를 params로 전달받는 것을 1순위로 사용
     * - 지원 키: botUserKeys / participants / mentionedUserKeys
     * - 값 형태: "k1,k2,k3" 또는 "k1 k2 k3" 등(구분자는 콤마/공백/개행 모두 허용)
     *
     * @param request 요청 DTO
     * @param fallbackUtterance params가 없을 때 마지막 fallback(테스트/디버그용)
     * @return 콤마(,)로 join된 botUserKey 목록 문자열
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
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * 다양한 구분자(콤마/공백/개행)를 콤마 구분 문자열로 정규화
     */
    private String normalizeKeys(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

        // 콤마, 공백, 개행, 탭을 모두 구분자로 처리
        String[] parts = trimmed.split("[\\s,]+");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isEmpty()) set.add(s);
        }
        return String.join(",", set);
    }
}
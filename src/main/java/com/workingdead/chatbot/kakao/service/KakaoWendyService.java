package com.workingdead.chatbot.kakao.service;

import com.workingdead.chatbot.kakao.dto.KakaoResponse;
import com.workingdead.meet.dto.ParticipantDtos.ParticipantStatusRes;
import com.workingdead.meet.dto.VoteDtos.CreateVoteReq;
import com.workingdead.meet.dto.VoteDtos.VoteSummary;
import com.workingdead.meet.dto.VoteResultDtos.RankingRes;
import com.workingdead.meet.dto.VoteResultDtos.VoteResultRes;
import com.workingdead.meet.service.ParticipantService;
import com.workingdead.meet.service.VoteResultService;
import com.workingdead.meet.service.VoteService;
import com.workingdead.chatbot.kakao.scheduler.KakaoWendyScheduler;
import com.workingdead.chatbot.kakao.scheduler.KakaoTimePollScheduler;
import com.workingdead.chatbot.kakao.service.KakaoTimePollNotifier;
import com.workingdead.meet.dto.request.TimePollCreateRequest;
import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.entity.Participant;
import com.workingdead.meet.service.TimePollService;
import com.workingdead.timepoll.enums.Period;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 카카오 챗봇용 웬디 서비스
 * Discord와 독립적으로 세션 관리
 * - 개인챗: userKey 기반
 * - 그룹챗: botGroupKey 기반
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoWendyService {

    private final VoteService voteService;
    private final ParticipantService participantService;
    private final VoteResultService voteResultService;


    // ========== 세션 관리 (sessionKey = botGroupKey 또는 userKey) ==========

    // 활성 세션 관리
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    // 참석자 목록 (sessionKey -> List<botUserKey>)
    private final Map<String, List<String>> participants = new ConcurrentHashMap<>();

    // 참석자 표시명 (sessionKey -> List<표시명>)
    private final Map<String, List<String>> participantDisplayNames = new ConcurrentHashMap<>();

    // 생성된 투표 ID (sessionKey -> voteId)
    private final Map<String, Long> sessionVoteId = new ConcurrentHashMap<>();

    // 생성된 투표 링크 (sessionKey -> shareUrl)
    private final Map<String, String> sessionShareUrl = new ConcurrentHashMap<>();

    // 투표 생성 시각 (sessionKey -> createdAt)
    private final Map<String, LocalDateTime> voteCreatedAt = new ConcurrentHashMap<>();

    // 세션 상태 (sessionKey -> state)
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    // botGroupKey -> voteId 매핑 (이벤트 메시지 발송용)
    private final Map<String, Long> groupVoteId = new ConcurrentHashMap<>();

    // voteId -> botGroupKey 역매핑
    private final Map<Long, String> voteIdToGroupKey = new ConcurrentHashMap<>();

    // 스케줄러
    private final KakaoWendyScheduler kakaoWendyScheduler;
    private final KakaoTimePollScheduler kakaoTimePollScheduler;

    // 카카오봇 API 클라이언트 
    private final KakaoBotApiClient kakaoBotApiClient;

    // 시간 투표 서비스
    private final TimePollService timePollService;

    public enum SessionState {
        IDLE,
        WAITING_PARTICIPANTS,
        WAITING_WEEKS,
        VOTE_CREATED
    }
    private final Map<String, Long> groupTimePollId = new ConcurrentHashMap<>();
    private final Map<Long, String> timePollIdToGroupKey = new ConcurrentHashMap<>();

    // ========== Deprecated: 하위 호환성 ==========
    @Deprecated
    private final Map<String, Long> userVoteId = sessionVoteId;
    @Deprecated
    private final Map<String, String> userShareUrl = sessionShareUrl;

    // ========== 세션 관리 ==========

    /**
     * 세션 시작 (웬디 시작)
     */
    // public KakaoResponse startSession(String userKey) {
    //     activeSessions.add(userKey);
    //     participants.put(userKey, new ArrayList<>());
    //     participantDisplayNames.put(userKey, new ArrayList<>());
    //     userVoteId.remove(userKey);
    //     userShareUrl.remove(userKey);
    //     voteCreatedAt.remove(userKey);
    //     sessionStates.put(userKey, SessionState.WAITING_WEEKS);

    //     log.info("[Kakao When:D] Session started: {}", userKey);

    //     Map<String, Object> data = new HashMap<>();
    //     data.put("sessionKey", userKey);
    //     data.put("state", SessionState.WAITING_WEEKS.name());
    //     data.put("active", true);
    //     return dataOnly(data);
    // }
    public KakaoResponse startSession(String userKey) {
        activeSessions.add(userKey);
        participants.put(userKey, new ArrayList<>());
        participantDisplayNames.put(userKey, new ArrayList<>());
        userVoteId.remove(userKey);
        userShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);
        sessionStates.put(userKey, SessionState.WAITING_WEEKS);

        log.info("[Kakao When:D] Session started: {}", userKey);

        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text("짜잔!\n스케쥴리 등장😎🚗\n\n지금부터 여러분의 일정 조율을 도와드릴게요 :D")
                                                .build())
                                        .build(),
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text("친구분들과 언제 만나실 건가요? :D\n\n[예시]\n@태그 + \"이번 주/1주 후/2주 후\" etc.")
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * 세션 활성 여부 확인
     */
    public boolean isSessionActive(String userKey) {
        return activeSessions.contains(userKey);
    }

    /**
     * 세션 종료 (웬디 종료)
     */
    public KakaoResponse endSession(String userKey) {
        kakaoWendyScheduler.stopSchedule(userKey);
        activeSessions.remove(userKey);
        participants.remove(userKey);
        userVoteId.remove(userKey);
        participantDisplayNames.remove(userKey);
        userShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);
        sessionStates.remove(userKey);

        log.info("[Kakao When:D] Session ended: {}", userKey);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionKey", userKey);
        data.put("state", SessionState.IDLE.name());
        data.put("active", false);
        return dataOnly(data);
    }

    /**
     * 현재 세션 상태 조회
     */
    public SessionState getSessionState(String userKey) {
        return sessionStates.getOrDefault(userKey, SessionState.IDLE);
    }

    // ========== 참석자 관리 ==========

    /**
     * 참석자 추가 (botUserKey 리스트 입력)
     **/
    public KakaoResponse addParticipants(String userKey, String input) {
        // input: 컨트롤러에서 botUserKey 목록을 ","로 정규화하여 전달한다고 가정
        String raw = Optional.ofNullable(input).orElse("");

        List<String> keys = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        if (keys.isEmpty()) {
            Map<String, Object> data = new HashMap<>();
            data.put("sessionKey", userKey);
            data.put("state", getSessionState(userKey).name());
            data.put("participantCount", 0);
            data.put("enabled", false);
            return dataOnly(data);
        }

        // 표시명은 PRD 상 botUserKey만 받는 상황을 고려해 임시 생성
        List<String> displayNames = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            displayNames.add("참석자" + (i + 1));
        }

        participants.put(userKey, keys);
        participantDisplayNames.put(userKey, displayNames);
        sessionStates.put(userKey, SessionState.WAITING_WEEKS);

        log.info("[Kakao When:D] Participants added: {} -> {}", userKey, keys);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionKey", userKey);
        data.put("state", SessionState.WAITING_WEEKS.name());
        data.put("participantCount", keys.size());
        data.put("botUserKeys", keys);

        data.put("participantDisplayNames", displayNames);
        return dataOnly(data);
    }

    // ========== 투표 생성 ==========

    /**
     * 투표 생성 (주차 선택 후)
     */
    private final ObjectMapper objectMapper;
    public KakaoResponse createVote(String userKey, int weeks, String botUserKey) {
        voteCreatedAt.put(userKey, LocalDateTime.now());

        // 1. 날짜 범위 계산
        LocalDate today = LocalDate.now();
        LocalDate startDate;
        LocalDate endDate;

        if (weeks == 0) {
            startDate = today;
            int daysToSunday = DayOfWeek.SUNDAY.getValue() - today.getDayOfWeek().getValue();
            endDate = today.plusDays(Math.max(daysToSunday, 0));
        } else {
            LocalDate mondayThisWeek = today.with(DayOfWeek.MONDAY);
            startDate = mondayThisWeek.plusWeeks(weeks);
            endDate = startDate.plusDays(6);
        }

        // 2. 참여자 표시명 리스트
        List<String> participantNames = participantDisplayNames.getOrDefault(userKey, List.of());

        // 3. 투표 생성
        CreateVoteReq req = new CreateVoteReq(
                "카카오 투표",
                startDate,
                endDate,
                participantNames.isEmpty() ? null : participantNames
        );

        VoteSummary summary = voteService.create(req);
        Long voteId = summary.id();
        String shareUrl = summary.shareUrl();

        userVoteId.put(userKey, voteId);
        userShareUrl.put(userKey, shareUrl);
        sessionStates.put(userKey, SessionState.VOTE_CREATED);

        
        kakaoWendyScheduler.startSchedule(userKey);

        log.info("[Kakao When:D] Vote created: userKey={}, voteId={}, weeks={}, shareUrl={}", userKey, voteId, weeks, shareUrl);


        String weekLabel = weeks == 0 ? "이번 주" : weeks + "주 뒤";

        Map<String, Object> data = new HashMap<>();
        data.put("voteId", voteId);
        data.put("shareUrl", shareUrl);
        data.put("weekLabel", weekLabel);
        data.put("startDate", startDate.toString());
        data.put("endDate", endDate.toString());
        data.put("participants", participantNames);

        data.put("sessionKey", userKey);
        data.put("state", SessionState.VOTE_CREATED.name());
        // return dataOnly(data);

        KakaoResponse response = KakaoResponse.builder()
            .version("2.0")
            .template(KakaoResponse.Template.builder()
                .outputs(List.of(
                    // 첫 번째 메시지
                    KakaoResponse.Output.builder()
                        .simpleText(KakaoResponse.SimpleText.builder()
                            .text(weekLabel + "를 선택하셨어요\n해당 일정의 투표를 만들어드릴게요 :D")
                            .build())
                        .build(),
                    // 두 번째 메시지
                    KakaoResponse.Output.builder()
                        .simpleText(KakaoResponse.SimpleText.builder()
                            .text("(투표 늦게 하는 사람 대머리 🧑‍🦲)")
                            .build())
                        .build(),
                    // 세 번째 메시지 - 버튼 카드
                    KakaoResponse.Output.builder()
                        .textCard(KakaoResponse.BasicCard.builder()
                            .title("투표 생성 완료!!")
                            .buttons(List.of(
                                KakaoResponse.Button.builder()
                                    .label("투표하러가기")
                                    .action("webLink")
                                    .webLinkUrl(shareUrl)
                                    .build()
                            ))
                            .build())
                        .build()
                ))
                .quickReplies(List.of(
                    KakaoResponse.quickReply("결과 보기", "결과"),
                    KakaoResponse.quickReply("종료", "종료")
                ))
                .build())
            .build();
        
        return response;
    }

    /**
     * 주차 파싱 (0 = 이번 주, 1~6 = n주 뒤)
     */
    public Integer parseWeeks(String input) {
        if (input.contains("이번")) return 0;
        if (input.contains("1주")) return 1;
        if (input.contains("2주")) return 2;
        if (input.contains("3주")) return 3;
        if (input.contains("4주")) return 4;
        if (input.contains("5주")) return 5;
        if (input.contains("6주")) return 6;

        // 숫자만 추출
        String numbers = input.replaceAll("[^0-9]", "");
        if (!numbers.isEmpty()) {
            try {
                int weeks = Integer.parseInt(numbers);
                if (weeks >= 0 && weeks <= 6) return weeks;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // ========== 결과 조회 ==========

    /**
     * 투표 결과 조회
     */
    public KakaoResponse getVoteResult(String userKey) {
        Long voteId = userVoteId.get(userKey);
        String shareUrl = userShareUrl.get(userKey);

        if (voteId == null) {
            return textOnly("""
                    스케쥴리가 투표 현황을 공유드려요! :D

                    아직 진행 중인 투표가 없어요 :(
                    """.strip());
        }

        VoteResultRes result = voteResultService.getVoteResult(voteId);

        if (result == null || result.rankings() == null || result.rankings().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("스케쥴리가 투표 현황을 공유드려요! :D\n\n");
            sb.append("엥 아직 아무도 투표를 안 했네요 :(\n");
            // if (shareUrl != null && !shareUrl.isBlank()) {
            //     sb.append("\n투표하러 가기: ").append(shareUrl);
            // }
            return textOnly(sb.toString().trim());
        }


        // 1~3순위만 출력 (없는 순위는 생략)
        List<RankingRes> top3 = result.rankings().stream()
                .filter(r -> r.rank() != null)
                .filter(r -> r.rank() >= 1 && r.rank() <= 3)
                .sorted(Comparator.comparingInt(RankingRes::rank))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("스케쥴리가 투표 현황을 공유드려요! :D\n\n");

        // if (shareUrl != null && !shareUrl.isBlank()) {
        //     sb.append("\n투표하러 가기: ").append(shareUrl).append("\n\n");
        // } else {
        //     sb.append("\n투표 링크가 준비되지 않았어요 😢\n\n");
        // }

        for (RankingRes rank : top3) {
            String periodLabel = "LUNCH".equals(rank.period()) ? "점심" : "저녁";

            sb.append("📌")
                    .append(rank.rank()).append("순위 ")
                    .append(rank.date()).append(" ")
                    .append(periodLabel).append("\n");

            if (rank.voters() != null && !rank.voters().isEmpty()) {
                String voterStr = rank.voters().stream()
                        .map(v -> v.participantName()
                                + (v.priorityIndex() != null ? "(" + v.priorityIndex() + ")" : ""))
                        .collect(Collectors.joining(", "));
                sb.append("투표자: ").append(voterStr).append("\n");
            }
            sb.append("\n");
        }

        // top3 가 비어있으면(이상 케이스) 그래도 안전하게 메시지 출력
        if (top3.isEmpty()) {
            sb.append("아직 집계할 수 있는 순위 결과가 없어요 :(");
        }

        return textOnly(sb.toString().trim());
    }

    /**
     * 재투표 (동일 참석자로 새 투표 생성)
     */
    // public KakaoResponse revote(String userKey) {
    //     if (!userVoteId.containsKey(userKey)) {
    //         Map<String, Object> data = new HashMap<>();
    //         data.put("hasVote", false);
    //         data.put("state", getSessionState(userKey).name());
    //         return dataOnly(data);
    //     }

    //     kakaoWendyScheduler.stopSchedule(userKey);
    //     userVoteId.remove(userKey);
    //     sessionStates.put(userKey, SessionState.WAITING_WEEKS);

    //     Map<String, Object> data = new HashMap<>();
    //     data.put("hasVote", true);
    //     data.put("state", SessionState.WAITING_WEEKS.name());
    //     return dataOnly(data);
    // }

    public KakaoResponse revote(String userKey) {
        // 기존 투표 데이터 정리
        kakaoWendyScheduler.stopSchedule(userKey);
        Long voteId = sessionVoteId.get(userKey);
        if (voteId != null) {
            String groupKey = voteIdToGroupKey.remove(voteId);
            if (groupKey != null) groupVoteId.remove(groupKey);
        }
        sessionVoteId.remove(userKey);
        sessionShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);

        // 그냥 시작과 동일하게
        return startSession(userKey);
    }

    /**
     * 도움말
     */
    public KakaoResponse help() {
        Map<String, Object> data = new HashMap<>();
        data.put("commands", List.of("시작", "종료", "재투표", "결과"));
        return dataOnly(data);
    }

    /**
     * 알 수 없는 입력 처리
     */
    public KakaoResponse unknownInput(String userKey) {
        SessionState state = getSessionState(userKey);
        Map<String, Object> data = new HashMap<>();
        data.put("state", state.name());
        String shareUrl = userShareUrl.get(userKey);
        if (shareUrl != null) {
            data.put("shareUrl", shareUrl);
        }
        Long voteId = userVoteId.get(userKey);
        if (voteId != null) {
            data.put("voteId", voteId);
        }
        return dataOnly(data);
    }

    // 투표 알림 메시지 생성
    /**
     * 투표 알림 메시지 생성
     */    
    public KakaoResponse buildRemindResponse(String sessionKey, String timing) {
        Long voteId = sessionVoteId.get(sessionKey);
        if (voteId == null) return KakaoResponse.simpleText("진행 중인 투표가 없어요.");
        
        if (timing == null) {
            log.error("[REMIND] timing is null");
            return KakaoResponse.simpleText("timing 값이 없습니다.");
        }
        log.info("[REMIND] buildRemindResponse called sessionKey={}, timing={}", sessionKey, timing);

        List<ParticipantStatusRes> nonVoters = participantService.getParticipantStatusByVoteId(voteId)
                .stream()
                .filter(s -> !s.submitted())
                .toList();

        log.info("=== [투표 상태 전체] voteId={} ===", voteId);
        var all = participantService.getParticipants(voteId);
        all.forEach(p ->
            log.info("userKey={}, kakaoId={}, submitted={}",
                p.getId(),
                p.getKakaoId(),
                p.getSubmitted()    
            )
        );

        if (nonVoters.isEmpty()) {
            kakaoWendyScheduler.stopSchedule(sessionKey);
            return buildCompletionResponse(sessionKey);
        }

        log.info("[REMIND] nonVoters size={}", nonVoters.size());

        // 3min은 멘션 없이 바로 반환
        if ("3min".equals(timing)) {
            return KakaoResponse.simpleText("스케쥴리가 투표 현황을 공유드려요! :D\n\n엥 아직 아무도 투표를 안 했네요 :(");
        }

        // 멘션 구성 (Map<String, String>)
        // Map<String, String> mentions = new LinkedHashMap<>();
        //StringBuilder mentionSb = new StringBuilder();

        // for (int i = 0; i < nonVoters.size(); i++) {
        //     String userKey = "user" + (i + 1);
        //     String kakaoId = nonVoters.get(i).kakaoId();
        //     if (kakaoId != null && !kakaoId.isBlank()) {
        //         mentions.put(userKey, kakaoId);
        //         mentionSb.append(KakaoResponse.buildMentionText(userKey)); // #{mentions.user1}
        //     } else {
        //         mentionSb.append(nonVoters.get(i).displayName());
        //     }
        //     if (i < nonVoters.size() - 1) mentionSb.append(", ");
        // }

        // 멘션 구성 - 타입 변경
        Map<String, Map<String, String>> mentions = new LinkedHashMap<>();
        StringBuilder mentionSb = new StringBuilder(); 

        for (int i = 0; i < nonVoters.size(); i++) {
            String userKey = "user" + (i + 1);
            String kakaoId = nonVoters.get(i).kakaoId();
            if (kakaoId != null && !kakaoId.isBlank()) {
                mentions.put(userKey, Map.of(
                    "type", "botUserKey",
                    "id", kakaoId
                ));
                mentionSb.append(KakaoResponse.buildMentionText(userKey));
            } else {
                mentionSb.append(nonVoters.get(i).displayName());
            }
            if (i < nonVoters.size() - 1) mentionSb.append(", ");
        }

        String mentionStr = mentionSb.toString();

        String message = switch (timing) {
            case "30min", "2hour" -> mentionStr + " 투표가 시작됐어요! \n다른 분들을 위해 빠른 참여 부탁드려요 :D";
            case "6hour" -> "다들 " + mentionStr + " 님의 투표를 기다리고 있어요🤔";
            case "12hour" -> "스케쥴리 기다리다 지쳐버림…🥹\n" + mentionStr + " 님 혹시 대머리신가요…?";
            default -> mentionStr + " 아직 투표 안 하셨어요!";
        };

        log.info("[REMIND] final message = {}", message);

        KakaoResponse res = KakaoResponse.textWithQuickRepliesAndMentions(message, mentions.isEmpty() ? null : mentions, null);

        
        try {
            log.info("[REMIND] response JSON = {}", objectMapper.writeValueAsString(res));
        } catch (Exception e) {
            log.warn("[REMIND] response JSON 로깅 실패: {}", e.getMessage());
        }
        return res;

        // return KakaoResponse.textWithQuickRepliesAndMentions(message, mentions, null);
    }
    /**
     * 투표 완료 메시지 생성
     */
    // public KakaoResponse buildCompletionResponse(String sessionKey) {
    //     Long voteId = sessionVoteId.get(sessionKey);
    //     VoteResultRes result = voteResultService.getVoteResult(voteId);

    //     StringBuilder sb = new StringBuilder("투표가 완료됐어요! :D\n\n");

    //     String timePollUrl = null;
    //     if (result != null && result.rankings() != null) {
    //         List<RankingRes> top3 = result.rankings().stream()
    //                 .filter(r -> r.rank() != null && r.rank() <= 3)
    //                 .sorted(Comparator.comparingInt(RankingRes::rank))
    //                 .toList();

    //         for (RankingRes r : top3) {
    //             String periodLabel = "LUNCH".equals(r.period()) ? "점심" : "저녁";
    //             String dayLabel = getDayLabel(r.date().getDayOfWeek());
    //             sb.append("📌").append(r.rank()).append("순위 ")
    //                     .append(r.date().format(DateTimeFormatter.ofPattern("MM/dd")))
    //                     .append("(").append(dayLabel).append(") ")
    //                     .append(periodLabel).append("\n");
    //             if (r.voters() != null && !r.voters().isEmpty()) {
    //                 String voters = r.voters().stream()
    //                         .map(v -> v.participantName()
    //                                 + (v.priorityIndex() != null ? "(" + v.priorityIndex() + ")" : ""))
    //                         .collect(Collectors.joining(", "));
    //                 sb.append("투표자: ").append(voters).append("\n");
    //             }
    //             sb.append("\n");
    //         }

    //         // 1순위로 time-poll 생성
    //         if (!top3.isEmpty()) {
    //             RankingRes top = top3.get(0);
    //             try {
    //                 TimePollCreateRequest req = new TimePollCreateRequest();
    //                 req.setVoteId(voteId);
    //                 req.setConfirmedDate(top.date().format(DateTimeFormatter.ofPattern("M월 d일")));
    //                 req.setPeriod(Period.valueOf(top.period()));
    //                 TimePoll timePoll = timePollService.create(req);
    //                 timePollUrl = "https://schedulyy.netlify.app/time/" + timePoll.getId();
    //             } catch (Exception e) {
    //                 log.warn("[Kakao] Failed to create time-poll: {}", e.getMessage());
    //             }
    //         }
    //     }

    //     sb.append("이제 몇 시에 만날지 정해볼까요?🙂");

    //     // 버튼 구성
    //     List<KakaoResponse.Button> buttons = new ArrayList<>();
    //     if (timePollUrl != null) {
    //         buttons.add(KakaoResponse.Button.builder()
    //                 .label("좋아요")
    //                 .action("webLink")
    //                 .webLinkUrl(timePollUrl)
    //                 .build());
    //     }
    //     buttons.add(KakaoResponse.messageButton("재투표할래요", "웬디 재투표"));
    //     buttons.add(KakaoResponse.messageButton("종료할게요", "웬디 종료"));

    //     return KakaoResponse.builder()
    //             .version("2.0")
    //             .template(KakaoResponse.Template.builder()
    //                     .outputs(List.of(
    //                             KakaoResponse.Output.builder()
    //                                     .textCard(KakaoResponse.BasicCard.builder()
    //                                             .title("투표가 완료됐어요! :D")
    //                                             .description(sb.toString().trim())
    //                                             .buttons(buttons)
    //                                             .build())
    //                                     .build()
    //                     ))
    //                     .build())
    //             .build();
    //     }
    /**
     * 최후통첩
     */
    public KakaoResponse buildFinalNoticeResponse(String sessionKey) {
        Long voteId = sessionVoteId.get(sessionKey);
        if (voteId == null) return KakaoResponse.simpleText("진행 중인 투표가 없어요.");

        // 미투표자 조회
        List<ParticipantStatusRes> nonVoters = participantService.getParticipantStatusByVoteId(voteId)
                .stream()
                .filter(s -> !s.submitted())
                .toList();

        if (nonVoters.isEmpty()) {
            // 이미 다 투표했으면 완료 처리
            return buildCompletionResponse(sessionKey);
        }

        // 마감 시간 = 생성 시각 + 24시간
        // LocalDateTime createdAt = voteCreatedAt.get(sessionKey);
        // String deadline = createdAt != null
        //         ? createdAt.plusHours(24).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        //         : "곧";

        LocalDateTime createdAt = voteCreatedAt.get(sessionKey);
        String deadline = createdAt != null
                ? createdAt.atZone(ZoneId.of("UTC"))          // 저장된 시간이 UTC임을 명시
                        .withZoneSameInstant(ZoneId.of("Asia/Seoul"))  // KST로 변환
                        .plusHours(25)
                        .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                : "곧";

        // 멘션 구성
        Map<String, Map<String, String>> mentions = new LinkedHashMap<>();
        StringBuilder mentionSb = new StringBuilder();
        for (int i = 0; i < nonVoters.size(); i++) {
            String key = "user" + (i + 1);
            String kakaoId = nonVoters.get(i).kakaoId();
            if (kakaoId != null && !kakaoId.isBlank()) {
                mentions.put(key, Map.of(
                        "type", "botUserKey",
                        "id", kakaoId));
                mentionSb.append(KakaoResponse.buildMentionText(key));
            } else {
                mentionSb.append(nonVoters.get(i).displayName());
            }
            if (i < nonVoters.size() - 1) mentionSb.append(", ");
        }

        // 1순위 조회
        VoteResultRes result = voteResultService.getVoteResult(voteId);
        String topResult = "미정";
        if (result != null && result.rankings() != null) {
            result.rankings().stream()
                    .filter(r -> r.rank() == 1)
                    .findFirst()
                    .ifPresent(top -> {
                        // topResult 재할당 불가라 StringBuilder 사용
                    });
            RankingRes top = result.rankings().stream()
                    .filter(r -> r.rank() == 1)
                    .findFirst().orElse(null);
            if (top != null) {
                String periodLabel = "LUNCH".equals(top.period()) ? "점심" : "저녁";
                topResult = top.date().format(DateTimeFormatter.ofPattern("MM/dd"))
                        + "(" + getDayLabel(top.date().getDayOfWeek()) + ") " + periodLabel;
            }
        }

        String message = "최후통첩✉️\n\n" + mentionSb
                + "\n" + deadline + "까지 투표 불참 시,\n"
                + topResult + "으로 확정할게요!😤";

        return KakaoResponse.textWithQuickRepliesAndMentions(message, mentions.isEmpty() ? null : mentions, null);
    }

    public KakaoResponse buildCompletionResponse(String sessionKey) {
        Long voteId = sessionVoteId.get(sessionKey);
        VoteResultRes result = voteResultService.getVoteResult(voteId);

        // 순위 결과 텍스트
        StringBuilder rankSb = new StringBuilder();
        String timePollUrl = null;

        if (result != null && result.rankings() != null) {
            List<RankingRes> top3 = result.rankings().stream()
                    .filter(r -> r.rank() != null && r.rank() <= 3)
                    .sorted(Comparator.comparingInt(RankingRes::rank))
                    .toList();

            for (RankingRes r : top3) {
                String periodLabel = "LUNCH".equals(r.period()) ? "점심" : "저녁";
                String dayLabel = getDayLabel(r.date().getDayOfWeek());
                rankSb.append("📌").append(r.rank()).append("순위 ")
                        .append(r.date().format(DateTimeFormatter.ofPattern("MM/dd")))
                        .append("(").append(dayLabel).append(") ")
                        .append(periodLabel).append("\n");
                if (r.voters() != null && !r.voters().isEmpty()) {
                    String voters = r.voters().stream()
                            .map(v -> v.participantName()
                                    + (v.priorityIndex() != null ? "(" + v.priorityIndex() + ")" : ""))
                            .collect(Collectors.joining(", "));
                    rankSb.append("투표자: ").append(voters).append("\n");
                }
                rankSb.append("\n");
            }

            // if (!top3.isEmpty()) {
            //     RankingRes top = top3.get(0);
            //     try {
            //         TimePollCreateRequest req = new TimePollCreateRequest();
            //         req.setVoteId(voteId);
            //         req.setConfirmedDate(top.date().format(DateTimeFormatter.ofPattern("M월 d일")));
            //         req.setPeriod(Period.valueOf(top.period()));
            //         TimePoll timePoll = timePollService.create(req);
            //         timePollUrl = "https://schedulyy.netlify.app/time/" + timePoll.getId();
            //     } catch (Exception e) {
            //         log.warn("[Kakao] Failed to create time-poll: {}", e.getMessage());
            //     }
            // }
        }

        // 버튼 구성
        List<KakaoResponse.Button> buttons = new ArrayList<>();
        // if (timePollUrl != null) {
        //     // webLink 대신 message로 → 새 메시지 트리거
        //     final String finalTimePollUrl = timePollUrl;
        //     buttons.add(KakaoResponse.Button.builder()
        //             .label("좋아요")
        //             .action("message")
        //             .messageText("시간 투표")
        //             .build());
        // }
        buttons.add(KakaoResponse.Button.builder()
            .label("좋아요")
            .action("message")
            .messageText("시간 투표")
            .build());
        buttons.add(KakaoResponse.messageButton("재투표할래요", "재투표"));
        buttons.add(KakaoResponse.messageButton("종료할게요", "종료"));

        List<KakaoResponse.Output> outputs = new ArrayList<>();

        // 순위 결과 - simpleText로 (검은색)
        if (!rankSb.isEmpty()) {
            outputs.add(KakaoResponse.Output.builder()
                    .simpleText(KakaoResponse.SimpleText.builder()
                            .text("🏆 투표 결과\n\n" + rankSb.toString().trim())
                            .build())
                    .build());
        }

        // 완료 카드 - 버튼만
        outputs.add(KakaoResponse.Output.builder()
                .textCard(KakaoResponse.BasicCard.builder()
                        .title("모두 투표를 완료했어요! :D")
                        .description("이제 몇 시에 만날지 정해볼까요?🙂")
                        .buttons(buttons)
                        .build())
                .build());

        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(outputs)
                        .build())
                .build();
    }

    public String createTimePoll(String sessionKey, Long voteId) {
        String botGroupKey = getBotGroupKeyByVoteId(voteId);
    Long existingTimePollId = groupTimePollId.get(botGroupKey);
        if (existingTimePollId != null) {
            kakaoTimePollScheduler.stopSchedule(existingTimePollId);
            timePollService.delete(existingTimePollId);  // delete 메서드 필요
            groupTimePollId.remove(botGroupKey);
            timePollIdToGroupKey.remove(existingTimePollId);
        }
        VoteResultRes result = voteResultService.getVoteResult(voteId);
        if (result == null || result.rankings() == null || result.rankings().isEmpty()) {
            throw new RuntimeException("투표 결과가 없습니다.");
        }

        // 1~3위만 사용
        List<RankingRes> top3 = result.rankings().stream()
                .filter(r -> r.rank() != null && r.rank() <= 3)
                .sorted(Comparator.comparingInt(RankingRes::rank))
                .toList();

        if (top3.isEmpty()) {
            throw new RuntimeException("유효한 순위 결과가 없습니다.");
        }

        // 1위 날짜 + 시간으로 시간투표 생성
        RankingRes top = top3.get(0);
        TimePollCreateRequest req = new TimePollCreateRequest();
        req.setVoteId(voteId);
        req.setConfirmedDate(top.date().format(DateTimeFormatter.ofPattern("M월 d일")));
        req.setPeriod(Period.valueOf(top.period()));

        TimePoll timePoll = timePollService.create(req);
        // 스케줄러 시작
        if (botGroupKey != null) {
            groupTimePollId.put(botGroupKey, timePoll.getId());
            timePollIdToGroupKey.put(timePoll.getId(), botGroupKey);
        }
        kakaoTimePollScheduler.startSchedule(timePoll.getId(), botGroupKey);
        return "https://schedulyy.netlify.app/time/" + timePoll.getId();
    }

    public Long getTimePollIdByBotGroupKey(String botGroupKey) {
        return groupTimePollId.get(botGroupKey);
    }

   
    public KakaoResponse buildTimeRemindResponse(Long timePollId, String botGroupKey, String timing) {
        // 1. 미투표자 조회
        List<Participant> pending = timePollService.getPendingParticipants(timePollId);

        if (pending.isEmpty()) {
            return KakaoResponse.simpleText(""); // 아무도 없으면 메시지 안 보냄
        }

        // 2. 멘션 구성
        Map<String, Map<String, String>> mentions = new LinkedHashMap<>();
        StringBuilder mentionSb = new StringBuilder();

        for (int i = 0; i < pending.size(); i++) {
            Participant p = pending.get(i);
            String key = "user" + (i + 1);

            if (p.getKakaoId() != null && !p.getKakaoId().isBlank()) {
                mentions.put(key, Map.of(
                        "type", "botUserKey",
                        "id", p.getKakaoId()
                ));
                mentionSb.append(KakaoResponse.buildMentionText(key));
            } else {
                mentionSb.append(p.getDisplayName());
            }

            if (i < pending.size() - 1) {
                mentionSb.append(", ");
            }
        }

        // 3. timing별 메시지 분기
        String message;

        String safeTiming = timing != null ? timing : "";

        switch (safeTiming) {
            case "30min":
            case "2hour":
                message = mentionSb + " 님 투표가 시작됐어요! \n다른 분들을 위해 빠른 참여 부탁드려요 :D";
                break;

            case "6hour":
                message = "다들 " + mentionSb + " 님의 투표를 기다리고 있어요🤔";
                break;

            case "12hour":
                message = "스케쥴리 기다리다 지쳐버림…🥹\n" + mentionSb + " 님 혹시 대머리신가요…?";
                break;

            default:
                message = mentionSb + " 님 투표 부탁드려요!";
        }

        // 4. 응답 생성
        return KakaoResponse.textWithQuickRepliesAndMentions(
                message,
                mentions.isEmpty() ? null : mentions,
                null
        );
    }

    public KakaoResponse buildTimeFinalNoticeResponse(Long timePollId, String botGroupKey) {
        // 1. 미투표자 조회
        List<Participant> nonVoters = timePollService.getPendingParticipants(timePollId);

        if (nonVoters.isEmpty()) {
            String groupKey = timePollIdToGroupKey.get(timePollId);
            kakaoTimePollScheduler.stopSchedule(timePollId);
            kakaoBotApiClient.sendEventMessage(botGroupKey, "finish_T");
            return KakaoResponse.simpleText("");
        }

        // 2. 마감 시간 계산
        Instant createdAt = timePollService.getTimePollCreatedAt(timePollId);
        String deadline = createdAt
                .atZone(ZoneId.of("Asia/Seoul"))
                .plusHours(25)
                .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"));

        // 3. 멘션 구성
        Map<String, Map<String, String>> mentions = new LinkedHashMap<>();
        StringBuilder mentionSb = new StringBuilder();

       for (int i = 0; i < nonVoters.size(); i++) {
            Participant p = nonVoters.get(i);
            String key = "user" + (i + 1);

            if (p.getKakaoId() != null && !p.getKakaoId().isBlank()) {
                mentions.put(key, Map.of(
                        "type", "botUserKey",
                        "id", p.getKakaoId()
                ));
                mentionSb.append(KakaoResponse.buildMentionText(key));
            } else {
                mentionSb.append(p.getDisplayName());
            }

            if (i < nonVoters.size() - 1) {
                mentionSb.append(", ");
            }
        }

        // 4. 1순위 조회
        String topResult = timePollService.getTopTimeLabel(timePollId);

        String message = "최후통첩✉️\n\n" + mentionSb
                + "\n" + deadline + "까지 투표 불참 시,\n"
                + topResult + "으로 확정할게요!😤";
        return KakaoResponse.textWithQuickRepliesAndMentions(
                message,
                mentions.isEmpty() ? null : mentions,
                null
        );
    }


    // ========== 그룹챗 지원 메서드 ==========

    /**
     * 세션 시작 (그룹챗용)
     */
    public KakaoResponse startSession(String sessionKey, String botGroupKey) {
        KakaoResponse response = startSession(sessionKey);

        // 그룹챗인 경우 botGroupKey 추가 저장
        if (botGroupKey != null && !botGroupKey.isBlank()) {
            log.info("[Kakao When:D] Group session started: sessionKey={}, botGroupKey={}", sessionKey, botGroupKey);
        }

        return response;
    }

    /**
     * 투표 생성 (그룹챗용)
     */
    public KakaoResponse createVote(String sessionKey, int weeks, String botGroupKey, String botUserKey) {
        KakaoResponse response = createVote(sessionKey, weeks, botUserKey);

        if (botGroupKey != null && !botGroupKey.isBlank()) {
            Long voteId = sessionVoteId.get(sessionKey);
            if (voteId != null) {
                groupVoteId.put(botGroupKey, voteId);
                voteIdToGroupKey.put(voteId, botGroupKey);
                log.info("[Kakao When:D] Group vote mapping: botGroupKey={}, voteId={}", botGroupKey, voteId);

                // 채팅방 멤버 전원 participant로 등록 ← 여기로 이동
                try {
                    var users = kakaoBotApiClient.getChatRoomMembers(botGroupKey);

                    users.forEach(userKey ->
                        participantService.addIfNotExists(voteId, userKey)
                    );

                } catch (Exception e) {
                    log.warn("[Kakao] Failed to pre-register members: {}", e.getMessage());
                }
            }
        }

        return response;
    }

    /**
     * botGroupKey로 voteId 조회
     */
    public Long getVoteIdByBotGroupKey(String botGroupKey) {
        return groupVoteId.get(botGroupKey);
    }

    /**
     * voteId로 botGroupKey 조회
     */
    public String getBotGroupKeyByVoteId(Long voteId) {
        return voteIdToGroupKey.get(voteId);
    }

    /**
     * sessionKey로 voteId 조회
     */
    public Long getVoteIdBySessionKey(String sessionKey) {
        return sessionVoteId.get(sessionKey);
    }

    /**
     * sessionKey로 shareUrl 조회
     */
    public String getShareUrlBySessionKey(String sessionKey) {
        return sessionShareUrl.get(sessionKey);
    }

    /**
     * sessionKey로 voteCreatedAt 조회
     */
    public LocalDateTime getVoteCreatedAtBySessionKey(String sessionKey) {
        return voteCreatedAt.get(sessionKey);
    }

    // ========== 헬퍼 메서드 ==========

    private KakaoResponse dataOnly(Map<String, Object> data) {
        Map<String, Object> safe = (data == null) ? new HashMap<>() : data;
        return KakaoResponse.builder()
                .version("2.0")
                .data(safe)
                .build();
    }

    private KakaoResponse textOnly(String text) {
        // 결과 조회는 블록 멘트가 아니라 스킬 응답(simpleText)로 바로 출력
        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text(text)
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    private String getDayLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }
}
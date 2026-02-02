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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    public enum SessionState {
        IDLE,
        WAITING_PARTICIPANTS,
        WAITING_WEEKS,
        VOTE_CREATED
    }

    // ========== Deprecated: 하위 호환성 ==========
    @Deprecated
    private final Map<String, Long> userVoteId = sessionVoteId;
    @Deprecated
    private final Map<String, String> userShareUrl = sessionShareUrl;

    // ========== 세션 관리 ==========

    /**
     * 세션 시작 (웬디 시작)
     */
    public KakaoResponse startSession(String userKey) {
        activeSessions.add(userKey);
        participants.put(userKey, new ArrayList<>());
        participantDisplayNames.put(userKey, new ArrayList<>());
        userVoteId.remove(userKey);
        userShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);
        sessionStates.put(userKey, SessionState.WAITING_WEEKS);

        log.info("[Kakao When:D] Session started: {}", userKey);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionKey", userKey);
        data.put("state", SessionState.WAITING_WEEKS.name());
        data.put("active", true);
        return dataOnly(data);
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
    public KakaoResponse createVote(String userKey, int weeks) {
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

        log.info("[Kakao When:D] Vote created: userKey={}, voteId={}, weeks={}", userKey, voteId, weeks);

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
        return dataOnly(data);
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
                    웬디가 투표 현황을 공유드려요! :D

                    아직 진행 중인 투표가 없어요 :(
                    """.strip());
        }

        VoteResultRes result = voteResultService.getVoteResult(voteId);

        if (result == null || result.rankings() == null || result.rankings().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("웬디가 투표 현황을 공유드려요! :D\n\n");
            sb.append("엥 아직 아무도 투표를 안 했네요 :(\n");
            if (shareUrl != null && !shareUrl.isBlank()) {
                sb.append("\n투표하러 가기: ").append(shareUrl);
            }
            return textOnly(sb.toString().trim());
        }


        // 1~3순위만 출력 (없는 순위는 생략)
        List<RankingRes> top3 = result.rankings().stream()
                .filter(r -> r.rank() != null)
                .filter(r -> r.rank() >= 1 && r.rank() <= 3)
                .sorted(Comparator.comparingInt(RankingRes::rank))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("웬디가 투표 현황을 공유드려요! :D\n");

        if (shareUrl != null && !shareUrl.isBlank()) {
            sb.append("\n투표하러 가기: ").append(shareUrl).append("\n\n");
        } else {
            sb.append("\n투표 링크가 준비되지 않았어요 😢\n\n");
        }

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
    public KakaoResponse revote(String userKey) {
        if (!userVoteId.containsKey(userKey)) {
            Map<String, Object> data = new HashMap<>();
            data.put("hasVote", false);
            data.put("state", getSessionState(userKey).name());
            return dataOnly(data);
        }

        userVoteId.remove(userKey);
        sessionStates.put(userKey, SessionState.WAITING_WEEKS);

        Map<String, Object> data = new HashMap<>();
        data.put("hasVote", true);
        data.put("state", SessionState.WAITING_WEEKS.name());
        return dataOnly(data);
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
    public KakaoResponse createVote(String sessionKey, int weeks, String botGroupKey) {
        KakaoResponse response = createVote(sessionKey, weeks);

        // 그룹챗인 경우 botGroupKey -> voteId 매핑 저장
        if (botGroupKey != null && !botGroupKey.isBlank()) {
            Long voteId = sessionVoteId.get(sessionKey);
            if (voteId != null) {
                groupVoteId.put(botGroupKey, voteId);
                voteIdToGroupKey.put(voteId, botGroupKey);
                log.info("[Kakao When:D] Group vote mapping: botGroupKey={}, voteId={}", botGroupKey, voteId);
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
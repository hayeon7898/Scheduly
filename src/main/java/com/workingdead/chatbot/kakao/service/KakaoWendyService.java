package com.workingdead.chatbot.kakao.service;

import com.workingdead.chatbot.kakao.dto.KakaoResponse;
import com.workingdead.meet.dto.ParticipantDtos.ParticipantStatusRes;
import com.workingdead.meet.dto.VoteDtos.CreateVoteReq;
import com.workingdead.meet.dto.VoteDtos.VoteSummary;
import com.workingdead.meet.dto.VoteResultDtos.RankingRes;
import com.workingdead.meet.dto.VoteResultDtos.VoteResultRes;
import com.workingdead.meet.entity.PendingSession;
import com.workingdead.meet.repository.PendingSessionRepository;
import com.workingdead.enums.PendingSessionStatus;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 챗봇용 웬디 서비스 Discord와 독립적으로 세션 관리 
 * - 개인챗: userKey 기반 
 * - 그룹챗: botGroupKey 기반
 */
@Service
@Slf4j
public class KakaoWendyService {

    private final VoteService voteService;
    private final ParticipantService participantService;
    private final VoteResultService voteResultService;
    private final KakaoWendyScheduler kakaoWendyScheduler;
    private final KakaoTimePollScheduler kakaoTimePollScheduler;
    private final KakaoBotApiClient kakaoBotApiClient;
    private final TimePollService timePollService;
    private final ObjectMapper objectMapper;
    private final PendingSessionRepository pendingSessionRepository;
    private final KakaoNotifier kakaoNotifier;

    public KakaoWendyService(
            VoteService voteService,
            ParticipantService participantService,
            VoteResultService voteResultService,
            @Lazy KakaoWendyScheduler kakaoWendyScheduler,
            @Lazy KakaoTimePollScheduler kakaoTimePollScheduler,
            KakaoBotApiClient kakaoBotApiClient,
            TimePollService timePollService,
            ObjectMapper objectMapper,
            PendingSessionRepository pendingSessionRepository,
            @Lazy KakaoNotifier kakaoNotifier) {
        this.voteService = voteService;
        this.participantService = participantService;
        this.voteResultService = voteResultService;
        this.kakaoWendyScheduler = kakaoWendyScheduler;
        this.kakaoTimePollScheduler = kakaoTimePollScheduler;
        this.kakaoBotApiClient = kakaoBotApiClient;
        this.timePollService = timePollService;
        this.objectMapper = objectMapper;
        this.pendingSessionRepository = pendingSessionRepository;
        this.kakaoNotifier = kakaoNotifier;
    }

    public enum SessionState {
        IDLE,
        WAITING_PARTICIPANTS,
        WAITING_WEEKS,
        VOTE_CREATED
    }
    // ========== 인메모리 맵 ==========
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, List<String>> participants = new ConcurrentHashMap<>();
    private final Map<String, List<String>> participantDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionVoteId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionShareUrl = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> voteCreatedAt = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, Long> groupVoteId = new ConcurrentHashMap<>();
    private final Map<Long, String> voteIdToGroupKey = new ConcurrentHashMap<>();
    private final Map<String, Long> groupTimePollId = new ConcurrentHashMap<>();
    private final Map<Long, String> timePollIdToGroupKey = new ConcurrentHashMap<>();

    @Deprecated
    private final Map<String, Long> userVoteId = sessionVoteId;
    @Deprecated
    private final Map<String, String> userShareUrl = sessionShareUrl;

    // ========== 세션 관리 ==========
    /**
     * 세션 시작 (웬디 시작)
     */
    public KakaoResponse startSession(String userKey) {
        // 이전 사이클(종료/재투표 없이 "시작"만 반복한 경우)의 좀비 스케줄이 남아있지 않도록 정리
        kakaoWendyScheduler.stopSchedule(userKey);
        kakaoWendyScheduler.stopCollectionSchedule(userKey);
        pendingSessionRepository.findBySessionKeyAndStatus(userKey, PendingSessionStatus.COLLECTING)
                .ifPresent(pendingSessionRepository::delete);

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
        kakaoWendyScheduler.stopCollectionSchedule(userKey);
        activeSessions.remove(userKey);
        participants.remove(userKey);
        userVoteId.remove(userKey);
        participantDisplayNames.remove(userKey);
        userShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);
        sessionStates.remove(userKey);

        pendingSessionRepository.findBySessionKeyAndStatus(userKey, PendingSessionStatus.COLLECTING)
                .ifPresent(pendingSessionRepository::delete);

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

    // ========== 참여자 수집 (신규 흐름) ==========
    /**
     * 주차 선택 완료 → 날짜범위 확정 + 6시간 참여자 수집 시작
     * (기존 createVote()를 즉시 호출하는 대신, 이 메서드가 대체합니다.)
     */
    @Transactional
    public KakaoResponse startCollecting(String sessionKey, int weeks, String botGroupKey) {
        // 1. 날짜 범위 계산 (기존 createVote와 동일 로직)
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

        // 2. 이전에 수집 중이던 세션이 남아있으면 정리 (재선택 대비)
        pendingSessionRepository.findBySessionKeyAndStatus(sessionKey, PendingSessionStatus.COLLECTING)
                .ifPresent(pendingSessionRepository::delete);
        kakaoWendyScheduler.stopCollectionSchedule(sessionKey);

        // 3. PendingSession 저장
        PendingSession pending = new PendingSession(sessionKey, botGroupKey, startDate, endDate);
        pendingSessionRepository.save(pending);

        sessionStates.put(sessionKey, SessionState.WAITING_PARTICIPANTS);

        // 4. 24시간 수집 타이머 시작
        kakaoWendyScheduler.startCollectionSchedule(sessionKey);

        log.info("[Kakao When:D] Collecting started: sessionKey={}, weeks={}, startDate={}, endDate={}",
                sessionKey, weeks, startDate, endDate);

        String weekLabel = weeks == 0 ? "이번 주" : weeks + "주 뒤";

        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text(weekLabel + "를 선택하셨어요\n6시간 동안 참여자를 모을게요 :D")
                                                .build())
                                        .build(),
                                KakaoResponse.Output.builder()
                                        .textCard(KakaoResponse.BasicCard.builder()
                                                .title("이 약속에 참여하시나요?")
                                                .description("아래 버튼을 눌러주세요!\n6시간 후에 모인 분들끼리 투표를 시작할게요🙂")
                                                .buttons(List.of(
                                                        KakaoResponse.messageButton("참여할래요", "참여"),
                                                        KakaoResponse.messageButton("모집 종료할게요", "모집종료")
                                                ))
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * "참여" 버튼 클릭 처리. 수집 중인 세션에 botUserKey만 누적합니다.
     * (요청하신 대로 이름 등록 등은 하지 않고, DB에 추가만 합니다.)
     */
    @Transactional
    public KakaoResponse joinPendingSession(String sessionKey, String botUserKey) {
        Optional<PendingSession> opt = pendingSessionRepository
                .findBySessionKeyAndStatus(sessionKey, PendingSessionStatus.COLLECTING);

        if (opt.isEmpty()) {
            return KakaoResponse.simpleText("지금은 참여를 받는 시간이 아니에요 :(");
        }

        if (botUserKey == null || botUserKey.isBlank()) {
            log.warn("[Kakao When:D] joinPendingSession called without botUserKey. sessionKey={}", sessionKey);
            return KakaoResponse.simpleText("참여자 정보를 확인하지 못했어요. 잠시 후 다시 시도해주세요.");
        }

        PendingSession pending = opt.get();
        pending.getBotUserKeys().add(botUserKey);
        pendingSessionRepository.save(pending);

        log.info("[Kakao When:D] Participant joined: sessionKey={}, botUserKey={}, count={}",
                sessionKey, botUserKey, pending.getBotUserKeys().size());

        return KakaoResponse.simpleText("참여 완료했어요! 🙌");
    }

    /**
     * "모집 종료할게요" 버튼 처리. 6시간(설정값) 다 안 기다리고, 지금까지 모인 참여자로
     * 즉시 투표를 생성한다. 예약된 타이머는 취소하고, finalizeCollecting()을 바로 호출.
     *
     * finalizeCollecting()이 내부적으로 "vote_created_D" 이벤트를 발송해 채팅방에
     * 완료 카드를 보여주므로, 이 메서드의 스킬 응답 자체는 빈 텍스트로 둔다
     * (타이머로 자연 종료될 때와 동일한 방식 — 알림 경로를 하나로 통일).
     */
    @Transactional
    public KakaoResponse closeCollectionNow(String sessionKey) {
        Optional<PendingSession> opt = pendingSessionRepository
                .findBySessionKeyAndStatus(sessionKey, PendingSessionStatus.COLLECTING);

        if (opt.isEmpty()) {
            return KakaoResponse.simpleText("지금은 모집 중이 아니에요 :(");
        }

        PendingSession pending = opt.get();
        if (pending.getBotUserKeys().isEmpty()) {
            return KakaoResponse.simpleText(
                    "아직 아무도 참여하지 않았어요! \n스케쥴리를 종료하고 싶으면 @스케쥴리 종료를 입력해주세요!"
            );
        }

        log.info("[Kakao When:D] Collection closed early: sessionKey={}", sessionKey);

        kakaoWendyScheduler.stopCollectionSchedule(sessionKey);
        finalizeCollecting(sessionKey);

        return KakaoResponse.simpleText("");
    }

    /**
     * 24시간 경과 후 스케줄러가 호출. 그동안 모인 참여자로 실제 Vote를 생성합니다.
     */
    @Transactional
    public void finalizeCollecting(String sessionKey) {
        Optional<PendingSession> opt = pendingSessionRepository
                .findBySessionKeyAndStatus(sessionKey, PendingSessionStatus.COLLECTING);

        if (opt.isEmpty()) {
            log.warn("[Kakao When:D] finalizeCollecting: no pending session. sessionKey={}", sessionKey);
            return;
        }

        PendingSession pending = opt.get();

        // 1. Vote 생성 (이름 목록 없이 날짜범위만)
        CreateVoteReq req = new CreateVoteReq(
                "카카오 투표",
                pending.getStartDate(),
                pending.getEndDate(),
                null
        );
        VoteSummary summary = voteService.create(req);
        Long voteId = summary.id();
        String shareUrl = summary.shareUrl();

        // 2. 참여자 등록 (botUserKey만, displayName은 "미등록"으로 시작 → 이후 프론트에서 채움)
        for (String botUserKey : pending.getBotUserKeys()) {
            participantService.addIfNotExists(voteId, botUserKey);
        }

        // 3. 세션 상태를 VOTE_CREATED로 전환 (기존 createVote와 동일하게 채워줌)
        voteCreatedAt.put(sessionKey, LocalDateTime.now());
        sessionVoteId.put(sessionKey, voteId);
        sessionShareUrl.put(sessionKey, shareUrl);
        sessionStates.put(sessionKey, SessionState.VOTE_CREATED);

        String botGroupKey = pending.getBotGroupKey();
        if (botGroupKey != null && !botGroupKey.isBlank()) {
            groupVoteId.put(botGroupKey, voteId);
            voteIdToGroupKey.put(voteId, botGroupKey);
            voteService.updateBotGroupKey(voteId, botGroupKey);
        }

        // 4. PendingSession 마감 처리
        pending.setStatus(PendingSessionStatus.FINALIZED);
        pendingSessionRepository.save(pending);

        // 5. 기존 리마인더 스케줄(3분/30분/.../25시간) 시작
        kakaoWendyScheduler.startSchedule(sessionKey);

        log.info("[Kakao When:D] Collecting finalized: sessionKey={}, voteId={}, participantCount={}, shareUrl={}",
                sessionKey, voteId, pending.getBotUserKeys().size(), shareUrl);

        // 6. 채팅방에 능동 알림 (그룹인 경우만; 카카오 관리자센터에 "vote_created_D" 이벤트 이름을
        //    미리 블록에 등록해두어야 실제로 메시지가 나갑니다)
        if (botGroupKey != null && !botGroupKey.isBlank()) {
            kakaoNotifier.sendEventToGroup(botGroupKey, "vote_created_D");
        }
    }

    // ========== 참석자 관리 (기존 코드 - 지금은 사용되지 않는 경로, 정리 전까지 유지) ==========
    /**
     * 참석자 추가 (botUserKey 리스트 입력)
     *
     * @deprecated startCollecting/joinPendingSession 흐름으로 대체됨. 컨트롤러에서 더 이상 호출하지 않음.
     */
    @Deprecated
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

    // ========== 투표 생성 (기존 코드 - 즉시생성 경로. startCollecting으로 대체되었으나
    //             다른 곳에서 참조할 수 있어 남겨둠) ==========
    /**
     * 투표 생성 (주차 선택 후, 즉시 생성하는 옛 버전)
     *
     * @deprecated startCollecting()이 대신 호출됩니다. 즉시 생성이 필요한 특수 케이스가
     *             아니라면 사용하지 마세요.
     */
    @Deprecated
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

        KakaoResponse response = KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text(weekLabel + "를 선택하셨어요\n해당 일정의 투표를 만들어드릴게요 :D")
                                                .build())
                                        .build(),
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text("(투표 늦게 하는 사람 대머리 🧑‍🦲)")
                                                .build())
                                        .build(),
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
     * 24시간 참여자 수집 완료 알림 응답 생성.
     * KakaoNotifier.sendEventToGroup(botGroupKey, "vote_created_D")로 발생한 이벤트가
     * 관리자센터의 블록을 거쳐 이 값을 만드는 스킬(/kakao/skill/notify/vote-created)을 호출한다.
     */
    public KakaoResponse buildVoteCreatedResponse(String sessionKey) {
        Long voteId = sessionVoteId.get(sessionKey);
        String shareUrl = sessionShareUrl.get(sessionKey);

        if (voteId == null || shareUrl == null) {
            log.warn("[Kakao When:D] buildVoteCreatedResponse: voteId/shareUrl 없음. sessionKey={}", sessionKey);
            return KakaoResponse.simpleText("");
        }

        int participantCount = participantService.getParticipants(voteId).size();

        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text("참여자 모집이 끝났어요! (" + participantCount + "명)\n이제 날짜 투표를 시작할게요 :D")
                                                .build())
                                        .build(),
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
                        .build())
                .build();
    }

    /**
     * 주차 파싱 (0 = 이번 주, 1~6 = n주 뒤)
     */
    public Integer parseWeeks(String input) {
        if (input.contains("이번")) {
            return 0;
        }
        if (input.contains("1주")) {
            return 1;
        }
        if (input.contains("2주")) {
            return 2;
        }
        if (input.contains("3주")) {
            return 3;
        }
        if (input.contains("4주")) {
            return 4;
        }
        if (input.contains("5주")) {
            return 5;
        }
        if (input.contains("6주")) {
            return 6;
        }

        // 숫자만 추출
        String numbers = input.replaceAll("[^0-9]", "");
        if (!numbers.isEmpty()) {
            try {
                int weeks = Integer.parseInt(numbers);
                if (weeks >= 0 && weeks <= 6) {
                    return weeks;
                }
            } catch (NumberFormatException ignored) {
            }
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

        if (top3.isEmpty()) {
            sb.append("아직 집계할 수 있는 순위 결과가 없어요 :(");
        }

        return textOnly(sb.toString().trim());
    }

    /**
     * 재투표 (동일 참석자로 새 투표 생성)
     */
    public KakaoResponse revote(String userKey) {
        // 기존 투표 데이터 정리
        kakaoWendyScheduler.stopSchedule(userKey);
        kakaoWendyScheduler.stopCollectionSchedule(userKey);

        Long voteId = sessionVoteId.get(userKey);
        if (voteId != null) {
            String groupKey = voteIdToGroupKey.remove(voteId);
            if (groupKey != null) {
                groupVoteId.remove(groupKey);
            }
        }
        sessionVoteId.remove(userKey);
        sessionShareUrl.remove(userKey);
        voteCreatedAt.remove(userKey);

        // 수집 중이던 PendingSession이 남아있다면 정리
        pendingSessionRepository.findBySessionKeyAndStatus(userKey, PendingSessionStatus.COLLECTING)
                .ifPresent(pendingSessionRepository::delete);

        // 그냥 시작과 동일하게
        return startSession(userKey);
    }

    /**
     * 도움말
     *
     * 실제 명령어 목록(@시작/@종료 등) 텍스트는 여기서 만들지 않는다.
     * 버튼의 action을 "guide"로 두면, 관리자센터에 이미 설정해둔 "챗봇 도움말" 콘텐츠를
     * 카카오가 자동으로 보여준다. 그 콘텐츠 문구를 바꾸고 싶으면 관리자센터에서 수정하면 됨.
     */
    public KakaoResponse help() {
        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .textCard(KakaoResponse.BasicCard.builder()
                                                .description("아래 버튼을 눌러서 확인하세요.")
                                                .buttons(List.of(
                                                        KakaoResponse.Button.builder()
                                                                .label("도움말")
                                                                .action("guide")
                                                                .build()
                                                ))
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * 알 수 없는 입력 처리
     */
    public KakaoResponse unknownInput(String userKey) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(KakaoResponse.Template.builder()
                        .outputs(List.of(
                                KakaoResponse.Output.builder()
                                        .simpleText(KakaoResponse.SimpleText.builder()
                                                .text("음... 무슨 말인지 잘 모르겠어요 🤔\n\n"
                                                        + "혹시 스케쥴리를 시작하고 싶으신가요?\n"
                                                        + "@스케쥴리 시작 이라고 말해주세요!\n\n"
                                                        + "사용법이 궁금하시면 @스케쥴리 도움말 을 확인해주세요 :D")
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    // 투표 알림 메시지 생성
    /**
     * 투표 알림 메시지 생성
     */
    public KakaoResponse buildRemindResponse(String sessionKey, String timing) {
        Long voteId = sessionVoteId.get(sessionKey);
        if (voteId == null) {
            return KakaoResponse.simpleText("진행 중인 투표가 없어요.");
        }

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
        all.forEach(p
                -> log.info("userKey={}, kakaoId={}, submitted={}",
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

        // 멘션 구성 - Map<String, Map<String, String>>
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
            if (i < nonVoters.size() - 1) {
                mentionSb.append(", ");
            }
        }

        String mentionStr = mentionSb.toString();

        String message = switch (timing) {
            case "30min", "2hour" ->
                mentionStr + " 투표가 시작됐어요! \n다른 분들을 위해 빠른 참여 부탁드려요 :D";
            case "6hour" ->
                "다들 " + mentionStr + " 님의 투표를 기다리고 있어요🤔";
            case "12hour" ->
                "스케쥴리 기다리다 지쳐버림…🥹\n" + mentionStr + " 님 혹시 대머리신가요…?";
            default ->
                mentionStr + " 아직 투표 안 하셨어요!";
        };

        log.info("[REMIND] final message = {}", message);

        KakaoResponse res = KakaoResponse.textWithQuickRepliesAndMentions(message, mentions.isEmpty() ? null : mentions, null);

        try {
            log.info("[REMIND] response JSON = {}", objectMapper.writeValueAsString(res));
        } catch (Exception e) {
            log.warn("[REMIND] response JSON 로깅 실패: {}", e.getMessage());
        }
        return res;
    }

    /**
     * 최후통첩
     */
    public KakaoResponse buildFinalNoticeResponse(String sessionKey) {
        Long voteId = sessionVoteId.get(sessionKey);
        if (voteId == null) {
            return KakaoResponse.simpleText("진행 중인 투표가 없어요.");
        }

        List<ParticipantStatusRes> nonVoters = participantService.getParticipantStatusByVoteId(voteId)
                .stream()
                .filter(s -> !s.submitted())
                .toList();

        if (nonVoters.isEmpty()) {
            return buildCompletionResponse(sessionKey);
        }

        LocalDateTime createdAt = voteCreatedAt.get(sessionKey);
        String deadline = createdAt != null
                ? createdAt.atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.of("Asia/Seoul"))
                        .plusHours(25)
                        .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                : "곧";

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
            if (i < nonVoters.size() - 1) {
                mentionSb.append(", ");
            }
        }

        VoteResultRes result = voteResultService.getVoteResult(voteId);
        String topResult = "미정";
        if (result != null && result.rankings() != null) {
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

        StringBuilder rankSb = new StringBuilder();

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
        }

        List<KakaoResponse.Button> buttons = new ArrayList<>();
        buttons.add(KakaoResponse.Button.builder()
                .label("좋아요")
                .action("message")
                .messageText("시간 투표")
                .build());
        buttons.add(KakaoResponse.messageButton("재투표할래요", "재투표"));
        buttons.add(KakaoResponse.messageButton("종료할게요", "종료"));

        List<KakaoResponse.Output> outputs = new ArrayList<>();

        if (!rankSb.isEmpty()) {
            outputs.add(KakaoResponse.Output.builder()
                    .simpleText(KakaoResponse.SimpleText.builder()
                            .text("🏆 투표 결과\n\n" + rankSb.toString().trim())
                            .build())
                    .build());
        }

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
            timePollService.delete(existingTimePollId);
            groupTimePollId.remove(botGroupKey);
            timePollIdToGroupKey.remove(existingTimePollId);
        }
        VoteResultRes result = voteResultService.getVoteResult(voteId);
        if (result == null || result.rankings() == null || result.rankings().isEmpty()) {
            throw new RuntimeException("투표 결과가 없습니다.");
        }

        List<RankingRes> top3 = result.rankings().stream()
                .filter(r -> r.rank() != null && r.rank() <= 3)
                .sorted(Comparator.comparingInt(RankingRes::rank))
                .toList();

        if (top3.isEmpty()) {
            throw new RuntimeException("유효한 순위 결과가 없습니다.");
        }

        RankingRes top = top3.get(0);
        TimePollCreateRequest req = new TimePollCreateRequest();
        req.setVoteId(voteId);
        req.setConfirmedDate(top.date().format(DateTimeFormatter.ofPattern("M월 d일")));
        req.setPeriod(Period.valueOf(top.period()));

        TimePoll timePoll = timePollService.create(req);
        if (botGroupKey != null) {
            timePollService.updateBotGroupKey(timePoll.getId(), botGroupKey);
        }
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
        List<Participant> pending = timePollService.getPendingParticipants(timePollId);

        if (pending.isEmpty()) {
            return KakaoResponse.simpleText("");
        }

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

        return KakaoResponse.textWithQuickRepliesAndMentions(
                message,
                mentions.isEmpty() ? null : mentions,
                null
        );
    }

    public KakaoResponse buildTimeFinalNoticeResponse(Long timePollId, String botGroupKey) {
        List<Participant> nonVoters = timePollService.getPendingParticipants(timePollId);

        if (nonVoters.isEmpty()) {
            kakaoTimePollScheduler.stopSchedule(timePollId);
            kakaoBotApiClient.sendEventMessage(botGroupKey, "finish_T");
            return KakaoResponse.simpleText("");
        }

        Instant createdAt = timePollService.getTimePollCreatedAt(timePollId);
        String deadline = createdAt
                .atZone(ZoneId.of("Asia/Seoul"))
                .plusHours(25)
                .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"));

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

        if (botGroupKey != null && !botGroupKey.isBlank()) {
            log.info("[Kakao When:D] Group session started: sessionKey={}, botGroupKey={}", sessionKey, botGroupKey);
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

    /**
     * timePollId로 botGroupKey 조회
     */
    public String getBotGroupKeyByTimePollId(Long timePollId) {
        return timePollIdToGroupKey.get(timePollId);
    }

    /**
     * 시간투표 재시작시 매핑 복구
     */
    public void restoreTimePollMapping(Long timePollId, String botGroupKey) {
        groupTimePollId.put(botGroupKey, timePollId);
        timePollIdToGroupKey.put(timePollId, botGroupKey);
        log.info("[WendyService] Restored timePoll mapping: botGroupKey={}, timePollId={}", botGroupKey, timePollId);
    }

    /**
     * 날짜투표 재시작시 매핑 복구
     */
    public void restoreVoteMapping(Long voteId, String botGroupKey) {
        groupVoteId.put(botGroupKey, voteId);
        voteIdToGroupKey.put(voteId, botGroupKey);
        sessionVoteId.put(botGroupKey, voteId); // sessionKey = botGroupKey
        log.info("[WendyService] Restored vote mapping: botGroupKey={}, voteId={}", botGroupKey, voteId);
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
            case MONDAY ->
                "월";
            case TUESDAY ->
                "화";
            case WEDNESDAY ->
                "수";
            case THURSDAY ->
                "목";
            case FRIDAY ->
                "금";
            case SATURDAY ->
                "토";
            case SUNDAY ->
                "일";
        };
    }
}
package com.workingdead.chatbot.discord.service;

import com.workingdead.meet.dto.ParticipantDtos.ParticipantRes;
import com.workingdead.meet.dto.ParticipantDtos.ParticipantStatusRes;
import com.workingdead.meet.dto.VoteDtos.CreateVoteReq;
import com.workingdead.meet.dto.VoteDtos.VoteSummary;
import com.workingdead.meet.dto.VoteResultDtos.RankingRes;
import com.workingdead.meet.dto.VoteResultDtos.VoteResultRes;
import com.workingdead.meet.service.ParticipantService;
import com.workingdead.meet.service.VoteResultService;
import com.workingdead.meet.service.VoteService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class DiscordWendyServiceImpl implements DiscordWendyService {

    private final VoteService voteService;
    private final ParticipantService participantService;
    private final VoteResultService voteResultService;

    // 활성 세션 관리 (channelId 기반)
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    // 디스코드 참석자 (channelId -> (discordUserId -> displayName))
    private final Map<String, Map<String, String>> participants = new ConcurrentHashMap<>();

    // 생성된 투표 id (channelId -> voteId)
    private final Map<String, Long> channelVoteId = new ConcurrentHashMap<>();

    // 생성된 투표 링크
    private final Map<String, String> channelShareUrl = new ConcurrentHashMap<>();

    // 투표 생성 여부 (재투표 체크용)
    private final Set<String> hasVote = ConcurrentHashMap.newKeySet();

    // 투표 생성 시각 및 기준 주차
    private final Map<String, LocalDateTime> voteCreatedAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> voteWeeks = new ConcurrentHashMap<>();

    @Override
    public void startSession(String channelId, List<Member> members) {
        activeSessions.add(channelId);

        participants.put(channelId, new ConcurrentHashMap<>());

        channelVoteId.remove(channelId);
        channelShareUrl.remove(channelId);

        voteCreatedAt.remove(channelId);
        voteWeeks.remove(channelId);
        System.out.println("[Discord When:D] Session started: " + channelId);
    }

    @Override
    public boolean isSessionActive(String channelId) {
        return activeSessions.contains(channelId);
    }

    @Override
    public void endSession(String channelId) {
        activeSessions.remove(channelId);
        participants.remove(channelId);

        channelVoteId.remove(channelId);
        channelShareUrl.remove(channelId);

        hasVote.remove(channelId);
        voteCreatedAt.remove(channelId);
        voteWeeks.remove(channelId);
        System.out.println("[Discord When:D] Session ended: " + channelId);
    }

    @Override
    public void addParticipant(String channelId, String memberId, String memberName) {
        Map<String, String> channelParticipants =
                participants.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>());
        String previousName = channelParticipants.put(memberId, memberName);

        Long voteId = channelVoteId.get(channelId);
        if (voteId == null) {
            System.out.println("[Discord When:D] Participant added BEFORE vote: " + memberName
                    + " (discordId=" + memberId + ")");
            return;
        }

        if (previousName != null) {
            System.out.println("[Discord When:D] Participant already exists AFTER vote: " + memberName
                    + " (discordId=" + memberId + ")");
            return;
        }

        ParticipantRes pRes = participantService.add(voteId, memberName);
        System.out.println("[Discord When:D] Participant added AFTER vote: " + memberName
                + " (discordId=" + memberId + ", participantId=" + pRes.id() + ")");
    }

    @Override
    public void removeParticipant(String channelId, String memberId) {
        Map<String, String> channelParticipants = participants.get(channelId);
        String removedName = null;
        if (channelParticipants != null) {
            removedName = channelParticipants.remove(memberId);
        }

        Long voteId = channelVoteId.get(channelId);
        if (voteId == null) {
            System.out.println("[Discord When:D] Participant removed BEFORE vote: "
                    + (removedName != null ? removedName : memberId)
                    + " (discordId=" + memberId + ")");
        } else {
            System.out.println("[Discord When:D] Participant removed AFTER vote (domain not deleted): "
                    + (removedName != null ? removedName : memberId)
                    + " (discordId=" + memberId + ")");
        }
    }


    @Override
    public String createVote(String channelId, String channelName, int weeks) {
        hasVote.add(channelId);
        voteCreatedAt.put(channelId, LocalDateTime.now());
        voteWeeks.put(channelId, weeks);

        LocalDate today = LocalDate.now();
        LocalDate startDate;
        LocalDate endDate;

        if (weeks == 0) {
            startDate = today;
            int daysToSunday = DayOfWeek.SUNDAY.getValue() - today.getDayOfWeek().getValue();
            endDate = today.plusDays(Math.max(daysToSunday, 0));
        } else  {
            LocalDate mondayThisWeek = today.with(DayOfWeek.MONDAY);
            startDate = mondayThisWeek.plusWeeks(weeks);
            endDate = startDate.plusDays(6);
        }

        Map<String, String> channelParticipants = participants.getOrDefault(channelId, Map.of());
        List<String> participantNames = new ArrayList<>(channelParticipants.values());

        CreateVoteReq req = new CreateVoteReq(
                channelName,
                startDate,
                endDate,
                participantNames.isEmpty() ? null : participantNames
        );

        VoteSummary summary = voteService.create(req);
        Long voteId = summary.id();
        String shareUrl = summary.shareUrl();
        channelShareUrl.put(channelId, shareUrl);

        channelVoteId.put(channelId, voteId);

        System.out.println("[Discord When:D] Vote created for channel " + channelId + " (voteId=" + voteId
                + ", (weeks=" + weeks + "))");
        return shareUrl;
    }

    @Override
    public VoteResultRes getVoteStatus(String channelId) {
        Long voteId = channelVoteId.get(channelId);
        if (voteId == null) {
            return null;
        }

        return voteResultService.getVoteResult(voteId);
    }

    @Override
    public List<String> getNonVoterIds(String channelId) {
        Long voteId = channelVoteId.get(channelId);
        if (voteId == null) {
            return List.of();
        }

        Map<String, String> channelParticipants = participants.getOrDefault(channelId, Map.of());
        if (channelParticipants.isEmpty()) {
            return List.of();
        }

        List<ParticipantStatusRes> statuses =
                participantService.getParticipantStatusByVoteId(voteId);

        Set<String> nonSubmittedNames = statuses.stream()
                .filter(s -> !Boolean.TRUE.equals(s.submitted()))
                .map(ParticipantStatusRes::displayName)
                .collect(Collectors.toSet());

        List<String> nonVoters = new ArrayList<>();
        for (Map.Entry<String, String> entry : channelParticipants.entrySet()) {
            if (nonSubmittedNames.contains(entry.getValue())) {
                nonVoters.add(entry.getKey());
            }
        }

        return nonVoters;
    }

    @Override
    public boolean hasPreviousVote(String channelId) {
        return hasVote.contains(channelId);
    }

    @Override
    public String recreateVote(String channelId, String channelName, int weeks) {
        channelVoteId.remove(channelId);

        String shareUrl = createVote(channelId, channelName, weeks);
        System.out.println("[Discord When:D] Vote recreated for channel " + channelId + " (weeks=" + weeks + ")");
        return shareUrl;
    }

    @Override
    public String getShareUrl(String channelId) {
        return channelShareUrl.get(channelId);
    }

    @Override
    public String getVoteDeadline(String channelId) {
        LocalDateTime createdAt = voteCreatedAt.get(channelId);
        if (createdAt == null) {
            return "No vote created.";
        }
        LocalDateTime deadline = createdAt.plusHours(24);

        return deadline.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    @Override
    public String getTopRankedDateTime(String channelId) {
        Long voteId = channelVoteId.get(channelId);
        if (voteId == null) {
            return "1순위 일정";
        }

        VoteResultRes res = voteResultService.getVoteResult(voteId);
        if (res == null || res.rankings() == null || res.rankings().isEmpty()) {
            return "1순위 일정";
        }

        RankingRes top = res.rankings().stream()
                .filter(r -> r.rank() != null && r.rank() == 1)
                .findFirst()
                .orElse(res.rankings().get(0));

        LocalDate date = top.date();
        String period = top.period();

        String dayLabel = switch (date.getDayOfWeek()) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };

        String periodLabel = "LUNCH".equals(period) ? "점심" : "저녁";

        return date.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd"))
                + "(" + dayLabel + ") "
                + periodLabel;
    }

    @Override
    public String getChannelIdByVoteId(Long voteId) {
        for (Map.Entry<String, Long> entry : channelVoteId.entrySet()) {
            if (entry.getValue().equals(voteId)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
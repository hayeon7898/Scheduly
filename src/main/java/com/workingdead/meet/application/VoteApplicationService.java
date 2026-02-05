package com.workingdead.meet.application;

import com.workingdead.chatbot.discord.service.DiscordWendyNotifier;
import com.workingdead.chatbot.discord.service.DiscordWendyService;
import com.workingdead.meet.dto.ParticipantDtos.ParticipantScheduleRes;
import com.workingdead.meet.dto.ParticipantDtos.SubmitScheduleReq;
import com.workingdead.meet.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VoteApplicationService {

    private final ParticipantService participantService;
    private final DiscordWendyService discordWendyService;
    private final DiscordWendyNotifier discordWendyNotifier;
    private final JDA jda;

    @Transactional
    public ParticipantScheduleRes submitSchedule(Long participantId, SubmitScheduleReq req) {
        // 1) 도메인 로직: 스케줄 제출
        ParticipantScheduleRes res =
                participantService.submitSchedule(participantId, req);

        // 2) participantId -> voteId 조회
        Long voteId = participantService.getVoteIdByParticipantId(participantId);
        if (voteId == null) {
            return res;
        }

        // 3) voteId -> channelId 매핑 (DiscordWendyService 관리)
        String channelId = discordWendyService.getChannelIdByVoteId(voteId);
        if (channelId == null || channelId.isBlank()) {
            return res;
        }

        // 4) JDA로 TextChannel 조회
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel != null) {
            // 5) 디스코드에 즉시 최신 투표 현황 공유
            discordWendyNotifier.shareVoteStatus(channel);
        }

        return res;
    }


}

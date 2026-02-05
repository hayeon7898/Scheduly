package com.workingdead.chatbot.discord.service;

import com.workingdead.meet.dto.VoteResultDtos.VoteResultRes;
import net.dv8tion.jda.api.entities.Member;

import java.util.List;

public interface DiscordWendyService {
    void startSession(String channelId, List<Member> participants);
    boolean isSessionActive(String channelId);
    void endSession(String channelId);

    void addParticipant(String channelId, String memberId, String memberName);
    void removeParticipant(String channelId, String memberId);

    String createVote(String channelId, String channelName, int weeks);
    VoteResultRes getVoteStatus(String channelId);
    List<String> getNonVoterIds(String channelId);

    String getShareUrl(String channelId);
    boolean hasPreviousVote(String channelId);
    String recreateVote(String channelId, String channelName, int weeks);

    String getVoteDeadline(String channelId);
    String getTopRankedDateTime(String channelId);

    String getChannelIdByVoteId(Long voteId);
}
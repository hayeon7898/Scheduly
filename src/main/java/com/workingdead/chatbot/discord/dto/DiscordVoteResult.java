package com.workingdead.chatbot.discord.dto;

import java.util.List;

public class DiscordVoteResult {
    private String voteUrl;
    private List<RankResult> rankings;

    public DiscordVoteResult() {}

    public DiscordVoteResult(String voteUrl, List<RankResult> rankings) {
        this.voteUrl = voteUrl;
        this.rankings = rankings;
    }

    public boolean isEmpty() {
        return rankings == null || rankings.isEmpty();
    }

    public String getVoteUrl() { return voteUrl; }
    public void setVoteUrl(String voteUrl) { this.voteUrl = voteUrl; }
    public List<RankResult> getRankings() { return rankings; }
    public void setRankings(List<RankResult> rankings) { this.rankings = rankings; }

    public static class RankResult {
        private int rank;
        private String dateTime;
        private List<Voter> voters;

        public RankResult() {}
        public RankResult(int rank, String dateTime, List<Voter> voters) {
            this.rank = rank;
            this.dateTime = dateTime;
            this.voters = voters;
        }

        public int getRank() { return rank; }
        public String getDateTime() { return dateTime; }
        public List<Voter> getVoters() { return voters; }
    }

    public static class Voter {
        private String name;
        private Integer priority;

        public Voter() {}
        public Voter(String name, Integer priority) {
            this.name = name;
            this.priority = priority;
        }

        public String getName() { return name; }
        public Integer getPriority() { return priority; }

        @Override
        public String toString() {
            return priority != null ? name + "(" + priority + ")" : name;
        }
    }
}
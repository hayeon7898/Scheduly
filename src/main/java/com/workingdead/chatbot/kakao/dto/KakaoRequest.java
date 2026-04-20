package com.workingdead.chatbot.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * 카카오 i 오픈빌더 스킬 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoRequest {

    private Intent intent;
    private UserRequest userRequest;
    private Bot bot;
    private Action action;
    private Chat chat;  // 그룹 채팅방 정보

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chat {
        private String id;      // 채팅방 ID (botGroupKey)
        private String type;    // 채팅방 타입
        private Map<String, String> properties;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Intent {
        private String id;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserRequest {
        private String timezone;
        private Map<String, Object> params;
        private Block block;
        private String utterance;
        private String lang;
        private User user;
        private Chat chat;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Block {
        private String id;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        private String id;
        private String type;
        private Properties properties;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Properties {
        private String plusfriendUserKey;
        private String appUserId;
        private Boolean isFriend;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bot {
        private String id;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        private String name;
        private String id;
        private Map<String, String> params;
        private Map<String, Object> detailParams;
        private Map<String, Object> clientExtra;
    }

    // 편의 메서드
    public String getUserKey() {
        if (userRequest == null || userRequest.getUser() == null) return null;

        Properties p = userRequest.getUser().getProperties();
        if (p != null) {
            String plusfriendUserKey = p.getPlusfriendUserKey();
            if (plusfriendUserKey != null && !plusfriendUserKey.isBlank()) {
                return plusfriendUserKey;
            }
        }
        String id = userRequest.getUser().getId();
        return (id == null || id.isBlank()) ? null : id;
    }

    public String getUtterance() {
        if (userRequest != null) {
            return userRequest.getUtterance();
        }
        return null;
    }

    public String getParam(String key) {
        if (key == null || key.isBlank()) return null;

        // 1) action.params 우선
        if (action != null && action.getParams() != null) {
            String v = action.getParams().get(key);
            if (v != null) return v;
        }

        // 2) userRequest.params fallback (오픈빌더 설정에 따라 여기로 들어오는 케이스 대비)
        if (userRequest != null && userRequest.getParams() != null) {
            Object v = userRequest.getParams().get(key);
            return v == null ? null : String.valueOf(v);
        }

        return null;
    }

    public String getBotId() {
        if (bot != null) {
            return bot.getId();
        }
        return null;
    }

    /**
     * 그룹 채팅방 키 (botGroupKey) 조회
     */
    // public String getBotGroupKey() {
    //     if (chat == null) return null;
    //     // properties.botGroupKey 우선
    //     if (chat.getProperties() != null) {
    //         String key = chat.getProperties().get("botGroupKey");
    //         if (key != null && !key.isBlank()) return key;
    //     }
    //     // fallback: chat.id
    //     return chat.getId();
    // }
    public String getBotGroupKey() {
        if (userRequest != null && userRequest.getChat() != null) {
            Chat chat = userRequest.getChat();
            if (chat.getProperties() != null) {
                String key = chat.getProperties().get("botGroupKey");
                if (key != null && !key.isBlank()) return key;
            }
            return chat.getId();
        }
        // 기존 최상위 chat fallback
        if (chat != null) {
            if (chat.getProperties() != null) {
                String key = chat.getProperties().get("botGroupKey");
                if (key != null && !key.isBlank()) return key;
            }
            return chat.getId();
        }
        return null;
    }
    /**
     * 그룹 채팅방 여부 확인
     */
    public boolean isGroupChat() {
        return chat != null && chat.getId() != null;
    }

    /**
     * 그룹챗 사용자 식별용 botUserKey
     * - PRD 기준: 멘션/참석자 식별 키로 사용
     * - 현재 구조에서는 user.id를 사용
     */
    public String getBotUserKey() {
        if (userRequest != null && userRequest.getUser() != null) {
            String id = userRequest.getUser().getId();
            return (id == null || id.isBlank()) ? null : id;
        }
        return null;
    }
}
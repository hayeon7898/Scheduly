package com.workingdead.chatbot.kakao.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.workingdead.config.KakaoConfig;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 카카오 Bot API 클라이언트
 *
 * - Event API: 그룹 채팅방에 메시지 발송
 * - Chatroom Info API: 참여 채팅방 조회, 멤버 조회
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoBotApiClient {

    private final KakaoConfig kakaoConfig;
    private final RestTemplate kakaoRestTemplate;

    // ==================== Event API ====================

    /**
     * 그룹 채팅방에 이벤트 메시지 발송
     * POST /v2/bots/{botId}/group
     *
     * @param botGroupKeys 발송 대상 채팅방 키 리스트 (최대 100개)
     * @param eventName    관리자센터에 등록한 이벤트 블록 이름
     * @return EventResponse (taskId, status)
     */
    public EventResponse sendEventMessage(List<String> botGroupKeys, String eventName) {
        String url = KakaoConfig.BOT_API_BASE_URL + "/v2/bots/" + kakaoConfig.getBotId() + "/group";

        EventRequest request = EventRequest.builder()
                .chat(botGroupKeys.stream()
                        .map(key -> Map.of("id", key))
                        .toList())
                .event(Map.of("name", eventName))
                .build();

        HttpEntity<EventRequest> entity = new HttpEntity<>(request, createHeaders());

        try {
            ResponseEntity<EventResponse> response = kakaoRestTemplate.exchange(
                    url, HttpMethod.POST, entity, EventResponse.class);
            log.info("[KakaoBotApi] Event message sent. taskId: {}", response.getBody().getTaskId());
            return response.getBody();
        } catch (Exception e) {
            log.error("[KakaoBotApi] Failed to send event message: {}", e.getMessage());
            throw new RuntimeException("Failed to send Kakao event message", e);
        }
    }

    /**
     * 이벤트 발송 결과 조회
     * GET /v1/tasks/{taskId}
     */
    public TaskResultResponse getTaskResult(String taskId) {
        String url = KakaoConfig.BOT_API_BASE_URL + "/v1/tasks/" + taskId;

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<TaskResultResponse> response = kakaoRestTemplate.exchange(
                    url, HttpMethod.GET, entity, TaskResultResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[KakaoBotApi] Failed to get task result: {}", e.getMessage());
            throw new RuntimeException("Failed to get task result", e);
        }
    }

    // ==================== Chatroom Info API ====================

    /**
     * 봇이 참여 중인 채팅방 키 조회
     * GET /v2/bots/{botId}/bot-group-keys
     */
    public BotGroupKeysResponse getBotGroupKeys(int pageNumber, int pageSize) {
        String url = KakaoConfig.BOT_API_BASE_URL + "/v2/bots/" + kakaoConfig.getBotId()
                + "/bot-group-keys?pageNumber=" + pageNumber + "&pageSize=" + pageSize;

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<BotGroupKeysResponse> response = kakaoRestTemplate.exchange(
                    url, HttpMethod.GET, entity, BotGroupKeysResponse.class);
            log.info("[KakaoBotApi] Got {} bot group keys", response.getBody().getTotal());
            return response.getBody();
        } catch (Exception e) {
            log.error("[KakaoBotApi] Failed to get bot group keys: {}", e.getMessage());
            throw new RuntimeException("Failed to get bot group keys", e);
        }
    }

    /**
     * 봇이 참여 중인 채팅방 키 전체 조회 (페이징 자동 처리)
     */
    public List<String> getAllBotGroupKeys() {
        BotGroupKeysResponse response = getBotGroupKeys(0, 100);
        return response.getBotGroupKeys();
    }

    /**
     * 채팅방 리스트 및 구독 상태 조회
     * GET /v2/bots/{botId}/group-chat-rooms
     */
    public GroupChatRoomsResponse getGroupChatRooms(int pageNumber, int pageSize) {
        String url = KakaoConfig.BOT_API_BASE_URL + "/v2/bots/" + kakaoConfig.getBotId()
                + "/group-chat-rooms?pageNumber=" + pageNumber + "&pageSize=" + pageSize;

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<GroupChatRoomsResponse> response = kakaoRestTemplate.exchange(
                    url, HttpMethod.GET, entity, GroupChatRoomsResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[KakaoBotApi] Failed to get group chat rooms: {}", e.getMessage());
            throw new RuntimeException("Failed to get group chat rooms", e);
        }
    }

    /**
     * 특정 채팅방의 참여 유저 목록 조회
     * GET /v2/bots/{botId}/group-chat-rooms/{botGroupKey}/members
     */
    public ChatRoomMembersResponse getChatRoomMembers(String botGroupKey) {
        String url = KakaoConfig.BOT_API_BASE_URL + "/v2/bots/" + kakaoConfig.getBotId()
                + "/group-chat-rooms/" + botGroupKey + "/members";

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<ChatRoomMembersResponse> response = kakaoRestTemplate.exchange(
                    url, HttpMethod.GET, entity, ChatRoomMembersResponse.class);
            log.info("[KakaoBotApi] Got {} members in chat room {}",
                    response.getBody().getMembers().size(), botGroupKey);
            return response.getBody();
        } catch (Exception e) {
            log.error("[KakaoBotApi] Failed to get chat room members: {}", e.getMessage());
            throw new RuntimeException("Failed to get chat room members", e);
        }
    }

    // ==================== Helper Methods ====================

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "KakaoAK " + kakaoConfig.getRestApiKey());
        return headers;
    }

    // ==================== Request/Response DTOs ====================

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventRequest {
        private List<Map<String, String>> chat;  // [{"id": "botGroupKey1"}, {"id": "botGroupKey2"}]
        private Map<String, String> event;        // {"name": "eventName"}
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventResponse {
        private String taskId;
        private String status;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskResultResponse {
        private String taskId;
        private String status;  // ALL SUCCESS, PARTIAL SUCCESS, ALL FAIL
        private Integer successCount;
        private Integer failCount;
        private List<FailDetail> fail;

        @Getter
        @NoArgsConstructor
        @AllArgsConstructor
        public static class FailDetail {
            private String botGroupKey;
            private String errorCode;
            private String errorMessage;
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BotGroupKeysResponse {
        private Integer total;
        private List<String> botGroupKeys;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupChatRoomsResponse {
        private Integer total;
        private List<GroupChatRoom> chatRooms;

        @Getter
        @NoArgsConstructor
        @AllArgsConstructor
        public static class GroupChatRoom {
            private String botGroupKey;
            private Boolean isSubscribed;  // 이벤트 메시지 수신 여부
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRoomMembersResponse {
        private List<ChatRoomMember> members;

        @Getter
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ChatRoomMember {
            private String botUserKey;
            private String nickname;
            private String profileImageUrl;
        }
    }
}
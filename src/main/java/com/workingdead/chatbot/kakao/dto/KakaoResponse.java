package com.workingdead.chatbot.kakao.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 카카오 i 오픈빌더 스킬 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KakaoResponse {

    private String version;
    private Template template;
    private Map<String, Object> context;
    private Map<String, Object> data;
    private Extra extra;  // 멘션 등 추가 기능

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Extra {
        private Map<String, String> mentions;  // key: 텍스트에서 사용할 키, value: botUserKey
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Template {
        private List<Output> outputs;
        private List<QuickReply> quickReplies;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Output {
        private SimpleText simpleText;
        private SimpleImage simpleImage;
        private BasicCard basicCard;
        private ListCard listCard;
        private Carousel carousel;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SimpleText {
        private String text;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SimpleImage {
        private String imageUrl;
        private String altText;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BasicCard {
        private String title;
        private String description;
        private Thumbnail thumbnail;
        private List<Button> buttons;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListCard {
        private ListCardHeader header;
        private List<ListCardItem> items;
        private List<Button> buttons;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListCardHeader {
        private String title;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListCardItem {
        private String title;
        private String description;
        private String imageUrl;
        private Link link;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Carousel {
        private String type;
        private List<BasicCard> items;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thumbnail {
        private String imageUrl;
        private Link link;
        private Boolean fixedRatio;
        private Integer width;
        private Integer height;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Button {
        private String label;
        private String action;
        private String webLinkUrl;
        private String messageText;
        private String phoneNumber;
        private String blockId;
        private Map<String, Object> extra;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuickReply {
        private String label;
        private String action;
        private String messageText;
        private String blockId;
        private Map<String, Object> extra;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Link {
        private String web;
        private String mobile;
    }

    // ========== Builder Helper Methods ==========

    /**
     * 단순 텍스트 응답 생성
     */
    public static KakaoResponse simpleText(String text) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .simpleText(SimpleText.builder().text(text).build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * 텍스트 + 퀵리플라이 응답 생성
     */
    public static KakaoResponse textWithQuickReplies(String text, List<QuickReply> quickReplies) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .simpleText(SimpleText.builder().text(text).build())
                                        .build()
                        ))
                        .quickReplies(quickReplies)
                        .build())
                .build();
    }

    /**
     * 기본 카드 응답 생성
     */
    public static KakaoResponse basicCard(String title, String description, List<Button> buttons) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .basicCard(BasicCard.builder()
                                                .title(title)
                                                .description(description)
                                                .buttons(buttons)
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * 퀵리플라이 버튼 생성 헬퍼
     */
    public static QuickReply quickReply(String label, String messageText) {
        return QuickReply.builder()
                .label(label)
                .action("message")
                .messageText(messageText)
                .build();
    }

    /**
     * 블록 이동 퀵리플라이 생성 헬퍼
     */
    public static QuickReply quickReplyBlock(String label, String blockId, Map<String, Object> extra) {
        return QuickReply.builder()
                .label(label)
                .action("block")
                .blockId(blockId)
                .extra(extra)
                .build();
    }

    /**
     * 웹링크 버튼 생성 헬퍼
     */
    public static Button webLinkButton(String label, String url) {
        return Button.builder()
                .label(label)
                .action("webLink")
                .webLinkUrl(url)
                .build();
    }

    /**
     * 메시지 버튼 생성 헬퍼
     */
    public static Button messageButton(String label, String messageText) {
        return Button.builder()
                .label(label)
                .action("message")
                .messageText(messageText)
                .build();
    }

    /**
     * 텍스트 + 퀵리플라이 + 멘션(extra.mentions) 응답 생성
     * text에 #{mentions.key} 형식으로 멘션 삽입 가능
     */
    public static KakaoResponse textWithQuickRepliesAndMentions(
            String text,
            Map<String, String> mentions,
            List<QuickReply> quickReplies
    ) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .simpleText(SimpleText.builder().text(text).build())
                                        .build()
                        ))
                        .quickReplies(quickReplies)
                        .build())
                .extra((mentions == null || mentions.isEmpty()) ? null : Extra.builder().mentions(mentions).build())
                .build();
    }

    /**
     * 멘션 텍스트 생성 헬퍼
     * 예: buildMentionText("user1") -> "#{mentions.user1}"
     */
    public static String buildMentionText(String mentionKey) {
        return "#{mentions." + mentionKey + "}";
    }

    /**
     * TextCard 응답 생성
     */
    public static KakaoResponse textCard(String title, String description, List<Button> buttons) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .basicCard(BasicCard.builder()
                                                .title(title)
                                                .description(description)
                                                .buttons(buttons)
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * ListCard 응답 생성
     */
    public static KakaoResponse listCard(String headerTitle, List<ListCardItem> items, List<Button> buttons) {
        return KakaoResponse.builder()
                .version("2.0")
                .template(Template.builder()
                        .outputs(List.of(
                                Output.builder()
                                        .listCard(ListCard.builder()
                                                .header(ListCardHeader.builder().title(headerTitle).build())
                                                .items(items)
                                                .buttons(buttons)
                                                .build())
                                        .build()
                        ))
                        .build())
                .build();
    }

    /**
     * ListCardItem 생성 헬퍼
     */
    public static ListCardItem listItem(String title, String description) {
        return ListCardItem.builder()
                .title(title)
                .description(description)
                .build();
    }
}
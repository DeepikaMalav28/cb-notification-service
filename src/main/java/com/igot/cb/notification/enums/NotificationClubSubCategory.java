package com.igot.cb.notification.enums;

import lombok.AllArgsConstructor;

import java.time.Duration;
//
//import lombok.AllArgsConstructor;
//
//import java.time.Duration;
//
//import static com.igot.cb.util.Constants.DEFAULT_DISCUSSION_WINDOW;
//import static com.igot.cb.util.Constants.DEFAULT_NETWORK_WINDOW;
//
//public enum NotificationClubSubCategory {
//    LIKED_POST(NotificationCategory.DISCUSSION, "{count} users liked your post.", DEFAULT_DISCUSSION_WINDOW),
//    LIKED_COMMENT(NotificationCategory.DISCUSSION, "{count} users commented on your post.", DEFAULT_DISCUSSION_WINDOW),
//    REPLIED_COMMENT(NotificationCategory.DISCUSSION, "You have {count} replies on your comment.",DEFAULT_DISCUSSION_WINDOW),
//    SEND_CONNECTION_REQUEST(NotificationCategory.NETWORK, "You received {count} new connection requests.", DEFAULT_NETWORK_WINDOW),
//    ACCEPTED_CONNECTION_REQUEST(NotificationCategory.NETWORK, "{count} users accepted your connection request.", DEFAULT_NETWORK_WINDOW);
//
//
//    private final NotificationCategory category;
//    private final String messageTemplate;
//    private final Duration clubbingWindow;

//
//
//    NotificationClubSubCategory(NotificationCategory category, String messageTemplate, Duration clubbingWindow, Boolean clubbable) {
//        this.category = category;
//        this.messageTemplate = messageTemplate;
//        this.clubbingWindow = clubbingWindow;
//
//    }
//

//
//
//    public NotificationCategory getCategory() {
//        return category;
//    }
//
//    public String getMessageTemplate() {
//        return messageTemplate;
//    }
//
//    public Duration getClubbingWindow() {
//        return clubbingWindow;
//    }
//


////    public boolean isClubbable() {
////        return !clubbingWindow.isZero();
////    }
//}

//


@AllArgsConstructor
public enum NotificationClubSubCategory {
    LIKED_POST(NotificationCategory.DISCUSSION, "{count} users liked your post.", Duration.ofMinutes(15), true),
    LIKED_COMMENT(NotificationCategory.DISCUSSION, "{count} users commented on your post.", Duration.ofMinutes(15), true),
    REPLIED_COMMENT(NotificationCategory.DISCUSSION, "You have {count} replies on your comment.", Duration.ofMinutes(15), true),
    SEND_CONNECTION_REQUEST(NotificationCategory.NETWORK, "You received {count} new connection requests.", Duration.ofMinutes(60), true),
    ACCEPTED_CONNECTION_REQUEST(NotificationCategory.NETWORK, "{count} users accepted your connection request.", Duration.ofMinutes(60), true),
    // other types without clubbing
    PROFILE_VERIFICATION(NotificationCategory.PROFILE, "A new profile verification request has been submitted for your review", null, false),
    USER_TRANSFER(NotificationCategory.PROFILE, "You have received a new user transfer request.", null, false),
    CONTENT_REVIEW_REQUEST(NotificationCategory.CONTENT, "New content {title} has been submitted for your review.", null, false),
    CONTENT_PUBLISHED(NotificationCategory.CONTENT, "Your content {title} has been published successfully.", null, false),
    CONTENT_REJECTED(NotificationCategory.CONTENT, "Your content {title} was not approved. Please check reviewer comments.", null, false),
    CONTENT_EDITED(NotificationCategory.CONTENT, "Your content {title} was edited by the publisher. Review the changes.", null, false),
    CONTENT_SHARE(NotificationCategory.NETWORK, "{userName} shared the content {title} with you.", null, false),
    TAGGED_COMMENT(NotificationCategory.NETWORK, "{userName} mentioned you in their comment/reply.", null, false),
    TAGGED_POST(NotificationCategory.NETWORK, "{userName} mentioned you in their post (For post tagging).", null, false),
    REJECTED_CONNECTION_REQUEST(NotificationCategory.NETWORK, "{userName} rejected your connection request.", null, false),
    REPLIED_POST(NotificationCategory.DISCUSSION, "{userName} liked your reply.", null, false),
    POST_COMMENT(NotificationCategory.DISCUSSION, "{userName} liked on your commented post.", null, false);



    private final NotificationCategory category;
    private final String messageTemplate;
    private final Duration clubbingWindow;
    private final boolean clubbable;

    public boolean isClubbable() {
        return clubbable;
    }

    public Duration getClubbingWindow() {
        return clubbingWindow;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}

//

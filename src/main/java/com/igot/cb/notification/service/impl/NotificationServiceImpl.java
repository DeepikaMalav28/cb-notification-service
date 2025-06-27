package com.igot.cb.notification.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.notification.enums.NotificationReadStatus;
import com.igot.cb.notification.enums.NotificationClubSubCategory;
import com.igot.cb.notification.enums.NotificationSubType;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.userNotificationSetting.entity.NotificationSettingEntity;
import com.igot.cb.userNotificationSetting.repository.NotificationSettingRepository;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.Constants;
import com.igot.cb.util.ProjectUtil;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.igot.cb.util.Constants.*;
import static org.keycloak.common.Version.UNKNOWN;


@Service
@Slf4j
public class NotificationServiceImpl implements NotificationService {



    @Autowired
    AccessTokenValidator accessTokenValidator;

    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationSettingRepository notificationSettingRepository;

    private final Logger logger = LoggerFactory.getLogger(NotificationServiceImpl.class);

    @Override
    public ApiResponse createNotification(JsonNode userNotificationDetail, String authToken) {
        log.info("NotificationService::createNotification: inside the method");
        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_CREATE);

        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);
            if (StringUtils.isEmpty(userId)) {
                updateErrorDetails(outgoingResponse, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            JsonNode requestNode = userNotificationDetail.get(Constants.REQUEST);
            if (ObjectUtils.isEmpty(requestNode) || !requestNode.isObject()) {
                log.warn("Missing or invalid 'request' node: {}", userNotificationDetail.toString());
                updateErrorDetails(outgoingResponse, "Missing or invalid 'request' node in payload", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            String notificationType = requestNode.path(TYPE).asText(null);
            if (StringUtils.isBlank(notificationType)) {
                updateErrorDetails(outgoingResponse, "Notification type is required", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            Optional<NotificationSettingEntity> settingOpt =
                    notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType);

            if (settingOpt.isPresent() && !settingOpt.get().isEnabled()) {
                log.info("User '{}' has disabled notification type '{}'. Skipping notification creation.", userId, notificationType);
                outgoingResponse.setResponseCode(HttpStatus.OK);
                outgoingResponse.getParams().setErrMsg("Notification not created as it is disabled by the user.");
                outgoingResponse.getParams().setStatus(Constants.SUCCESS);
                return outgoingResponse;
            }

            ZoneId zoneId = ZoneId.of(UTC);
            Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();

            Map<String, Object> dbMap = new HashMap<>();
            dbMap.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
            dbMap.put(Constants.USER_ID, userId);
            dbMap.put(Constants.CREATED_AT, instant);
            dbMap.put(Constants.UPDATED_AT, instant);
            dbMap.put(Constants.IS_DELETED, false);
            dbMap.put(Constants.READ, false);
            dbMap.put(Constants.READ_AT, null);

            if (ObjectUtils.isNotEmpty(requestNode) && requestNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode valueNode = entry.getValue();
                    if (valueNode.isValueNode()) {
                        dbMap.put(entry.getKey(), valueNode.asText());
                    } else {
                        dbMap.put(entry.getKey(), valueNode.toString());
                    }
                }
            } else {
                log.warn("Missing or invalid 'request' node: {}", userNotificationDetail.toString());
                outgoingResponse.getParams().setErrMsg("Missing or invalid 'request' node in payload");
                outgoingResponse.getParams().setStatus(Constants.FAILED);
                outgoingResponse.setResponseCode(HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            Object res = cassandraOperation.insertRecord(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    dbMap
            );
            log.info("Inserted notification: {}", res.toString());


            incrementUnreadCountManually(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_UNREAD_NOTIFICATION_COUNT, userId);

            Map<String, Object> responseMap = new HashMap<>(dbMap);
            Map<String, Object> resultMap = prepareNotificationResponse(responseMap);

            outgoingResponse.setResponseCode(HttpStatus.OK);
            outgoingResponse.setResult(resultMap);
            log.info("NotificationService::createNotification saved successfully");

        } catch (Exception e) {
            log.error("Error while saving notification to Cassandra: {}", e.getMessage(), e);
            updateErrorDetails(outgoingResponse, "Internal server error while saving notification data",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return outgoingResponse;
    }

//    @Override
//    public ApiResponse bulkCreateNotifications(JsonNode userNotificationDetail) {
//        log.info("NotificationService::bulkCreateNotification: Bulk notification creation started");
//        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_BULK_CREATE);
//
//        try {
//            JsonNode requestNode = userNotificationDetail.get(Constants.REQUEST);
//            if (ObjectUtils.isEmpty(requestNode) || !requestNode.isObject()) {
//                log.warn("Missing or invalid 'request' node: {}", userNotificationDetail.toString());
//                updateErrorDetails(outgoingResponse, "Missing or invalid 'request' node in payload", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            JsonNode userIdsNode = requestNode.get(USER_IDS);
//            if (ObjectUtils.isEmpty(userIdsNode) || !userIdsNode.isArray()) {
//                log.warn("Missing or invalid 'user_ids' in request");
//                updateErrorDetails(outgoingResponse, "'user_ids' must be a non-empty list", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//
//            String notificationType = requestNode.path(TYPE).asText(null);
//            if (StringUtils.isBlank(notificationType)) {
//                log.warn("Missing 'notification_type' in payload");
//                updateErrorDetails(outgoingResponse, "'notification_type' is required", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            if (userIdsNode.size() > MAX_USER_LIMIT) {
//                log.warn("Too many user_ids in request: {}", userIdsNode.size());
//                updateErrorDetails(outgoingResponse, "Cannot send notifications to more than 100 users in a single request", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            List<Map<String, Object>> notificationRecords = new ArrayList<>();
//            ZoneId zoneId = ZoneId.of(UTC);
//            Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();
//
//            List<String> userIdsForCountUpdate = new ArrayList<>();
//
//            for (JsonNode userIdNode : userIdsNode) {
//                JsonNode idNode = userIdNode.get("user_id");
//                String userId = (idNode != null) ? idNode.asText() : null;
//
//                if (StringUtils.isEmpty(userId)) {
//                    log.warn("Empty user_id encountered in request");
//                    continue;
//                }
//
//                Optional<NotificationSettingEntity> settingOpt =
//                        notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType);
//
//                if (settingOpt.isPresent() && !settingOpt.get().isEnabled()) {
//                    log.info("NotificationType '{}' is disabled for user '{}', skipping notification", notificationType, userId);
//                    continue;
//                }
//
//                userIdsForCountUpdate.add(userId);
//
//                Map<String, Object> dbMap = new HashMap<>();
//                dbMap.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
//                dbMap.put(Constants.USER_ID, userId);
//                dbMap.put(Constants.CREATED_AT, instant);
//                dbMap.put(Constants.UPDATED_AT, instant);
//                dbMap.put(Constants.IS_DELETED, false);
//                dbMap.put(Constants.READ, false);
//                dbMap.put(Constants.READ_AT, null);
//
//                Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
//                while (fields.hasNext()) {
//                    Map.Entry<String, JsonNode> entry = fields.next();
//                    String key = entry.getKey();
//                    JsonNode valueNode = entry.getValue();
//
//                    if (!USER_IDS.equals(key)) {
//                        dbMap.put(key, valueNode.isValueNode() ? valueNode.asText() : valueNode.toString());
//                    }
//                }
//
//                notificationRecords.add(dbMap);
//            }
//
//            Object insertResponse = cassandraOperation.insertBulkRecord(
//                    Constants.KEYSPACE_SUNBIRD,
//                    Constants.TABLE_USER_NOTIFICATION,
//                    notificationRecords
//            );
//
//            if (insertResponse instanceof ApiResponse apiResponse &&
//                    Constants.FAILED.equals(apiResponse.get(Constants.RESPONSE))) {
//                log.error("Bulk notification insertion failed: {}", apiResponse.getParams().getErrMsg());
//                updateErrorDetails(outgoingResponse, "Failed to insert notifications", HttpStatus.INTERNAL_SERVER_ERROR);
//                return outgoingResponse;
//            }
//
//
//            for (String userId : userIdsForCountUpdate) {
//                incrementUnreadCountManually(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_UNREAD_NOTIFICATION_COUNT, userId);
//            }
//
//            List<Map<String, Object>> responseList = notificationRecords.stream()
//                    .map(this::prepareNotificationResponse)
//                    .toList();
//
//            outgoingResponse.setResponseCode(HttpStatus.OK);
//            outgoingResponse.setResult(Map.of("notifications", responseList));
//            log.info("NotificationService::bulkCreateNotification: Successfully inserted {} notifications", responseList.size());
//
//        } catch (Exception e) {
//            log.error("Error during bulk notification creation: {}", e.getMessage(), e);
//            updateErrorDetails(outgoingResponse, "Internal server error while saving notifications", HttpStatus.INTERNAL_SERVER_ERROR);
//        }
//
//        return outgoingResponse;
//    }


    //TODO clubbed logic1

    @Override
    public ApiResponse bulkCreateNotifications(JsonNode userNotificationDetail) {
        log.info("NotificationService::bulkCreateNotification: Bulk notification creation started");
        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_BULK_CREATE);

        try {
            JsonNode requestNode = userNotificationDetail.get(Constants.REQUEST);
            if (ObjectUtils.isEmpty(requestNode) || !requestNode.isObject()) {
                log.warn("Missing or invalid 'request' node: {}", userNotificationDetail.toString());
                updateErrorDetails(outgoingResponse, "Missing or invalid 'request' node in payload", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            JsonNode userIdsNode = requestNode.get(USER_IDS);
            if (ObjectUtils.isEmpty(userIdsNode) || !userIdsNode.isArray()) {
                log.warn("Missing or invalid 'user_ids' in request");
                updateErrorDetails(outgoingResponse, "'user_ids' must be a non-empty list", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }


            String notificationType = requestNode.path(TYPE).asText(null);
            if (StringUtils.isBlank(notificationType)) {
                log.warn("Missing 'notification_type' in payload");
                updateErrorDetails(outgoingResponse, "'notification_type' is required", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            String notificationSubCategory = requestNode.path(SUB_CATEGORY).asText(null);
            Optional<NotificationClubSubCategory> subCategoryOpt = getClubSubCategory(notificationSubCategory);
            boolean isClubbable = subCategoryOpt.map(NotificationClubSubCategory::isClubbable).orElse(false);


            if (userIdsNode.size() > MAX_USER_LIMIT) {
                log.warn("Too many user_ids in request: {}", userIdsNode.size());
                updateErrorDetails(outgoingResponse, "Cannot send notifications to more than 100 users in a single request", HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            List<Map<String, Object>> notificationRecords = new ArrayList<>();
            ZoneId zoneId = ZoneId.of(UTC);
            Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();

            List<String> userIdsForCountUpdate = new ArrayList<>();

            for (JsonNode userIdNode : userIdsNode) {
                JsonNode idNode = userIdNode.get("user_id");
                String userId = (idNode != null) ? idNode.asText() : null;

                if (StringUtils.isEmpty(userId)) {
                    log.warn("Empty user_id encountered in request");
                    continue;
                }

                Optional<NotificationSettingEntity> settingOpt =
                        notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType);

                if (settingOpt.isPresent() && !settingOpt.get().isEnabled()) {
                    log.info("NotificationType '{}' is disabled for user '{}', skipping notification", notificationType, userId);
                    continue;
                }

                userIdsForCountUpdate.add(userId);

                if (isClubbable && subCategoryOpt.isPresent()) {
                    NotificationClubSubCategory subCategory = subCategoryOpt.get();
                    System.out.println(requestNode+"requestNOde");

                    // Always insert into individual table
                    insertIndividualNotification(userId, requestNode, notificationType, subCategory, instant);

                    // Handle main clubbing logic
                    handleClubbedNotification(userId, requestNode, notificationType, subCategory, instant, notificationRecords);

                    continue; // skip default notification insertion below
                }


                Map<String, Object> dbMap = new HashMap<>();
                dbMap.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
                dbMap.put(Constants.USER_ID, userId);
                dbMap.put(Constants.CREATED_AT, instant);
                dbMap.put(Constants.UPDATED_AT, instant);
                dbMap.put(Constants.IS_DELETED, false);
                dbMap.put(Constants.READ, false);
                dbMap.put(Constants.READ_AT, null);

                Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey();
                    JsonNode valueNode = entry.getValue();

                    if (!USER_IDS.equals(key)) {
                        dbMap.put(key, valueNode.isValueNode() ? valueNode.asText() : valueNode.toString());
                    }
                }

                notificationRecords.add(dbMap);
            }

            Object insertResponse = cassandraOperation.insertBulkRecord(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    notificationRecords
            );

            if (insertResponse instanceof ApiResponse apiResponse &&
                    Constants.FAILED.equals(apiResponse.get(Constants.RESPONSE))) {
                log.error("Bulk notification insertion failed: {}", apiResponse.getParams().getErrMsg());
                updateErrorDetails(outgoingResponse, "Failed to insert notifications", HttpStatus.INTERNAL_SERVER_ERROR);
                return outgoingResponse;
            }


            for (String userId : userIdsForCountUpdate) {
                incrementUnreadCountManually(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_UNREAD_NOTIFICATION_COUNT, userId);
            }

            List<Map<String, Object>> responseList = notificationRecords.stream()
                    .map(this::prepareNotificationResponse)
                    .toList();

            outgoingResponse.setResponseCode(HttpStatus.OK);
            outgoingResponse.setResult(Map.of("notifications", responseList));
            log.info("NotificationService::bulkCreateNotification: Successfully inserted {} notifications", responseList.size());

        } catch (Exception e) {
            log.error("Error during bulk notification creation: {}", e.getMessage(), e);
            updateErrorDetails(outgoingResponse, "Internal server error while saving notifications", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return outgoingResponse;
    }


    private Optional<NotificationClubSubCategory> getClubSubCategory(String notificationSubCategory) {
        try {
            return Optional.of(NotificationClubSubCategory.valueOf(notificationSubCategory));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // Not a clubbable type
        }
    }

    private void insertIndividualNotification(String userId, JsonNode requestNode, String notificationType,
                                              NotificationClubSubCategory subCategory, Instant instant) {

        Map<String, Object> individualMap = new HashMap<>();
        individualMap.put("user_id", userId);
        individualMap.put("notification_id", java.util.UUID.randomUUID().toString());
        individualMap.put("created_at", instant);
        individualMap.put("updated_at", instant);
        individualMap.put("read", false);
        individualMap.put("read_at", null);
        individualMap.put("is_deleted", false);

        // Add mandatory known fields:
        individualMap.put("type", notificationType);
        individualMap.put("category", subCategory.getCategory().name());
        individualMap.put("sub_category", notificationType);

        // Now copy all other fields from requestNode except 'user_ids' dynamically:
        Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();

            if (!USER_IDS.equals(key)) {
                JsonNode valueNode = entry.getValue();
                // Put raw value (string or json string)
                individualMap.put(key, valueNode.isValueNode() ? valueNode.asText() : valueNode.toString());
            }
        }

        // Insert into individual_notifications table
        cassandraOperation.insertRecord(
                Constants.KEYSPACE_SUNBIRD,
                Constants.TABLE_INDIVIDUAL_NOTIFICATION,
                individualMap
        );
    }


        private static final Map<String, ClubbingConfig> CLUBBING_CONFIG_MAP = Arrays.stream(NotificationClubSubCategory.values())
            .filter(NotificationClubSubCategory::isClubbable)
            .collect(Collectors.toMap(
                    Enum::name,
                    subCategory -> new ClubbingConfig(
                            subCategory.getClubbingWindow(),
                            notif -> {
                                try {
                                    Map<String, Object> msg = safeCastMap(notif.get(MESSAGE));
                                    Map<String, Object> data = safeCastMap(msg.get(DATA));
                                    String userId = String.valueOf(notif.getOrDefault(USER_ID, "unknown"));

                                    // Customize grouping key logic per subcategory if needed
                                    if (data.containsKey(DISCUSSION_ID)) {
                                        return data.get(DISCUSSION_ID) + "::" + userId;
                                    } else if (data.containsKey(ID)) {
                                        return data.get(ID) + "::" + userId;
                                    } else {
                                        return userId;
                                    }

                                } catch (Exception e) {
                                    return UNKNOWN;
                                }
                            },
                            subCategory.getMessageTemplate()
                    )
            ));

        @SuppressWarnings("unchecked")
    public static Map<String, Object> safeCastMap(Object obj) {
        if (obj instanceof Map<?, ?>) {
            return (Map<String, Object>) obj;
        }

        if (obj instanceof String str) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(str, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.error("Failed to parse JSON string to Map: {}", str, e);
            }
        }

        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private void handleClubbedNotification(
            String userId,
            JsonNode requestNode,
            String notificationType,
            NotificationClubSubCategory subCategory,
            Instant instant,
            List<Map<String, Object>> notificationRecords
    ) {
        ClubbingConfig config = CLUBBING_CONFIG_MAP.get(subCategory.name());
        if (config == null) return;

        // Step 1: Calculate window start time
        Instant windowStart = instant.minus(config.getWindow());

        // Step 2: Simulate group key input
        Map<String, Object> simulated = Map.of(
                Constants.USER_ID, userId,
                Constants.CREATED_AT, instant,
                Constants.SUB_CATEGORY, subCategory.name(),
                MESSAGE, Map.of("data", extractMessageData(requestNode))
        );
        String groupKey = config.getGroupKey().apply(simulated);

        // Step 3: Query for recent notifications by user
        Map<String, Object> query = Map.of(Constants.USER_ID, userId);
        List<Map<String, Object>> recent = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD,
                Constants.TABLE_USER_NOTIFICATION,
                query,
                null,
                100
        );

        // Step 4: Find matching existing notification
        Map<String, Object> existing = recent.stream()
                .filter(n -> {
                    Instant createdAt = (Instant) n.get(Constants.CREATED_AT);
                    return createdAt != null && !createdAt.isBefore(windowStart) && !createdAt.isAfter(instant)
                            && groupKey.equals(config.getGroupKey().apply(n));
                })
                .findFirst()
                .orElse(null);

        if (existing != null) {
            // Step 5: Update existing notification
            Map<String, Object> existingMessage = safeCastMap(existing.get(MESSAGE));
            if (existingMessage == null) existingMessage = new HashMap<>();

            String currentBody = (String) existingMessage.get("body");
            int currentCount = extractCurrentCountFromMessageBody(currentBody);
            int updatedCount = currentCount + 1;
            String updatedBody = currentBody.replace(String.valueOf(currentCount), String.valueOf(updatedCount));

            Map<String, Object> data = safeCastMap(existingMessage.get("data"));

            // Safely collect updated message
            Map<String, Object> updatedMessage = new HashMap<>();
            updatedMessage.put("body", updatedBody);
            updatedMessage.put("data", data);

            // Prepare update map (only non-primary key fields)
            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put(Constants.UPDATED_AT, instant);

            String notificationId = existing.get("notification_id").toString();

            // Convert message to JSON
            ObjectMapper mapper = new ObjectMapper();
            String messageJson = null;
            try {
                messageJson = mapper.writeValueAsString(updatedMessage);
            } catch (JsonProcessingException e) {
                log.info("error", e);
            }

            updateMap.put(Constants.MESSAGE, messageJson);

            // Use helper method to update by composite key (userId + createdAt)
            updateNotification(userId, notificationId, updateMap);

            // **Add updated notification to the list for response**
            existing.put(Constants.MESSAGE, messageJson);  // Ensure the updated message is reflected
            notificationRecords.add(existing);  // Add to the response list

        } else {
            // Step 6: Insert new notification
            Map<String, Object> dbMap = new HashMap<>();
            dbMap.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
            dbMap.put(Constants.USER_ID, userId);
            dbMap.put(Constants.CREATED_AT, instant);
            dbMap.put(Constants.UPDATED_AT, instant);
            dbMap.put(Constants.IS_DELETED, false);
            dbMap.put(Constants.READ, false);
            dbMap.put(Constants.READ_AT, null);
            dbMap.put(Constants.TYPE, notificationType);
            dbMap.put(Constants.SUB_CATEGORY, subCategory.name());
            dbMap.put(Constants.CATEGORY, subCategory.getCategory().name());

            Map<String, Object> message = new HashMap<>();
            message.put("body", config.getMessageTemplate().replace("{count}", "1"));
            message.put("data", extractMessageData(requestNode));

            ObjectMapper mapper = new ObjectMapper();
            String messageJson = null;
            try {
                messageJson = mapper.writeValueAsString(message);
            } catch (JsonProcessingException e) {
                log.info("error", e);
            }

            dbMap.put(Constants.MESSAGE, messageJson);

            // **Add new notification to the list for response**
            notificationRecords.add(dbMap);
        }
    }


    // Helper method to extract the current count from the message body
    private int extractCurrentCountFromMessageBody(String messageBody) {
        // Assuming the message body is in the format of "X users liked your post."
        String[] parts = messageBody.split(" ");
        try {
            return Integer.parseInt(parts[0]); // The first part should be the count
        } catch (NumberFormatException e) {
            return 0; // Default to 0 if there's an issue parsing
        }
    }


    private Map<String, Object> extractMessageData(JsonNode requestNode) {
        Map<String, Object> data = new HashMap<>();

        if (requestNode.has("message")) {
            JsonNode messageNode = requestNode.get("message");
            if (messageNode.has("data")) {
                JsonNode dataNode = messageNode.get("data");

                // Extract known expected fields
                if (dataNode.has("communityId")) {
                    data.put("communityId", dataNode.get("communityId").asText());
                }
                if (dataNode.has("discussionId")) {
                    data.put("discussionId", dataNode.get("discussionId").asText());
                }
                if (dataNode.has("id")) {
                    data.put("id", dataNode.get("id").asText());
                }
            }
        }

        System.out.println(data + " <-- Correctly extracted message.data");
        return data;
    }


    private String toJson(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to convert map to JSON string", e);
            return "{}"; // Return empty JSON if conversion fails.
        }
    }












//    @Override
//    public ApiResponse bulkCreateNotifications(JsonNode userNotificationDetail) {
//        log.info("NotificationService::bulkCreateNotification: Bulk notification creation started");
//        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_BULK_CREATE);
//
//        try {
//            JsonNode requestNode = userNotificationDetail.get(Constants.REQUEST);
//            if (ObjectUtils.isEmpty(requestNode) || !requestNode.isObject()) {
//                updateErrorDetails(outgoingResponse, "Missing or invalid 'request' node", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            JsonNode userIdsNode = requestNode.get(USER_IDS);
//            if (ObjectUtils.isEmpty(userIdsNode) || !userIdsNode.isArray()) {
//                updateErrorDetails(outgoingResponse, "'user_ids' must be a non-empty list", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            String notificationType = requestNode.path(TYPE).asText(null);
//            if (StringUtils.isBlank(notificationType)) {
//                updateErrorDetails(outgoingResponse, "'notification_type' is required", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            String subCategoryKey = requestNode.path("sub_category").asText(null);
//            NotificationClubSubCategory subCategoryEnum = null;
//            if (StringUtils.isNotBlank(subCategoryKey)) {
//                try {
//                    subCategoryEnum = NotificationClubSubCategory.valueOf(subCategoryKey);
//                } catch (IllegalArgumentException ex) {
//                    log.warn("Invalid sub_category provided: {}", subCategoryKey);
//                }
//            }
//
//            boolean isClubbable = subCategoryEnum != null && subCategoryEnum.isClubbable();
//            List<Map<String, Object>> clubNotificationInsertList = new ArrayList<>();
//            List<Map<String, Object>> individualNotificationInsertList = new ArrayList<>();
//            Instant now = Instant.now();
//
//            for (JsonNode userIdNode : userIdsNode) {
//                String userId = Optional.ofNullable(userIdNode.get("user_id"))
//                        .map(JsonNode::asText).orElse(null);
//                if (StringUtils.isBlank(userId)) continue;
//
//                Optional<NotificationSettingEntity> settingOpt =
//                        notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType);
//
//                if (settingOpt.isPresent() && !settingOpt.get().isEnabled()) continue;
//
//                if (isClubbable) {
//                    Map<String, Object> query = Map.of(USER_ID, userId);
//                    List<Map<String, Object>> recentRecords = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
//                            Constants.KEYSPACE_SUNBIRD,
//                            Constants.TABLE_USER_NOTIFICATION,
//                            query,
//                            List.of(NOTIFICATION_ID,USER_ID, CREATED_AT, TYPE, MESSAGE, READ, ROLE, SOURCE, CATEGORY, SUB_CATEGORY, SUB_TYPE, IS_DELETED, UPDATED_AT),
//                            MAX_NOTIFICATIONS_FETCH_FOR_READ);
//
//                    Instant clubWindowStart = now.minus(subCategoryEnum.getClubbingWindow());
//
//                    Optional<Map<String, Object>> openRecordOpt = recentRecords.stream()
//                            .filter(rec -> subCategoryKey.equals(rec.get("sub_category")))
//                            .filter(rec -> {
//                                Object updatedAt = rec.get(Constants.UPDATED_AT);
//                                if (updatedAt instanceof Instant) {
//                                    return ((Instant) updatedAt).isAfter(clubWindowStart);
//                                } else if (updatedAt instanceof Date) {
//                                    return ((Date) updatedAt).toInstant().isAfter(clubWindowStart);
//                                }
//                                return false;
//                            })
//                            .findFirst();
//
//                    if (openRecordOpt.isPresent()) {
//                        Map<String, Object> existing = openRecordOpt.get();
//                        String existingMsg = (String) existing.get("message");
//                        int currentCount = 1;
//                        try {
//                            JsonNode msgNode = new ObjectMapper().readTree(existingMsg);
//                            String body = msgNode.path("notification").path("body").asText();
//                            Matcher matcher = Pattern.compile("(\\d+)").matcher(body);
//                            if (matcher.find()) {
//                                currentCount = Integer.parseInt(matcher.group(1));
//                            }
//                        } catch (Exception e) {
//                            log.warn("Unable to extract count from existing message: {}", existingMsg);
//                        }
//                        int newCount = currentCount + 1;
//                        String updatedMessageBody = subCategoryEnum.getMessageTemplate().replace("{count}", String.valueOf(newCount));
//
//                        Map<String, Object> bodyMap = new HashMap<>();
//                        bodyMap.put("body", updatedMessageBody);
//                        Map<String, Object> newMessageMap = new HashMap<>();
//                        newMessageMap.put("notification", bodyMap);
//
//                        existing.put("message", new ObjectMapper().writeValueAsString(newMessageMap));
//                        existing.put("updated_at", now);
//                        existing.remove("created_at");
//
//                        Object userIdKey = existing.get(Constants.USER_ID);
//                        Object notificationIdKey = existing.get(Constants.NOTIFICATION_ID);
//
//                        if (userIdKey == null || notificationIdKey == null) {
//                            log.warn("Skipping update due to missing composite key: userId={}, notificationId={}", userIdKey, notificationIdKey);
//                        } else {
//                            Map<String, Object> compositeKey = new HashMap<>();
//                            compositeKey.put(Constants.USER_ID, userIdKey);
//                            compositeKey.put(Constants.NOTIFICATION_ID, notificationIdKey);
//
//                            cassandraOperation.updateRecordByCompositeKey(
//                                    Constants.KEYSPACE_SUNBIRD,
//                                    Constants.TABLE_USER_NOTIFICATION,
//                                    existing,
//                                    compositeKey
//                            );
//                        }
//                    } else {
//                        Map<String, Object> clubRecord = new HashMap<>();
//                        clubRecord.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
//                        clubRecord.put(USER_ID, userId);
//                        clubRecord.put("sub_category", subCategoryKey);
//                        clubRecord.put("sub_type", requestNode.path("sub_type").asText(null));
//                        clubRecord.put("type", notificationType);
//                        clubRecord.put("category", requestNode.path("category").asText(null));
//                        clubRecord.put("source", requestNode.path("source").asText(null));
//                        clubRecord.put("role", requestNode.path("role").asText(null));
//                        clubRecord.put("created_at", now);
//                        clubRecord.put("updated_at", now);
//                        clubRecord.put(Constants.READ, false);
//                        clubRecord.put(Constants.IS_DELETED, false);
//                        String messageBody = subCategoryEnum.getMessageTemplate().replace("{count}", "1");
//
//                        Map<String, Object> bodyMap = new HashMap<>();
//                        bodyMap.put("body", messageBody);
//                        Map<String, Object> messageMap = new HashMap<>();
//                        messageMap.put("notification", bodyMap);
//
//                        clubRecord.put("message", new ObjectMapper().writeValueAsString(messageMap));
//                        clubNotificationInsertList.add(clubRecord);
//                    }
//
//                    String individualMessage = Optional.ofNullable(requestNode.path("message").path("body"))
//                            .map(JsonNode::asText)
//                            .orElse("Someone performed an action.");
//
//                    Map<String, Object> individualRecord = new HashMap<>();
//                    individualRecord.put("notification_id", java.util.UUID.randomUUID().toString());
//                    individualRecord.put(USER_ID, userId);
//                    individualRecord.put("created_at", now);
//                    individualRecord.put("message", individualMessage);
//                    individualNotificationInsertList.add(individualRecord);
//
//                } else {
//                    Map<String, Object> dbMap = new HashMap<>();
//                    dbMap.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
//                    dbMap.put(USER_ID, userId);
//                    dbMap.put(Constants.CREATED_AT, now);
//                    dbMap.put(Constants.UPDATED_AT, now);
//                    dbMap.put(Constants.READ, false);
//                    dbMap.put(Constants.READ_AT, null);
//                    dbMap.put(Constants.IS_DELETED, false);
//
//                    Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
//                    while (fields.hasNext()) {
//                        Map.Entry<String, JsonNode> entry = fields.next();
//                        if (!USER_IDS.equals(entry.getKey())) {
//                            dbMap.put(entry.getKey(), entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString());
//                        }
//                    }
//                    clubNotificationInsertList.add(dbMap);
//                }
//            }
//
//            if (!clubNotificationInsertList.isEmpty()) {
//                cassandraOperation.insertBulkRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_USER_NOTIFICATION, clubNotificationInsertList);
//            }
//
//            if (!individualNotificationInsertList.isEmpty()) {
//                cassandraOperation.insertBulkRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_INDIVIDUAL_NOTIFICATION, individualNotificationInsertList);
//            }
//
//            List<Map<String, Object>> responseList = new ArrayList<>();
//            clubNotificationInsertList.forEach(rec -> responseList.add(prepareNotificationResponse(rec)));
//
//            outgoingResponse.setResponseCode(HttpStatus.OK);
//            outgoingResponse.setResult(Map.of("notifications", responseList));
//            return outgoingResponse;
//
//        } catch (Exception e) {
//            log.error("Error in bulkCreateNotifications: {}", e.getMessage(), e);
//            updateErrorDetails(outgoingResponse, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
//            return outgoingResponse;
//        }
//    }






//
//    @Override
//    public ApiResponse bulkCreateNotifications(JsonNode userNotificationDetail) {
//        log.info("NotificationService::bulkCreateNotification: Bulk notification creation started");
//        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_BULK_CREATE);
//
//        try {
//            JsonNode requestNode = userNotificationDetail.get(Constants.REQUEST);
//            if (ObjectUtils.isEmpty(requestNode) || !requestNode.isObject()) {
//                updateErrorDetails(outgoingResponse, "Missing or invalid 'request' node in payload", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            JsonNode userIdsNode = requestNode.get(USER_IDS);
//            if (ObjectUtils.isEmpty(userIdsNode) || !userIdsNode.isArray()) {
//                updateErrorDetails(outgoingResponse, "'user_ids' must be a non-empty list", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            String notificationType = requestNode.path(TYPE).asText(null);
//            String subCategoryStr = requestNode.path(SUB_CATEGORY).asText(null);
//
//            if (StringUtils.isBlank(notificationType) || StringUtils.isBlank(subCategoryStr)) {
//                updateErrorDetails(outgoingResponse, "'notification_type' and 'sub_category' are required", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            NotificationClubSubCategory clubConfig;
//            try {
//                clubConfig = NotificationClubSubCategory.valueOf(subCategoryStr);
//            } catch (IllegalArgumentException e) {
//                updateErrorDetails(outgoingResponse, "Invalid sub_category: " + subCategoryStr, HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            if (userIdsNode.size() > MAX_USER_LIMIT) {
//                updateErrorDetails(outgoingResponse, "Cannot send notifications to more than 100 users in a single request", HttpStatus.BAD_REQUEST);
//                return outgoingResponse;
//            }
//
//            ZoneId zoneId = ZoneId.of(UTC);
//            Instant now = LocalDateTime.now().atZone(zoneId).toInstant();
//            List<Map<String, Object>> finalNotifications = new ArrayList<>();
//            List<Map<String, Object>> individualNotifications = new ArrayList<>();
//            List<String> userIdsForCountUpdate = new ArrayList<>();
//
//            for (JsonNode userIdNode : userIdsNode) {
//                String userId = Optional.ofNullable(userIdNode.get("user_id")).map(JsonNode::asText).orElse(null);
//                if (StringUtils.isEmpty(userId)) continue;
//
//                Optional<NotificationSettingEntity> settingOpt =
//                        notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType);
//
//                if (settingOpt.isPresent() && !settingOpt.get().isEnabled()) continue;
//
//                userIdsForCountUpdate.add(userId);
//
//                // Common message fields
//                Map<String, Object> messageData = new HashMap<>();
//                Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
//                while (fields.hasNext()) {
//                    Map.Entry<String, JsonNode> entry = fields.next();
//                    if (!USER_IDS.equals(entry.getKey())) {
//                        messageData.put(entry.getKey(), entry.getValue().isValueNode()
//                                ? entry.getValue().asText()
//                                : entry.getValue().toString());
//                    }
//                }
//
//                // Store raw event into individual notification table (traceability)
//                Map<String, Object> individual = new HashMap<>(messageData);
//                individual.put(Constants.NOTIFICATION_ID, java.util.UUID.randomUUID().toString());
//                individual.put(Constants.USER_ID, userId);
//                individual.put(Constants.CREATED_AT, now);
//                individual.put(Constants.IS_DELETED, false);
//                individualNotifications.add(individual);
//
//                if (!clubConfig.isClubbable()) {
//                    // Non-clubbable → add to main notification table
//                    Map<String, Object> dbMap = buildBaseNotificationMap(userId, now, messageData);
//                    finalNotifications.add(dbMap);
//                    continue;
//                }
//
//                // Clubbable logic → check existing open window
//                List<Map<String, Object>> existing = cassandraOperation.getRecordsByPropertiesByKey(
//                        Constants.KEYSPACE_SUNBIRD,
//                        Constants.TABLE_USER_NOTIFICATION,
//                        Map.of(USER_ID, userId),
//                        List.of(NOTIFICATION_ID, CREATED_AT, MESSAGE, SUB_CATEGORY),
//                        USER_ID
//                );
//
//                Optional<Map<String, Object>> matching = existing.stream()
//                        .filter(row -> subCategoryStr.equals(row.get(SUB_CATEGORY)))
//                        .filter(row -> {
//                            Instant created = (Instant) row.get(CREATED_AT);
//                            return Duration.between(created, now).compareTo(clubConfig.getClubbingWindow()) <= 0;
//                        })
//                        .findFirst();
//
//                if (matching.isPresent()) {
//                    // Update existing notification (logic depends on how update works in your Cassandra DAO)
//                    Map<String, Object> toUpdate = matching.get();
//                    String notificationId = (String) toUpdate.get(NOTIFICATION_ID);
//
//                    Map<String, Object> updateMap = new HashMap<>();
//                    updateMap.put(NOTIFICATION_ID, notificationId);
//                    updateMap.put(USER_ID, userId);
//                    updateMap.put(UPDATED_AT, now);
//                    updateMap.put(MESSAGE, buildUpdatedMessage(toUpdate, clubConfig.getMessageTemplate()));
//
//                    cassandraOperation.updateRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_USER_NOTIFICATION, updateMap);
//                    finalNotifications.add(toUpdate);
//                } else {
//                    // Create new notification entry
//                    Map<String, Object> dbMap = buildBaseNotificationMap(userId, now, messageData);
//                    finalNotifications.add(dbMap);
//                }
//            }
//
//            // Persist individual notifications to Cassandra
//            if (!individualNotifications.isEmpty()) {
//                cassandraOperation.insertBulkRecord(
//                        Constants.KEYSPACE_SUNBIRD,
//                        Constants.TABLE_INDIVIDUAL_NOTIFICATION,  // replace with your actual individual notification table name
//                        individualNotifications
//                );
//            }
//
//            for (String userId : userIdsForCountUpdate) {
//                incrementUnreadCountManually(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_UNREAD_NOTIFICATION_COUNT, userId);
//            }
//
//
//
//            outgoingResponse.setResponseCode(HttpStatus.OK);
//            outgoingResponse.setResult(Map.of("notifications", finalNotifications.stream()
//                    .map(this::prepareNotificationResponse)
//                    .toList()));
//
//            log.info("Final notifications size: {}", finalNotifications.size());
//
//            return outgoingResponse;
//
//        } catch (Exception e) {
//            log.error("Error during bulk notification creation", e);
//            updateErrorDetails(outgoingResponse, "Internal server error while saving notifications", HttpStatus.INTERNAL_SERVER_ERROR);
//            return outgoingResponse;
//        }
//    }
//
//
//
//    private Map<String, Object> buildBaseNotificationMap(String userId, Instant now, Map<String, Object> fields) {
//        Map<String, Object> dbMap = new HashMap<>(fields);
//        dbMap.put(Constants.NOTIFICATION_ID,  java.util.UUID.randomUUID().toString());
//        dbMap.put(Constants.USER_ID, userId);
//        dbMap.put(Constants.CREATED_AT, now);
//        dbMap.put(Constants.UPDATED_AT, now);
//        dbMap.put(Constants.IS_DELETED, false);
//        dbMap.put(Constants.READ, false);
//        dbMap.put(Constants.READ_AT, null);
//        return dbMap;
//    }
//
//    private Map<String, Object> buildUpdatedMessage(Map<String, Object> existing, String template) {
//        Map<String, Object> updatedMessage = new HashMap<>();
//        Map<String, Object> oldMessage = safeCastMap(existing.get("message"));
//        int count = Optional.ofNullable(oldMessage.get("data"))
//                .map(this::safeCastMap)
//                .map(data -> data.get("count"))
//                .map(Object::toString)
//                .map(Integer::parseInt)
//                .orElse(1);
//
//        Map<String, Object> newData = new HashMap<>();
//        newData.put("count", count + 1);
//        updatedMessage.put("body", template.replace("{count}", String.valueOf(count + 1)));
//        updatedMessage.put("data", newData);
//        return updatedMessage;
//    }
//
//        @SuppressWarnings("unchecked")
//    public  Map<String, Object> safeCastMap(Object obj) {
//        if (obj instanceof Map<?, ?>) {
//            return (Map<String, Object>) obj;
//        }
//
//        if (obj instanceof String str) {
//            try {
//                ObjectMapper mapper = new ObjectMapper();
//                return mapper.readValue(str, new TypeReference<Map<String, Object>>() {
//                });
//            } catch (Exception e) {
//                log.error("Failed to parse JSON string to Map: {}", str, e);
//            }
//        }
//
//        return Collections.emptyMap();
//    }
//
//





    @Override
    public ApiResponse readByUserIdAndNotificationId(String notificationId, String authToken) {
        log.info("NotificationService::readByUserIdAndNotificationId: inside the method");
        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_READ_NOTIFICATIONID);

        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);

            if (StringUtils.isEmpty(userId)) {
                updateErrorDetails(outgoingResponse, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            List<Map<String, Object>> notifications = fetchNotifications(userId);

            Optional<Map<String, Object>> match = notifications.stream()
                    .filter(n -> notificationId.equals(n.get(NOTIFICATION_ID)))
                    .findFirst();

            if (match.isPresent()) {
                Map<String, Object> resultMap = prepareNotificationResponse(match.get());
                outgoingResponse.setResult(resultMap);
                outgoingResponse.setResponseCode(HttpStatus.OK);
            } else {
                outgoingResponse.getParams().setErrMsg("Notification not found for this user.");
                outgoingResponse.getParams().setStatus(Constants.SUCCESS);
                outgoingResponse.setResponseCode(HttpStatus.OK);
            }
            logger.info("NotificationServiceImpl::readByUserIdAndNotificationId retrieved successfully ");

        } catch (Exception e) {
            logger.error("Error while fetching readByUserIdAndNotificationId from Cassandra: {}", e.getMessage(), e);
            updateErrorDetails(outgoingResponse, "Internal server error while fetching notification by userId",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return outgoingResponse;
    }

    @Override
    public ApiResponse getNotificationsByUserIdAndLastXDays(String authToken, int days, int page, int size, NotificationReadStatus status, String subTypeFilter) {
        log.info("NotificationService::readByUserIdAndLastXDaysNotifications: inside the method");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_READ_N_DAYSID);

        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);
            if (StringUtils.isEmpty(userId)) {
                updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return response;
            }

            System.out.println(userId+"userId");


            Instant fromDate = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days).toInstant();

            List<Map<String, Object>> allNotifications = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    Map.of(USER_ID, userId),
                    List.of(NOTIFICATION_ID, CREATED_AT, TYPE, MESSAGE, READ, ROLE, SOURCE, CATEGORY, SUB_CATEGORY, SUB_TYPE, IS_DELETED),
                    MAX_NOTIFICATIONS_FETCH_FOR_READ
            );

            List<Map<String, Object>> statFiltered = allNotifications.stream()
                    .filter(notification -> {
                        Instant createdAt = (Instant) notification.get(CREATED_AT);
                        if (createdAt == null || createdAt.isBefore(fromDate)) return false;

                        Boolean isRead = (Boolean) notification.get(READ);
                        if (status == NotificationReadStatus.READ && !Boolean.TRUE.equals(isRead)) return false;
                        if (status == NotificationReadStatus.UNREAD && !Boolean.FALSE.equals(isRead)) return false;

                        Boolean isDeleted = (Boolean) notification.get(IS_DELETED);
                        if (Boolean.TRUE.equals(isDeleted)) return false;

                        return true;
                    })
                    .toList();

            Map<String, Map<String, Integer>> subTypeCountMap = new HashMap<>();
            for (Map<String, Object> notification : statFiltered) {
                String cat = (String) notification.getOrDefault(SUB_TYPE, ALL);
                Boolean isRead = (Boolean) notification.get(READ);

                Map<String, Integer> counts = subTypeCountMap.computeIfAbsent(cat, k -> new HashMap<>());
                counts.put(READ, counts.getOrDefault(READ, 0) + (Boolean.TRUE.equals(isRead) ? 1 : 0));
                counts.put(UNREAD, counts.getOrDefault(UNREAD, 0) + (Boolean.FALSE.equals(isRead) ? 1 : 0));
            }

            List<Map<String, Object>> subTypeStats = subTypeCountMap.entrySet().stream()
                    .map(this::buildSubTypeStat)
                    .sorted(Comparator.comparingInt(stat -> getFixedOrderIndex((String) stat.get(NAME))))
                    .toList();

            List<Map<String, Object>> finalFiltered = statFiltered.stream()
                    .filter(notification -> {

                        if (StringUtils.isNotBlank(subTypeFilter)) {
                            String subType = (String) notification.getOrDefault(SUB_TYPE, ALL);
                            return subTypeFilter.equalsIgnoreCase(subType);
                        }
                        return true;
                    })
                    .toList();


            //clubbed notification
//            finalFiltered = clubNotifications(finalFiltered);


            int total = finalFiltered.size();
            int fromIndex = Math.min(page * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            List<Map<String, Object>> paginated = finalFiltered.subList(fromIndex, toIndex);
            List<Map<String, Object>> processed = paginated.stream()
                    .map(this::prepareNotificationResponse)
                    .toList();

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put(NOTIFICATIONS, processed);
            resultMap.put(TOTAL_COUNT, statFiltered.size());
            resultMap.put(PAGE, page);
            resultMap.put(SIZE, size);
            resultMap.put(HAS_NEXT_PAGE, toIndex < total);
            resultMap.put(SUBTYPE_STATS, subTypeStats);

            response.setResponseCode(HttpStatus.OK);
            response.setResult(resultMap);
            log.info("NotificationServiceImpl::readByUserIdAndLastXDaysNotifications: list retrieved successfully");

        } catch (Exception e) {
            log.error("Error while fetching readByUserIdAndLastXDaysNotifications from Cassandra: {}", e.getMessage(), e);
            updateErrorDetails(response,
                    "Internal server error while fetching readByUserIdAndLastXDaysNotifications list",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    private Map<String, Object> buildSubTypeStat(Map.Entry<String, Map<String, Integer>> entry) {
        Map<String, Object> stat = new HashMap<>();
        stat.put(NAME, entry.getKey());
        stat.put(READ, entry.getValue().getOrDefault(READ, 0));
        stat.put(UNREAD, entry.getValue().getOrDefault(UNREAD, 0));
        return stat;
    }

    private int getFixedOrderIndex(String subType) {
        try {
            return NotificationSubType.valueOf(subType.toUpperCase()).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }


    @Override
    public ApiResponse markNotificationsAsRead(String authToken, Map<String, Object> request) {
        log.info("NotificationService::markNotificationsAsRead - Incoming request: {}", request);

        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_READ_UPDATEID);
        String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);

        if (StringUtils.isEmpty(userId)) {
            updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
            return response;
        }

        String type = (String) request.get(TYPE);
        if (StringUtils.isBlank(type)) {
            updateErrorDetails(response, "Request type must be provided (all or individual)", HttpStatus.BAD_REQUEST);
            return response;
        }

        try {
            List<Map<String, Object>> userNotifications = fetchNotifications(userId);
            List<String> notificationIds;

            if (ALL.equalsIgnoreCase(type)) {
                notificationIds = userNotifications.stream()
                        .map(n -> (String) n.get(NOTIFICATION_ID))
                        .collect(Collectors.toList());
            } else if (INDIVIDUAL.equalsIgnoreCase(type)) {
                notificationIds = extractIndividualNotificationIds(request, response);
                if (notificationIds == null) return response;
            } else {
                updateErrorDetails(response, "Invalid type. Allowed values: all, individual", HttpStatus.BAD_REQUEST);
                return response;
            }

            List<Map<String, Object>> updated = processReadUpdate(userId, userNotifications, notificationIds);

            response.getParams().setErrMsg("Notifications updated successfully");
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResponseCode(HttpStatus.OK);
            response.setResult(Map.of("notifications", updated));

            log.info("Notifications marked as read successfully. Count: {}", updated.size());
        } catch (Exception e) {
            log.error("Unexpected error during markNotificationsAsRead: {}", e.getMessage(), e);
            updateErrorDetails(response, "Internal server error while updating notifications", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractIndividualNotificationIds(Map<String, Object> request, ApiResponse response) {
        Object idsObj = request.get("ids");
        if (idsObj instanceof List<?>) {
            return (List<String>) idsObj;
        } else {
            updateErrorDetails(response, "Missing or invalid 'ids' field for individual type", HttpStatus.BAD_REQUEST);
            return null;
        }
    }

    private List<Map<String, Object>> processReadUpdate(
            String userId,
            List<Map<String, Object>> userNotifications,
            List<String> targetIds
    ) {
        List<Map<String, Object>> updated = new ArrayList<>();
        Instant now = Instant.now();

        for (String notificationId : targetIds) {
            Optional<Map<String, Object>> matchOpt = userNotifications.stream()
                    .filter(n -> notificationId.equals(n.get(NOTIFICATION_ID)))
                    .findFirst();

            if (matchOpt.isEmpty()) {
                log.warn("Notification ID {} not found for user {}", notificationId, userId);
                continue;
            }

            Map<String, Object> notification = matchOpt.get();
            boolean alreadyRead = Boolean.TRUE.equals(notification.get(READ));

            if (alreadyRead) {
                log.debug("Notification {} already marked as read. Skipping.", notificationId);
                continue;
            }

            Map<String, Object> updateMap = Map.of(
                    READ, true,
                    READ_AT, now
            );

            Map<String, Object> result = updateNotification(userId, notificationId, updateMap);

            if (Constants.SUCCESS.equalsIgnoreCase((String) result.get(Constants.RESPONSE))) {
                updated.add(Map.of(
                        ID, notificationId,
                        READ, true,
                        READ_AT, now.toString()
                ));
            } else {
                log.warn("Failed to update notification ID {} for user {}", notificationId, userId);
            }
        }

        return updated;
    }

    @Override
    public ApiResponse markNotificationsAsDeleted(String authToken, List<String> notificationIds) {
        log.info("NotificationService::markNotificationsAsDeleted - ids: {}", notificationIds);

        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.USER_NOTIFICATION_DELETE);
        List<Map<String, Object>> updated = new ArrayList<>();

        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);
            if (StringUtils.isEmpty(userId)) {
                updateErrorDetails(outgoingResponse, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            String errMsg = validateNotificationReadRequest(notificationIds, outgoingResponse);
            if (StringUtils.isNotBlank(errMsg)) {
                return outgoingResponse;
            }


            for (String notificationId : notificationIds) {
                Map<String, Object> updateMap = Map.of(
                        IS_DELETED, true,
                        UPDATED_AT, Instant.now()
                );

                Map<String, Object> result = updateNotification(userId, notificationId, updateMap);


                if (Constants.SUCCESS.equalsIgnoreCase((String) result.get(Constants.RESPONSE))) {
                    updated.add(Map.of(
                            ID, notificationId,
                            IS_DELETED, true
                    ));
                } else {
                    log.info("Notification {} is already marked as deleted or has no created_at", notificationId);
                }
            }

            outgoingResponse.getParams().setErrMsg("Notifications marked as deleted successfully");
            outgoingResponse.getParams().setStatus(Constants.SUCCESS);
            outgoingResponse.setResponseCode(HttpStatus.OK);
            outgoingResponse.setResult(Map.of(NOTIFICATIONS, updated));

            logger.info("NotificationServiceImpl::markNotificationsAsDeleted  delete successfully ");

        } catch (Exception e) {
            logger.error("Error while fetching  markNotificationsAsDeleted delete from Cassandra: {}", e.getMessage(), e);
            updateErrorDetails(outgoingResponse, "Internal server error while fetching markNotificationsAsDeleted  delete",
                    HttpStatus.INTERNAL_SERVER_ERROR);
            return outgoingResponse;
        }
        return outgoingResponse;
    }

    @Override
    public ApiResponse getUnreadNotificationCount(String authToken, int days) {
        log.info("NotificationService::getUnreadNotificationCount: inside the method");

        ApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(USER_NOTIFICATION_UNREAD_COUNT);

        try {

            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);
            if (StringUtils.isEmpty(userId)) {
                updateErrorDetails(outgoingResponse, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }

            ApiResponse daysValidationResponse = validateDays(days);
            if (daysValidationResponse != null && daysValidationResponse.getResponseCode() != null &&
                    !HttpStatus.OK.equals(daysValidationResponse.getResponseCode())) {
                return daysValidationResponse;
            }

            int unreadCount = 0;
            Map<String, Object> criteria = Map.of(Constants.USER_ID, userId);

            List<Map<String, Object>> countRecords = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_UNREAD_NOTIFICATION_COUNT,
                    criteria,
                    List.of(COUNT),
                    1
            );

            if (countRecords != null && !countRecords.isEmpty()) {
                Map<String, Object> record = countRecords.get(0);
                if (record != null) {
                    Object countObj = record.get(COUNT);
                    if (countObj instanceof Number) {
                        unreadCount = ((Number) countObj).intValue();
                    }
                }
            } else {
                Map<String, Object> insertMap = new HashMap<>();
                insertMap.put(Constants.USER_ID, userId);
                insertMap.put(COUNT, 0);
                cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_UNREAD_NOTIFICATION_COUNT, insertMap);
            }

            log.info("Fetched unread count for userId {}: {}", userId, unreadCount);
            outgoingResponse.setResponseCode(HttpStatus.OK);
            outgoingResponse.setResult(Map.of("unread", unreadCount));

        } catch (Exception e) {
            log.error("Error in getUnreadNotificationCount: {}", e.getMessage(), e);
            updateErrorDetails(outgoingResponse,
                    "Internal server error while fetching unread notification count",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return outgoingResponse;
    }


    @Override
    public ApiResponse getResetNotificationCount(String authToken) {
        log.info("NotificationService::getResetNotificationCount - Start");

        ApiResponse response = ProjectUtil.createDefaultResponse(USER_NOTIFICATION_UNREAD_RESET_COUNT);

        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(authToken);

            if (StringUtils.isBlank(userId)) {
                log.warn("User ID not found from token.");
                updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return response;
            }

            Map<String, Object> updateAttributes = Map.of(COUNT, 0);
            Map<String, Object> compositeKey = Map.of(Constants.USER_ID, userId);

            Map<String, Object> updateResponse = cassandraOperation.updateRecordByCompositeKey(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_UNREAD_NOTIFICATION_COUNT,
                    updateAttributes,
                    compositeKey
            );

            if (!Constants.SUCCESS.equals(updateResponse.get(Constants.RESPONSE))) {
                log.warn("Failed to reset unread count for userId: {}", userId);
            } else {
                log.info("Unread count successfully reset to 0 for userId: {}", userId);
            }

            response.setResponseCode(HttpStatus.OK);

        } catch (Exception e) {
            log.error("Exception in getResetNotificationCount: {}", e.getMessage(), e);
            updateErrorDetails(response,
                    "Internal server error while resetting unread notification count",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    private String validateNotificationReadRequest(List<String> ids, ApiResponse response) {
        if (org.springframework.util.CollectionUtils.isEmpty(ids)) {
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrMsg("Request must contain a non-empty list of notification IDs.");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return "Request must contain a non-empty list of notification IDs.";
        }

        if (ids.size() > Constants.MAX_NOTIFICATION_READ_BATCH_SIZE) {
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrMsg("You can only mark up to " + Constants.MAX_NOTIFICATION_READ_BATCH_SIZE + " notifications as read at a time.");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return "You can only mark up to " + Constants.MAX_NOTIFICATION_READ_BATCH_SIZE + " notifications as read at a time.";
        }

        return "";
    }

    private void updateErrorDetails(ApiResponse response, String errorMessage, HttpStatus httpStatus) {
        response.getParams().setStatus(Constants.FAILED);
        response.getParams().setErrMsg(errorMessage);
        response.setResponseCode(httpStatus);
    }

    private List<Map<String, Object>> fetchNotifications(String userId) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put(USER_ID, userId);

        return cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD,
                Constants.TABLE_USER_NOTIFICATION,
                queryMap,
                null,
                MAX_NOTIFICATIONS_FETCH_FOR_READ
        );
    }


    private Map<String, Object> updateNotification(String userId, String notificationId, Map<String, Object> updateMap) {
        List<Map<String, Object>> records = fetchNotifications(userId);

        Optional<Map<String, Object>> match = records.stream()
                .filter(r -> notificationId.equals(r.get(NOTIFICATION_ID)))
                .findFirst();

        if (match.isPresent()) {
            Map<String, Object> notification = match.get();
            Instant createdAt = (Instant) notification.get(CREATED_AT);

            Map<String, Object> compositeKey = Map.of(
                    USER_ID, userId,
                    CREATED_AT, createdAt
            );

            return cassandraOperation.updateRecordByCompositeKey(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    updateMap,
                    compositeKey
            );
        }
        return Collections.emptyMap();
    }


    public Map<String, Object> prepareNotificationResponse(Map<String, Object> dbRecord) {
        Map<String, Object> resultMap = new HashMap<>(dbRecord);
        List<String> fieldsToRemove = Arrays.asList(
                Constants.IS_DELETED,
                Constants.UPDATED_AT,
                Constants.USER_ID,
                Constants.READ_AT,
                Constants.TEMPLATE_ID
        );
        fieldsToRemove.forEach(resultMap::remove);
        Object messageObj = resultMap.get("message");

        if (messageObj instanceof String && messageObj != null) {
            try {
                JsonNode parsed = objectMapper.readTree((String) messageObj);
                resultMap.put("message", parsed);
                log.info("Message successfully parsed into JSON: {}", parsed.toPrettyString());
            } catch (Exception e) {
                log.warn("Could not parse message field as JSON: {}", e.getMessage());
            }
        } else {
            log.warn("Message field is not a valid string or is null.");
        }

        return resultMap;
    }


    private ApiResponse validateDays(int days) {
        ApiResponse response = new ApiResponse();
        if (days <= 0) {
            log.warn("Invalid 'days' parameter: {}", days);
            updateErrorDetails(response, "'days' parameter must be greater than 0", HttpStatus.BAD_REQUEST);
            return response;
        }
        return null;
    }

    private void incrementUnreadCountManually(String keyspace, String table, String userId) {
        try {
            Map<String, Object> whereClause = new HashMap<>();
            whereClause.put(USER_ID, userId);

            List<String> fields = Collections.singletonList(COUNT);
            List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    keyspace, table, whereClause, fields, 1
            );

            int updatedCount = 1;

            if (!records.isEmpty() && records.get(0).get(COUNT) != null) {
                int currentCount = (int) records.get(0).get(COUNT);
                updatedCount = currentCount + 1;
            }

            Map<String, Object> updateAttributes = new HashMap<>();
            updateAttributes.put(COUNT, updatedCount);

            cassandraOperation.updateRecordByCompositeKey(
                    keyspace,
                    table,
                    updateAttributes,
                    whereClause
            );

            log.info("Unread notification count updated for user {}: {}", userId, updatedCount);
        } catch (Exception e) {
            log.error("Error updating unread count for user {}: {}", userId, e.getMessage(), e);
        }
    }

//
//    public static final String UNKNOWN = "unknown";
//    private static final Map<String, ClubbingConfig> CLUBBING_CONFIG_MAP = Arrays.stream(NotificationClubSubCategory.values())
//            .filter(NotificationClubSubCategory::isClubbable)
//            .collect(Collectors.toMap(
//                    Enum::name,
//                    subCategory -> new ClubbingConfig(
//                            subCategory.getClubbingWindow(),
//                            notif -> {
//                                try {
//                                    Map<String, Object> msg = safeCastMap(notif.get(MESSAGE));
//                                    Map<String, Object> data = safeCastMap(msg.get(DATA));
//                                    String userId = String.valueOf(notif.getOrDefault(USER_ID, "unknown"));
//
//                                    // Customize grouping key logic per subcategory if needed
//                                    if (data.containsKey(DISCUSSION_ID)) {
//                                        return data.get(DISCUSSION_ID) + "::" + userId;
//                                    } else if (data.containsKey(ID)) {
//                                        return data.get(ID) + "::" + userId;
//                                    } else {
//                                        return userId;
//                                    }
//
//                                } catch (Exception e) {
//                                    return UNKNOWN;
//                                }
//                            },
//                            subCategory.getMessageTemplate()
//                    )
//            ));
//
//    @SuppressWarnings("unchecked")
//    public static Map<String, Object> safeCastMap(Object obj) {
//        if (obj instanceof Map<?, ?>) {
//            return (Map<String, Object>) obj;
//        }
//
//        if (obj instanceof String str) {
//            try {
//                ObjectMapper mapper = new ObjectMapper();
//                return mapper.readValue(str, new TypeReference<Map<String, Object>>() {
//                });
//            } catch (Exception e) {
//                log.error("Failed to parse JSON string to Map: {}", str, e);
//            }
//        }
//
//        return Collections.emptyMap();
//    }
//
//    private List<Map<String, Object>> clubNotifications(List<Map<String, Object>> notifications) {
//        List<Map<String, Object>> result = new ArrayList<>();
//
//        Map<String, List<Map<String, Object>>> bySubCategory = notifications.stream()
//                .collect(Collectors.groupingBy(n -> (String) n.get(SUB_CATEGORY)));
//
//        for (Map.Entry<String, List<Map<String, Object>>> entry : bySubCategory.entrySet()) {
//            String subCategory = entry.getKey();
//            List<Map<String, Object>> subList = entry.getValue();
//
//            ClubbingConfig config = CLUBBING_CONFIG_MAP.get(subCategory);
//            if (config == null) {
//                result.addAll(subList);
//                continue;
//            }
//
//            subList.sort(Comparator.comparing(n -> (Instant) n.get(CREATED_AT)));
//
//            Map<String, List<List<Map<String, Object>>>> grouped = new HashMap<>();
//            for (Map<String, Object> notif : subList) {
//                String key = config.getGroupKey().apply(notif);
//                Instant created = (Instant) notif.get(CREATED_AT);
//                List<List<Map<String, Object>>> windows = grouped.computeIfAbsent(key, k -> new ArrayList<>());
//
//                boolean added = false;
//                for (List<Map<String, Object>> group : windows) {
//                    Instant first = (Instant) group.get(0).get(CREATED_AT);
//                    if (Duration.between(first, created).compareTo(config.getWindow()) <= 0) {
//                        group.add(notif);
//                        added = true;
//                        break;
//                    }
//                }
//
//                if (!added) {
//                    windows.add(new ArrayList<>(List.of(notif)));
//                }
//            }
//
//            for (List<List<Map<String, Object>>> groupedLists : grouped.values()) {
//                for (List<Map<String, Object>> group : groupedLists) {
//                    if (group.size() == 1) {
//                        result.add(group.get(0));
//                    } else {
//                        Map<String, Object> base = group.get(0);
//                        Map<String, Object> clubbed = new HashMap<>(base);
//
//                        // Merge data from all notifications in the group
//                        Map<String, Object> messageData = new HashMap<>();
//                        for (Map<String, Object> notification : group) {
//                            Map<String, Object> msg = safeCastMap(notification.get(MESSAGE));
//                            Map<String, Object> data = safeCastMap(msg.get(DATA));
//                            messageData.putAll(data);
//                        }
//
//                        // Build final message
//                        Map<String, Object> finalMessage = new HashMap<>();
//                        finalMessage.put(BODY, config.getMessageTemplate().replace("{count}", String.valueOf(group.size())));
//                        finalMessage.put(DATA, messageData);
//
//                        // Add notification IDs from all grouped
//                        List<String> notificationIds = group.stream()
//                                .map(n -> (String) n.get(NOTIFICATION_ID))
//                                .collect(Collectors.toList());
//                        clubbed.put(NOTIFICATION_IDS, notificationIds);
//                        clubbed.put(MESSAGE, finalMessage);
//                        result.add(clubbed);
//                    }
//                }
//            }
//        }
//
//        return result;
//    }


}

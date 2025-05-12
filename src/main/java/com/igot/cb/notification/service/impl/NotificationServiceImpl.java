package com.igot.cb.notification.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.util.Constants;
import com.igot.cb.util.TransformUtility;
import com.igot.cb.util.cache.CacheService;
import com.igot.cb.util.dto.SBApiResponse;
import com.igot.cb.util.exceptions.CustomException;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;


import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    AccessTokenValidator accessTokenValidator;

    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    private TransformUtility transformUtility;

    @Autowired
    CacheService cacheService;

    @Override
    public SBApiResponse createNotification(JsonNode userNotificationDetail, String token) {
        log.info("NotificationService::CreateNotification: inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_CREATE);
        try {
//            String userId = accessTokenValidator.verifyUserToken(token);
            String userId = "96102"; // Normally extracted from token
            log.info("UserId from auth token {}", userId);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            ZoneId zoneId = ZoneId.of("UTC");
            Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();

            Map<String, Object> dbMap = new HashMap<>();
            dbMap.put("notification_id", UUID.randomUUID().toString());
            dbMap.put("user_id", userId);
            dbMap.put("created_at", instant);
            dbMap.put("updated_at", instant);
            dbMap.put("is_deleted", false);
            dbMap.put("read", false);
            dbMap.put("read_at", null);

            Iterator<Map.Entry<String, JsonNode>> fields = userNotificationDetail.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                dbMap.put(entry.getKey(), entry.getValue().asText());
            }

            Object res = cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION, dbMap);
            log.info("Inserted notification: {}", res.toString());

            // Prepare response by excluding unwanted fields like 'is_deleted'
            Map<String, Object> responseMap = new HashMap<>(dbMap);
            responseMap.remove("is_deleted");

            response.setResponseCode(HttpStatus.OK);
            response.setResult(responseMap);

            String notificationId = (String) responseMap.get("notification_id");
            if (StringUtils.isNotBlank(notificationId)) {
                cacheService.putCache("notification:" + notificationId, response);
            }

            return response;

        } catch (Exception e) {
            String errMsg = "Error while performing operation: " + e.getMessage();
            log.error(errMsg, e);
            response.getParams().setMsg(errMsg);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }

    @Override
    public SBApiResponse readByUserIdAndNotificationId(String notificationId, String token) {
        log.info("NotificationService::readByUserIdAndNotificationId: inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_READ_NOTIFICATIONID);

        try {
            String userId = "96102"; // Replace with token extraction
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            // Step 1: Query all notifications for the user
            Map<String, Object> queryMap = new HashMap<>();
            queryMap.put("user_id", userId);
            List<Map<String, Object>> notifications = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    queryMap,
                    null,
                    null
            );

            // Step 2: Filter in-memory for the correct notification_id
            Optional<Map<String, Object>> match = notifications.stream()
                    .filter(n -> notificationId.equals(n.get("notification_id")))
                    .findFirst();

            if (match.isPresent()) {
                Map<String, Object> resultMap = new HashMap<>(match.get());
                resultMap.remove("is_deleted");
                response.setResult(resultMap);
                response.setResponseCode(HttpStatus.OK);
            } else {
                response.getParams().setMsg("Notification not found for this user.");
                response.getParams().setStatus(Constants.SUCCESS);
                response.setResponseCode(HttpStatus.OK);
            }

            return response;

        } catch (Exception e) {
            log.error("Error while processing notification read", e);
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @Override
    public SBApiResponse readByUserIdAndLastXDaysNotifications(String token, int days, int page, int size) {
        log.info("NotificationService::readByUserIdAndLastXDaysNotifications: inside the method");

        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_READ_N_DAYSID);

        try {
            // String userId = accessTokenValidator.verifyUserToken(token);
            String userId = "96102"; // Replace with actual token logic

            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            // Dynamically calculate "N days ago"
            Instant fromDate = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days).toInstant();

            // Fetch all notifications for this user
            List<Map<String, Object>> userNotificationList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_NOTIFICATION,
                    Map.of("user_id", userId),
                    List.of("notification_id", "created_at", "type", "message", "read"),
                    200 // You can also make this dynamic if needed
            );

            // Filter for notifications created after 'fromDate'
            List<Map<String, Object>> filteredNotifications = userNotificationList.stream()
                    .filter(notification -> {
                        Instant createdAt = (Instant) notification.get("created_at");
                        return createdAt != null && createdAt.isAfter(fromDate);
                    })
                    .collect(Collectors.toList());

            // Pagination
            int total = filteredNotifications.size();
            int fromIndex = Math.min(page * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            List<Map<String, Object>> paginated = filteredNotifications.subList(fromIndex, toIndex);

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("notifications", paginated);
            resultMap.put("total", total);
            resultMap.put("page", page);
            resultMap.put("size", size);
            resultMap.put("hasNextPage", toIndex < total);

            response.setResponseCode(HttpStatus.OK);
            response.setResult(resultMap);
            return response;

        } catch (Exception e) {
            log.error("Error while processing readByUserIdAndLastXDaysNotifications", e);
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public SBApiResponse markNotificationsAsRead(String token, List<String> notificationIds) {
        log.info("NotificationService::markNotificationsAsRead - ids: {}", notificationIds);

        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_READ_UPDATEID);
        List<Map<String, Object>> updated = new ArrayList<>();

        try {
            String userId = "96102"; // Replace with token-based logic

            for (String notificationId : notificationIds) {
                // Fetch all records for this user
                List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD,
                        Constants.TABLE_USER_NOTIFICATION,
                        Map.of("user_id", userId),
                        null,
                        100
                );

                Optional<Map<String, Object>> match = records.stream()
                        .filter(r -> notificationId.equals(r.get("notification_id")))
                        .findFirst();

                if (match.isPresent()) {
                    Map<String, Object> notification = match.get();
                    Instant createdAt = (Instant) notification.get("created_at");

                    // Check if already read
                    Object readValue = notification.get("read");
                    boolean isAlreadyRead = readValue instanceof Boolean && (Boolean) readValue;

                    if (createdAt != null && !isAlreadyRead) {
                        Instant readAt = Instant.now();

                        Map<String, Object> updateMap = Map.of(
                                "read", true,
                                "read_at", readAt
                        );

                        Map<String, Object> compositeKey = Map.of(
                                "user_id", userId,
                                "created_at", createdAt
                        );

                        Map<String, Object> result = cassandraOperation.updateRecord(
                                Constants.KEYSPACE_SUNBIRD,
                                Constants.TABLE_USER_NOTIFICATION,
                                updateMap,
                                compositeKey
                        );

                        if (Constants.SUCCESS.equalsIgnoreCase((String) result.get(Constants.RESPONSE))) {
                            updated.add(Map.of(
                                    "id", notificationId,
                                    "read", true,
                                    "read_at", readAt.toString()
                            ));
                        }
                    } else {
                        log.info("Notification {} already marked as read. Skipping.", notificationId);
                    }
                }
            }

            response.getParams().setMsg("Notifications updated successfully");
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResponseCode(HttpStatus.OK);
            response.setResult(Map.of("notifications", updated));

            return response;

        } catch (Exception e) {
            log.error("Error in markNotificationsAsRead", e);
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @Override
    public SBApiResponse markNotificationsAsDeleted(String token, List<String> notificationIds) {
        log.info("NotificationService::markNotificationsAsDeleted - ids: {}", notificationIds);

        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_DELETE);
        List<Map<String, Object>> updated = new ArrayList<>();

        try {
            String userId = "96102"; // Replace with actual token parsing logic

            for (String notificationId : notificationIds) {
                List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD,
                        Constants.TABLE_USER_NOTIFICATION,
                        Map.of("user_id", userId),
                        null,
                        100
                );

                Optional<Map<String, Object>> match = records.stream()
                        .filter(r -> notificationId.equals(r.get("notification_id")))
                        .findFirst();

                if (match.isPresent()) {
                    Map<String, Object> notification = match.get();
                    Instant createdAt = (Instant) notification.get("created_at");

                    if (createdAt != null && !Boolean.TRUE.equals(notification.get("is_deleted"))) {
                        Map<String, Object> updateMap = Map.of(
                                "is_deleted", true,
                                "updated_at", Instant.now()
                        );

                        Map<String, Object> compositeKey = Map.of(
                                "user_id", userId,
                                "created_at", createdAt
                        );

                        Map<String, Object> result = cassandraOperation.updateRecord(
                                Constants.KEYSPACE_SUNBIRD,
                                Constants.TABLE_USER_NOTIFICATION,
                                updateMap,
                                compositeKey
                        );

                        if (Constants.SUCCESS.equalsIgnoreCase((String) result.get(Constants.RESPONSE))) {
                            updated.add(Map.of(
                                    "id", notificationId,
                                    "is_deleted", true
                            ));
                        }
                    } else {
                        log.info("Notification {} is already marked as deleted or has no created_at", notificationId);
                    }
                }
            }

            response.getParams().setMsg("Notifications marked as deleted successfully");
            response.getParams().setStatus(Constants.SUCCESS);
            response.setResponseCode(HttpStatus.OK);
            response.setResult(Map.of("notifications", updated));

            return response;

        } catch (Exception e) {
            log.error("Error in markNotificationsAsDeleted", e);
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }




}

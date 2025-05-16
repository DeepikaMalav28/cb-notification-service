package com.igot.cb.userNotificationSetting.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.userNotificationSetting.entity.NotificationSettingEntity;
import com.igot.cb.userNotificationSetting.repo.NotificationSettingRepository;
import com.igot.cb.userNotificationSetting.service.UserNotificationSettingService;
import com.igot.cb.util.Constants;
import com.igot.cb.util.TransformUtility;
import com.igot.cb.util.cache.CacheService;
import com.igot.cb.util.dto.SBApiResponse;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;


@Service
@Slf4j
public class UserNotificationSettingServiceImpl implements UserNotificationSettingService {


    @Autowired
    AccessTokenValidator accessTokenValidator;

    @Autowired
    private TransformUtility transformUtility;

    @Autowired
    CacheService cacheService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationSettingRepository notificationSettingRepository;

    @Override
    public SBApiResponse createUserNotificationSetting(JsonNode userNotificationDetail, String token) {
        log.info("NotificationSettingService::createUserNotificationSetting - inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.USER_NOTIFICATION_CREATE);

        try {
            String userId = accessTokenValidator.verifyUserToken(token);
            log.info("UserId from auth token: {}", userId);

            if (StringUtils.isBlank(userId) || Constants.UNAUTHORIZED.equalsIgnoreCase(userId)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.UNAUTHORIZED);
                return response;
            }

            JsonNode requestNode = userNotificationDetail.get("request");
            if (requestNode == null || !requestNode.isObject()) {
                log.warn("Missing or invalid 'request' node in payload");
                response.getParams().setMsg("Missing or invalid 'request' node in payload");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }


            String notificationType = requestNode.has("notificationType") ? requestNode.get("notificationType").asText() : null;
            Boolean enabled = requestNode.has("enabled") && requestNode.get("enabled").asBoolean();

            if (StringUtils.isBlank(notificationType)) {
                response.getParams().setMsg("Notification type is required");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }


            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            NotificationSettingEntity entity = NotificationSettingEntity.builder()
                    .userId(userId)
                    .notificationType(notificationType)
                    .enabled(enabled != null ? enabled : true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            NotificationSettingEntity savedEntity = notificationSettingRepository.save(entity);


            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("userId", savedEntity.getUserId());
            responseMap.put("notificationType", savedEntity.getNotificationType());
            responseMap.put("enabled", savedEntity.isEnabled());
            responseMap.put("createdAt", savedEntity.getCreatedAt());

            response.setResponseCode(HttpStatus.OK);
            response.setResult(responseMap);
            log.info("User notification setting created successfully: {}", responseMap);

            return response;

        } catch (Exception e) {
            log.error("Error while creating user notification setting", e);
            response.getParams().setMsg("Error: " + e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }
}













package com.igot.cb.userNotificationSetting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.notification.enums.NotificationReadStatus;
import com.igot.cb.util.dto.SBApiResponse;

import java.util.List;

public interface UserNotificationSettingService {

    SBApiResponse createUserNotificationSetting(JsonNode userNotificationDetail, String token);


}

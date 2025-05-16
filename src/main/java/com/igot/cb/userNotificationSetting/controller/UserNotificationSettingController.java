package com.igot.cb.userNotificationSetting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.userNotificationSetting.service.UserNotificationSettingService;
import com.igot.cb.util.Constants;
import com.igot.cb.util.dto.SBApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notificationSetting")
public class UserNotificationSettingController {

    @Autowired
    private UserNotificationSettingService userNotificationSettingService;


    @PostMapping("/create")
    public ResponseEntity<SBApiResponse> createUserNotificationSetting(@RequestBody JsonNode userNotificationSettingDetail,
                                                            @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        SBApiResponse response = userNotificationSettingService.createUserNotificationSetting(userNotificationSettingDetail, token);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

}

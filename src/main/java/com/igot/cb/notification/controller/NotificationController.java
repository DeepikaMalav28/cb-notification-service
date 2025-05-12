package com.igot.cb.notification.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.notification.service.NotificationService;

import com.igot.cb.util.Constants;
import com.igot.cb.util.dto.SBApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    NotificationService notificationService;

    @PostMapping("/create")
    public ResponseEntity<SBApiResponse> createNotification(@RequestBody JsonNode userNotificationDetail,
                                                            @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        SBApiResponse response = notificationService.createNotification(userNotificationDetail, token);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/readby/{notificationId}")
    public ResponseEntity<?> readByUserIdAndCourseId(@PathVariable String notificationId, @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        SBApiResponse response = notificationService.readByUserIdAndNotificationId(notificationId, token);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @GetMapping("/list")
    public ResponseEntity<?> getLastXDaysNotifications(
            @RequestHeader(Constants.X_AUTH_TOKEN) String token,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        SBApiResponse response = notificationService.readByUserIdAndLastXDaysNotifications(token, days, page, size);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @PatchMapping("/read")
    public ResponseEntity<?> markNotificationsAsRead(
            @RequestHeader(Constants.X_AUTH_TOKEN) String token,
            @RequestBody Map<String, Object> requestBody) {

        // Extract ids from request
        List<String> ids = (List<String>) ((Map<String, Object>) requestBody.get("request")).get("ids");

        // ✅ Validation checks
        if (ids == null || ids.isEmpty()) {
            return new ResponseEntity<>("Request must contain a non-empty list of notification IDs.", HttpStatus.BAD_REQUEST);
        }

        if (ids.size() > 2) {
            return new ResponseEntity<>("You can only mark up to 20 notifications as read at a time.", HttpStatus.BAD_REQUEST);
        }
        SBApiResponse response = notificationService.markNotificationsAsRead(token, ids);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> markNotificationsAsDeleted(
            @RequestHeader(Constants.X_AUTH_TOKEN) String token,
            @RequestBody Map<String, Object> requestBody) {

        // Extract ids from request
        List<String> ids = (List<String>) ((Map<String, Object>) requestBody.get("request")).get("ids");

        // ✅ Validation checks
        if (ids == null || ids.isEmpty()) {
            return new ResponseEntity<>("Request must contain a non-empty list of notification IDs.", HttpStatus.BAD_REQUEST);
        }

        if (ids.size() > 20) {
            return new ResponseEntity<>("You can only delete up to 20 notifications at a time.", HttpStatus.BAD_REQUEST);
        }

        // Proceed to service call
        SBApiResponse response = notificationService.markNotificationsAsDeleted(token, ids);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}

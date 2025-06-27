

package com.igot.cb.notification.service.impl.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.notification.controller.NotificationController;
import com.igot.cb.notification.enums.NotificationReadStatus;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {

    @InjectMocks
    private NotificationController notificationController;

    @Mock
    private NotificationService notificationService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateNotification() {
        ObjectNode json = objectMapper.createObjectNode();
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.CREATED);

        when(notificationService.createNotification(eq(json), eq(token))).thenReturn(apiResponse);

        ResponseEntity<ApiResponse> response = notificationController.createNotification(json, token);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void testCreateBulkNotification() {
        ObjectNode json = objectMapper.createObjectNode();
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.bulkCreateNotifications(eq(json))).thenReturn(apiResponse);

        ResponseEntity<ApiResponse> response = notificationController.createBulkNotification(json);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testReadByUserIdAndNotificationId() {
        String notifId = "notif-1";
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.readByUserIdAndNotificationId(eq(notifId), eq(token))).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.readByUserIdAndNotificationId(notifId, token);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

//    @Test
//    void testGetLastXDaysNotifications() {
//        String token = "token";
//        int days = 7, page = 0, size = 10;
//        NotificationReadStatus status = NotificationReadStatus.BOTH;
//        String subType = null;
//
//        ApiResponse apiResponse = new ApiResponse();
//        apiResponse.setResponseCode(HttpStatus.OK);
//
//        when(notificationService.getNotificationsByUserIdAndLastXDays(
//                eq(token), eq(days), eq(page), eq(size), eq(status), eq(subType)))
//                .thenReturn(apiResponse);
//
//        ResponseEntity<?> response = notificationController.getLastXDaysNotifications(
//                token, days, page, size, status, subType);
//
//        assertEquals(apiResponse, response.getBody());
//        assertEquals(HttpStatus.OK, response.getStatusCode());
//    }

    @Test
    void testMarkNotificationsAsRead() {
        String token = "token";
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, Constants.ALL);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.REQUEST, request);

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.markNotificationsAsRead(eq(token), eq(request))).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.markNotificationsAsRead(token, requestBody);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testMarkNotificationsAsDeleted() {
        String token = "token";
        List<String> ids = Arrays.asList("id1", "id2");
        Map<String, Object> innerRequest = new HashMap<>();
        innerRequest.put(Constants.IDS, ids);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.REQUEST, innerRequest);

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.markNotificationsAsDeleted(eq(token), eq(ids))).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.markNotificationsAsDeleted(token, requestBody);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetUnreadNotificationCount() {
        String token = "token";
        int days = 3;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.getUnreadNotificationCount(eq(token), eq(days))).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.getUnreadNotificationCount(token, days);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetResetNotificationCount() {
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.getResetNotificationCount(eq(token))).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.getResetNotificationCount(token);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
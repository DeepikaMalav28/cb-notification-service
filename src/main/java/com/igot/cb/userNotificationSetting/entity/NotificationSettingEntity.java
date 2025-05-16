package com.igot.cb.userNotificationSetting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Id;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;




@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "user_notification_settings", schema = "user_settings")
public class NotificationSettingEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}





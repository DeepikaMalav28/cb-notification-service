package com.igot.cb.userNotificationSetting.repo;

import com.igot.cb.userNotificationSetting.entity.NotificationSettingEntity;
import com.igot.cb.userNotificationSetting.model.UserNotificationSettingRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;





@Repository
public interface NotificationSettingRepository extends JpaRepository<NotificationSettingEntity, String> {
}
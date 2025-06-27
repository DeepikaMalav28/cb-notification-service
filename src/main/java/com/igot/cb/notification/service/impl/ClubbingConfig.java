package com.igot.cb.notification.service.impl;

import lombok.*;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

@AllArgsConstructor
@Data
@Getter
@Setter
public class ClubbingConfig {
    private final Duration window;
    private final Function<Map<String, Object>, String> groupKey;
    private final String messageTemplate;
}






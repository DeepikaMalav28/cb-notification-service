package com.igot.cb.notification.templateEnum;

public enum NotificationTemplate {
    TEMPLATE_A("Template A"),
    TEMPLATE_B("Template B"),
    TEMPLATE_C("Template C");

    private final String templateName;

    NotificationTemplate(String templateName) {
        this.templateName = templateName;
    }

    public String getTemplateName() {
        return templateName;
    }

    public static NotificationTemplate fromString(String templateName) {
        for (NotificationTemplate template : NotificationTemplate.values()) {
            if (template.templateName.equalsIgnoreCase(templateName)) {
                return template;
            }
        }
        throw new IllegalArgumentException("Unknown template: " + templateName);
    }
}

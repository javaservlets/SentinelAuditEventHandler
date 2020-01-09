/*
 * Copyright 2015-2017 ForgeRock AS. All Rights Reserved
 *
 * Use of this code requires a commercial software license with ForgeRock AS.
 * or with one of its affiliates. All use shall be exclusively subject
 * to such license between the licensee and ForgeRock AS.
 */
package org.forgerock.audit.handlers.sentinel;

/**
 * Defines the standard Syslog message severities.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5424#section-6.2.1">RFC-5424 section 6.2.1</a>
 */
public enum Severity {

    /**
     * System is unusable.
     */
    EMERGENCY(0),
    /**
     * Action must be taken immediately.
     */
    ALERT(1),
    /**
     * Critical conditions.
     */
    CRITICAL(2),
    /**
     * Error conditions.
     */
    ERROR(3),
    /**
     * Warning conditions.
     */
    WARNING(4),
    /**
     * Normal but significant condition.
     */
    NOTICE(5),
    /**
     * Informational messages.
     */
    INFORMATIONAL(6),
    /**
     * Debug-level messages.
     */
    DEBUG(7);

    private final int code;

    Severity(int code) {
        this.code = code;
    }

    /**
     * Get the sentinel code for the severity.
     * @return The code.
     */
    public int getCode() {
        return code;
    }
}

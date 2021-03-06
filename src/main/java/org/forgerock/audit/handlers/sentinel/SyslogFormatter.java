/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013 Cybernetica AS
 * Portions copyright 2014-2016 ForgeRock AS.
 */
package org.forgerock.audit.handlers.sentinel;

import org.forgerock.audit.AuditService;
import org.forgerock.audit.events.AuditEvent;
import org.forgerock.audit.events.EventTopicsMetaData;
import org.forgerock.audit.handlers.sentinel.SentinelAuditEventHandlerConfiguration.SeverityFieldMapping;
import org.forgerock.audit.providers.LocalHostNameProvider;
import org.forgerock.audit.providers.ProductInfoProvider;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.util.Reject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static org.forgerock.audit.events.AuditEventBuilder.EVENT_NAME;
import static org.forgerock.audit.events.AuditEventBuilder.TIMESTAMP;
import static org.forgerock.audit.events.AuditEventHelper.getAuditEventSchema;
import static org.forgerock.audit.events.AuditEventHelper.jsonPointerToDotNotation;
import static org.forgerock.audit.util.JsonSchemaUtils.generateJsonPointers;
import static org.forgerock.audit.util.JsonValueUtils.extractValueAsString;

/**
 * Responsible for formatting an {@link AuditEvent}'s JSON representation as an RFC-5424 compliant Syslog message.
 * <p>
 * Objects are immutable and can therefore be freely shared across threads without synchronization.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5424">RFC-5424</a>
 */
class SyslogFormatter {

    private static final Logger logger = LoggerFactory.getLogger(SyslogFormatter.class);
    private static final String SYSLOG_SPEC_VERSION = "1";
    private static final String NIL_VALUE = "-";
    private final Map<String, StructuredDataFormatter> structuredDataFormatters;
    private final Map<String, SeverityFieldMapping> severityFieldMappings;
    private final Facility facility;

    private final String HOST_NAME;
    private final String APP_NAME;
    private final String PROC_ID;

    /**
     * Format the provided <code>auditEvent</code> to a CEF message...
        */
    public String format(String topic, JsonValue auditEvent) {

        Reject.ifFalse(canFormat(topic), "Unknown event topic");

        final Severity FR_SEVERITY = getSeverityLevel(topic, auditEvent); //rj? kCase
        final String FR_PRIORITY = String.valueOf(calculatePriorityValue(facility, FR_SEVERITY));
        final String TIME_STAMP = auditEvent.get(TIMESTAMP).asString();
        final String MSG_ID = auditEvent.get(EVENT_NAME).asString();
        final String STRUCTURED_DATA = structuredDataFormatters.get(topic).format(auditEvent);

        final String CEF_VERSION = "CEF:0";
        final String FR_VENDOR = "ForgeRock Inc";
        final String FR_PRODUCT = "Trust Partner Network";
        final String FR_VERSION = "1.0";
        final String FR_CODE = "100";
        final String FR_TYPE = "forgerock cef";
        final String FR_MSG_CODE = "1";

        String pattern = "dd MMMMM HH:mm:ss";
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern, new Locale("us", "EN"));
        String date = simpleDateFormat.format(new Date());

        InetAddress inetAddress = null;
        try {
            inetAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        //CEF:Version|Device Vendor|Device Product|Version|Signature ID|Name|Severity|Extensions
        String parsedCEF = date + " " + CEF_VERSION + "|" + FR_VENDOR + "|" + APP_NAME + "|" + FR_VERSION + "|"  + FR_PRIORITY + "|" + FR_TYPE + "|" + FR_SEVERITY + "|"
                + " src=" + inetAddress.getHostAddress()    // a CEF standard field
                + " act=" + MSG_ID                           // a CEF standard field
                + " targetType=" + inetAddress.getHostName()       // HOSTNAME
                //+ " cn1=" + APP_NAME                         // APP-NAME
                + " procId=" + PROC_ID                       // PROCID
                //+ " fr_priority=" + PRIORITY
                + " msg=" + STRUCTURED_DATA;     // rj? STRUCTURED-DATA (worried :: will mess things up
        System.out.println(parsedCEF);
        return parsedCEF;
    }

    /**
     * Construct a new SyslogFormatter.
     *
     * @param eventTopicsMetaData   Schemas and additional meta-data for known audit event topics.
     * @param config                Configuration options.
     * @param localHostNameProvider Strategy for obtaining hostname of current server.
     * @param productInfoProvider   Strategy for obtaining name of the hosting application.
     */
    SyslogFormatter(EventTopicsMetaData eventTopicsMetaData, SentinelAuditEventHandlerConfiguration config,
                    LocalHostNameProvider localHostNameProvider, ProductInfoProvider productInfoProvider) {

        Reject.ifNull(localHostNameProvider, "LocalHostNameProvider must not be null");

        this.HOST_NAME = getLocalHostName(localHostNameProvider);
        this.PROC_ID = String.valueOf(SyslogFormatter.class.hashCode());
        this.APP_NAME = getProductName(productInfoProvider);
        this.facility = config.getFacility();
        this.severityFieldMappings =
                createSeverityFieldMappings(config.getSeverityFieldMappings(), eventTopicsMetaData);
        this.structuredDataFormatters = Collections.unmodifiableMap(
                createStructuredDataFormatters(APP_NAME, eventTopicsMetaData));
    }



    /**
     * Returns <code>true</code> if this formatter has been configured to handle events of the specified topic.
     *
     * @param topic The topic of the <code>auditEvent</code> to be formatted.
     * @return <code>true</code> if this formatter has been configured to handle events of the specified topic;
     * <code>false</code> otherwise.
     */
    public boolean canFormat(String topic) {
        return structuredDataFormatters.containsKey(topic);
    }

    private Map<String, SeverityFieldMapping> createSeverityFieldMappings(
            List<SeverityFieldMapping> mappings, EventTopicsMetaData eventTopicsMetaData) {

        Map<String, SeverityFieldMapping> results = new HashMap<>(mappings.size());
        for (SeverityFieldMapping mapping : mappings) {

            if (results.containsKey(mapping.getTopic())) {
                logger.warn("Multiple Syslog severity field mappings defined for {} topic", mapping.getTopic());
                continue;
            }

            if (!eventTopicsMetaData.containsTopic(mapping.getTopic())) {
                logger.warn("Syslog severity field mapping defined for unknown topic {}", mapping.getTopic());
                continue;
            }

            JsonValue auditEventMetaData = eventTopicsMetaData.getSchema(mapping.getTopic());
            JsonValue auditEventSchema;
            try {
                auditEventSchema = getAuditEventSchema(auditEventMetaData);
            } catch (ResourceException e) {
                logger.warn(e.getMessage());
                continue;
            }
            Set<String> topicFieldPointers = generateJsonPointers(auditEventSchema);
            String mappedField = mapping.getField();
            if (mappedField != null && !mappedField.startsWith("/")) {
                mappedField = "/" + mappedField;
            }
            if (!topicFieldPointers.contains(mappedField)) {
                logger.warn("Syslog severity field mapping for topic {} references unknown field {}",
                        mapping.getTopic(), mapping.getField());
                continue;
            }

            results.put(mapping.getTopic(), mapping);
        }
        return results;
    }

    private Map<String, StructuredDataFormatter> createStructuredDataFormatters(
            String productName,
            EventTopicsMetaData eventTopicsMetaData) {

        final Map<String, StructuredDataFormatter> results = new HashMap<>();
        for (String topic : eventTopicsMetaData.getTopics()) {
            JsonValue schema = eventTopicsMetaData.getSchema(topic);
            results.put(topic, new StructuredDataFormatter(productName, topic, schema));
        }
        return results;
    }

    private Severity getSeverityLevel(String topic, JsonValue auditEvent) {
        if (severityFieldMappings.containsKey(topic)) {
            SeverityFieldMapping severityFieldMapping = severityFieldMappings.get(topic);
            String severityField = severityFieldMapping.getField();
            if (severityField != null && !severityField.startsWith("/")) {
                severityField = "/" + severityField;
            }
            JsonValue jsonValue = auditEvent.get(new JsonPointer(severityField));
            String severityValue = jsonValue == null ? null : jsonValue.asString();
            if (severityValue == null) {
                logger.debug("{} value not set; defaulting to INFORMATIONAL Syslog SEVERITY level", severityField);
            } else {
                try {
                    return Severity.valueOf(severityValue);
                } catch (IllegalArgumentException ex) {
                    logger.debug("{} is not a valid Syslog SEVERITY level; defaulting to INFORMATIONAL", severityValue);
                }
            }
        }
        // if no mapping was defined or the value wasn't a valid severity, default to INFORMATIONAL
        return Severity.INFORMATIONAL;
    }

    /**
     * Calculates the Syslog message PRI value.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5424#section-6.2.1">RFC-5424 section 6.2.1</a>
     */
    private int calculatePriorityValue(Facility facility, Severity severityLevel) {
        return (facility.getCode() * 8) + severityLevel.getCode();
    }

    /**
     * Calculates the Syslog message HOSTNAME value.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5424#section-6.2.4">RFC-5424 section 6.2.4</a>
     */
    private String getLocalHostName(LocalHostNameProvider localHostNameProvider) {
        String localHostName = localHostNameProvider.getLocalHostName();
        return localHostName != null ? localHostName : NIL_VALUE;
    }

    /**
     * Calculates the Syslog message APP-NAME value.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5424#section-6.2.5">RFC-5424 section 6.2.5</a>
     */
    private String getProductName(ProductInfoProvider productInfoProvider) {
        String productName = productInfoProvider.getProductName();
        return productName != null ? productName.replace(" ", "-") : NIL_VALUE;
    }

    /**
     * Responsible for formatting an {@link AuditEvent}'s JSON representation as an RFC-5424 compliant SD-ELEMENT.
     * <p>
     * Objects are immutable and can therefore be freely shared across threads without synchronization.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5424#section-6.3">RFC-5424 section 6.3</a>
     */
    private static class StructuredDataFormatter {

        private static final String FORGEROCK_IANA_ENTERPRISE_ID = "36733";
        /**
         * The set of audit event fields that should not be copied to structured-data.
         */
        private static final Set<String> IGNORED_FIELDS = unmodifiableSet(
                new HashSet<>(asList("_id", TIMESTAMP, EVENT_NAME)));

        private final String id;
        private final Set<String> fieldNames;

        /**
         * Construct a new StructuredDataFormatter.
         *
         * @param productName        Name of the ForgeRock product in which the {@link AuditService}
         *                           is executing; the SD-ID of each STRUCTURED-DATA element is derived from the
         *                           <code>productName</code> and <code>topic</code>.
         * @param topic              Coarse-grained categorisation of the types of audit events that this formatter handles;
         *                           the SD-ID of each STRUCTURED-DATA element is derived from the <code>productName</code>
         *                           and <code>topic</code>.
         * @param auditEventMetaData Schema and additional meta-data for the audit event topic.
         */
        StructuredDataFormatter(String productName, String topic, JsonValue auditEventMetaData) {

            Reject.ifNull(productName, "Product name required.");
            Reject.ifNull(topic, "Audit event topic name required.");

            JsonValue auditEventSchema;
            try {
                auditEventSchema = getAuditEventSchema(auditEventMetaData);
            } catch (ResourceException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }

            id = topic + "." + productName + "@" + FORGEROCK_IANA_ENTERPRISE_ID;
            fieldNames = unmodifiableSet(generateJsonPointers(auditEventSchema));
        }

        /**
         * Translate the provided <code>auditEvent</code> to an RFC-5424 compliant SD-ELEMENT.
         *
         * @param auditEvent The audit event to be formatted.
         * @return an RFC-5424 compliant SD-ELEMENT.
         */
        public String format(JsonValue auditEvent) {

            StringBuilder sd = new StringBuilder();

            sd.append("[");
            sd.append(id);
            for (String fieldName : fieldNames) {
                String formattedName = formatParamName(fieldName);
                if (IGNORED_FIELDS.contains(formattedName)) {
                    continue;
                }
                sd.append(" ");
                sd.append(formattedName);
                sd.append("=\"");
                sd.append(formatParamValue(extractValueAsString(auditEvent, fieldName)));
                sd.append("\"");
            }
            sd.append("]");

            return sd.toString();
        }

        private String formatParamName(String name) {
            return jsonPointerToDotNotation(name);
        }

        private String formatParamValue(String value) {
            if (value == null) {
                return "";
            } else {
                return value.replaceAll("[\\\\\"\\]]", "\\\\$0");
            }
        }
    }
}

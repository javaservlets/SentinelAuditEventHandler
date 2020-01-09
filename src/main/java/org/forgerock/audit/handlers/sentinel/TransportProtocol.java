/*
 * Copyright 2015-2017 ForgeRock AS. All Rights Reserved
 *
 * Use of this code requires a commercial software license with ForgeRock AS.
 * or with one of its affiliates. All use shall be exclusively subject
 * to such license between the licensee and ForgeRock AS.
 */
package org.forgerock.audit.handlers.sentinel;

import org.forgerock.audit.handlers.sentinel.SentinelAuditEventHandlerConfiguration.EventBufferingConfiguration;

import java.net.InetSocketAddress;

/**
 * Transport protocol over which Syslog messages should be published.
 */
public enum TransportProtocol {

    /**
     * Publish Syslog messages over TCP.
     */
    TCP {
        @Override
        SyslogConnection getSyslogConnection(InetSocketAddress socket, SentinelAuditEventHandlerConfiguration config) {
            return new TcpSyslogConnection(socket, config.getConnectTimeout());
        }
    },

    /**
     * Publish Syslog messages over UDP.
     */
    UDP {
        @Override
        SyslogConnection getSyslogConnection(InetSocketAddress socket, SentinelAuditEventHandlerConfiguration config) {
            return new UdpSyslogConnection(socket);
        }
    };

    /**
     * Get the publisher for the given configuration.
     * @param socket The socket.
     * @param config The configuration.
     * @return The publisher.
     */
    public SyslogPublisher getPublisher(InetSocketAddress socket, SentinelAuditEventHandlerConfiguration config) {
        SyslogConnection syslogConnection = getSyslogConnection(socket, config);
        EventBufferingConfiguration buffering = config.getBuffering();
        if (buffering.isEnabled()) {
            return new AsynchronousSyslogPublisher("SyslogHandler", syslogConnection, buffering.getMaxSize());
        } else {
            return new SynchronousSyslogPublisher(syslogConnection);
        }
    }

    abstract SyslogConnection getSyslogConnection(InetSocketAddress socket,
            SentinelAuditEventHandlerConfiguration config);

}

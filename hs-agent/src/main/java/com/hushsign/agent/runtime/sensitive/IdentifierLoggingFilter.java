package com.hushsign.agent.runtime.sensitive;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Keeps the raw log file closed unless {@code HS_AGENT_LOG_IDENTIFIERS=raw} (P7-S12).
 *
 * <p>A filter rather than conditional configuration, because logback's {@code <if>} needs
 * Janino and this needs nothing: the appender is always there, and it is simply not fed. The
 * file that exists and stays empty is easier to explain than one that appears only in some
 * deployments.
 */
public final class IdentifierLoggingFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return IdentifierLogging.rawEnabled() ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}

package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.FlowContext;

/**
 * Interface for all log fields that can be configured in ActivityLogger.
 * Provides the foundation for different types of log fields including standard fields,
 * header fields, and computed fields.
 */
public interface LogField {
    
    /**
     * Gets the name of this log field.
     * @return the field name
     */
    String getName();
    
    /**
     * Gets a description of this log field.
     * @return the field description
     */
    String getDescription();
    
    /**
     * Extracts the value for this field from the given request/response context.
     * @param flowContext the flow context
     * @param request the HTTP request
     * @param response the HTTP response
     * @param duration the request processing duration in milliseconds
     * @return the extracted value, or "-" if not available
     */
    String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response, long duration);
}
package org.littleshoot.proxy.extras;

import java.util.HashMap;
import java.util.Map;

/**
 * A general purpose information map to be used for transferring data between filters used for a connection.
 */
public class PreservedInformation {
    /**
     * Information Map.
     */
    public Map<String, Object> informationMap = new HashMap<>();
}

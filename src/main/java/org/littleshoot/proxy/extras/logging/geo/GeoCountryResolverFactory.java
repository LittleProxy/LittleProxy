package org.littleshoot.proxy.extras.logging.geo;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeoCountryResolverFactory {

  private static final Logger LOG = LoggerFactory.getLogger(GeoCountryResolverFactory.class);

  public static final String PROVIDER_PROPERTY = "littleproxy.logging.geo.provider";
  public static final String MMDB_PATH_PROPERTY = "littleproxy.logging.geo.mmdb.path";

  private static volatile GeoCountryResolver resolver;

  private GeoCountryResolverFactory() {}

  /**
   * Closes the resolver and releases any resources. This should be called during application
   * shutdown to properly clean up resources (e.g., MMDB database connections).
   */
  public static void closeResolver() {
    GeoCountryResolver current;
    synchronized (GeoCountryResolverFactory.class) {
      current = resolver;
      resolver = null;
    }
    if (current instanceof AutoCloseable) {
      try {
        ((AutoCloseable) current).close();
      } catch (Exception e) {
        LOG.warn("Error closing geo resolver", e);
      }
    }
  }

  public static GeoCountryResolver getResolver() {
    GeoCountryResolver current = resolver;
    if (current != null) {
      return current;
    }

    synchronized (GeoCountryResolverFactory.class) {
      if (resolver == null) {
        resolver = createResolver();
      }
      return resolver;
    }
  }

  static GeoCountryResolver createResolver() {
    String provider = System.getProperty(PROVIDER_PROPERTY, "local").trim().toLowerCase();
    if (provider.isEmpty() || "local".equals(provider)) {
      return new LocalGeoCountryResolver();
    }

    if ("mmdb".equals(provider)) {
      String path = System.getProperty(MMDB_PATH_PROPERTY, "").trim();
      if (path.isEmpty()) {
        LOG.warn(
            "Geo provider set to mmdb but no database path configured ({}). Falling back to local resolver.",
            MMDB_PATH_PROPERTY);
        return new LocalGeoCountryResolver();
      }

      try {
        Path databasePath = Paths.get(path);
        return new MmdbGeoCountryResolver(databasePath);
      } catch (Throwable t) {
        LOG.warn(
            "Failed to initialize MMDB resolver from '{}'. Falling back to local resolver.",
            path,
            t);
        return new LocalGeoCountryResolver();
      }
    }

    LOG.warn(
        "Unknown geo provider '{}'. Supported values: local, mmdb. Falling back to local resolver.",
        provider);
    return new LocalGeoCountryResolver();
  }
}

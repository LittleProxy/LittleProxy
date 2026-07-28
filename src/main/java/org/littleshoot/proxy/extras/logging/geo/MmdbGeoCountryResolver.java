package org.littleshoot.proxy.extras.logging.geo;

import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class MmdbGeoCountryResolver implements GeoCountryResolver, AutoCloseable {

  private final Reader reader;

  public MmdbGeoCountryResolver(Path databasePath) throws IOException {
    if (databasePath == null) {
      throw new IllegalArgumentException("databasePath must not be null");
    }
    if (!Files.exists(databasePath)) {
      throw new IOException("MMDB database not found: " + databasePath);
    }
    this.reader = new Reader(databasePath.toFile());
  }

  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
    }
  }

  @Override
  public String resolveCountryCode(InetAddress ip) {
    if (IpAddressClassifier.isLocalOrPrivate(ip)) {
      return LOCAL;
    }

    if (ip == null) {
      return UNKNOWN;
    }

    try {
      Map<String, Object> record = reader.get(ip, Map.class);
      if (record == null) {
        return UNKNOWN;
      }

      String countryCode = extractIsoCode(record);
      if (countryCode == null || countryCode.isEmpty()) {
        return UNKNOWN;
      }

      return countryCode.toUpperCase(Locale.ROOT);
    } catch (Exception e) {
      return UNKNOWN;
    }
  }

  private String extractIsoCode(Map<String, Object> record) {
    String countryCode = extractIsoCodeFromSection(record.get("country"));
    if (countryCode != null) {
      return countryCode;
    }

    countryCode = extractIsoCodeFromSection(record.get("registered_country"));
    if (countryCode != null) {
      return countryCode;
    }

    return extractIsoCodeFromSection(record.get("represented_country"));
  }

  @SuppressWarnings("unchecked")
  private String extractIsoCodeFromSection(Object section) {
    if (!(section instanceof Map)) {
      return null;
    }
    Object isoCode = ((Map<String, Object>) section).get("iso_code");
    return isoCode != null ? String.valueOf(isoCode) : null;
  }
}

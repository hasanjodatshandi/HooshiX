package com.sajtech.authorization.configuration;

import com.sajtech.authorization.application.model.PermissionModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class PermissionCatalogLoader {
  private static final Pattern KEY =
      Pattern.compile(
          "  - key: ((?:[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+)|platform\\.legal_hold\\.manage)");

  public Catalog load() {
    InputStream resource =
        getClass().getResourceAsStream("/permission-catalog/permission-catalog.yaml");
    if (resource == null) throw new IllegalStateException("Permission catalog resource is missing");
    try (InputStream in = resource;
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      List<String> l = reader.lines().toList();
      if (l.size() < 5
          || !"version: 1".equals(l.get(0))
          || !"permissions:".equals(l.get(1))
          || (l.size() - 2) % 3 != 0) throw invalid();
      List<PermissionModel> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 2; i < l.size(); i += 3) {
        Matcher m = KEY.matcher(l.get(i));
        if (!m.matches()
            || !l.get(i + 1).matches("    scope: (TENANT|PLATFORM)")
            || !l.get(i + 2).matches("    lifecycle: (ACTIVE|DEPRECATED|RETIRED)")) throw invalid();
        String k = m.group(1);
        if (!seen.add(k) || k.length() > 128) throw invalid();
        out.add(new PermissionModel(k, l.get(i + 1).substring(11), l.get(i + 2).substring(15)));
      }
      return new Catalog(1, out);
    } catch (IOException e) {
      throw new IllegalStateException("Permission catalog cannot be loaded", e);
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Permission catalog is invalid");
  }

  public record Catalog(int version, List<PermissionModel> permissions) {
    public Catalog {
      permissions = List.copyOf(permissions);
    }

    @Override
    public List<PermissionModel> permissions() {
      return List.copyOf(permissions);
    }
  }
}

package com.sajtech.identity.application.tenant.service;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class TenantIntentEncoder {
  public byte[] encode(String operation, String... values) {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(b);
      o.writeUTF("hooshix-identity-tenant-intent-v1");
      write(o, operation);
      for (String v : values) write(o, v);
      o.flush();
      return b.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to encode tenant intent", e);
    }
  }

  private static void write(DataOutputStream o, String v) throws IOException {
    if (v == null) {
      o.writeInt(-1);
      return;
    }
    byte[] x = v.getBytes(StandardCharsets.UTF_8);
    o.writeInt(x.length);
    o.write(x);
  }
}

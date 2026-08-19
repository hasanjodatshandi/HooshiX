package com.sajtech.identity.application.registration.service;

import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import com.sajtech.identity.domain.registration.valueobject.RegistrationProfile;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class FingerprintMaterialEncoder {
  public byte[] register(
      CanonicalContact contact,
      String password,
      RegistrationLocale locale,
      RegistrationProfile profile) {
    return encode(
        "REGISTER",
        contact.channel().name(),
        contact.canonicalValue(),
        password,
        locale.canonical(),
        profile.firstName(),
        profile.lastName(),
        profile.fatherName());
  }

  public byte[] resend(CanonicalContact contact) {
    return encode(
        "RESEND_REGISTRATION_VERIFICATION", contact.channel().name(), contact.canonicalValue());
  }

  public byte[] confirm(CanonicalContact contact, String code) {
    return encode("CONFIRM_REGISTRATION", contact.channel().name(), contact.canonicalValue(), code);
  }

  private static byte[] encode(String... values) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeUTF("hooshix-identity-intent-v1");
      for (String value : values) {
        if (value == null) {
          output.writeInt(-1);
        } else {
          byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
          output.writeInt(encoded.length);
          output.write(encoded);
        }
      }
      output.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode bounded fingerprint material", impossible);
    }
  }
}

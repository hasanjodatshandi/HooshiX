package com.sajtech.identity.application.profile.service;

import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import com.sajtech.identity.domain.registration.valueobject.RegistrationProfile;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ProfileFingerprintEncoder {
  public byte[] update(RegistrationProfile profile) {
    return encode("UPDATE_PROFILE", profile.firstName(), profile.lastName(), profile.fatherName());
  }

  public byte[] add(CanonicalContact contact, RegistrationLocale locale) {
    return encode(
        "ADD_CONTACT", contact.channel().name(), contact.canonicalValue(), locale.canonical());
  }

  public byte[] resend(UUID contactId) {
    return encode("RESEND_CONTACT_VERIFICATION", contactId.toString());
  }

  public byte[] verify(UUID contactId, String code) {
    return encode("VERIFY_CONTACT", contactId.toString(), code);
  }

  public byte[] primary(UUID contactId) {
    return encode("SET_PRIMARY_CONTACT", contactId.toString());
  }

  public byte[] remove(UUID contactId) {
    return encode("REMOVE_CONTACT", contactId.toString());
  }

  private static byte[] encode(String... values) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      output.writeUTF("hooshix-identity-profile-intent-v1");
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
      throw new IllegalStateException("Unable to encode bounded profile intent", impossible);
    }
  }
}

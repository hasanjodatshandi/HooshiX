package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.application.authentication.model.GeneratedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import java.util.List;

public interface SessionCredentialPort {
  String newSessionId();

  GeneratedRefreshCredential newRefreshCredential();

  List<RefreshDigest> digestCandidates(String encodedCredential);
}

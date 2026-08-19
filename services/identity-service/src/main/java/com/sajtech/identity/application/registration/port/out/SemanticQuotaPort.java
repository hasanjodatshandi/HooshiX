package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.QuotaRequest;

public interface SemanticQuotaPort {
  void consume(QuotaRequest request);
}

package com.sajtech.identity.application.erasure.port.in;

import com.sajtech.identity.application.erasure.model.ErasureRequestView;

public interface ErasureCoordination {
  ErasureRequestView requestSelfErasure(RequestSelfErasureCommand command);
}

package com.sajtech.authorization.application.port.out;

import com.sajtech.authorization.application.model.ActorContext;

public interface AdminQuota {
  void acquire(ActorContext actor, int semanticCost);
}

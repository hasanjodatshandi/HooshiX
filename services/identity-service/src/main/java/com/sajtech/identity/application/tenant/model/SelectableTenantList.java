package com.sajtech.identity.application.tenant.model;

import java.util.List;
import java.util.UUID;

public record SelectableTenantList(List<SelectableTenant> tenants, UUID suggestedMembershipId) {
  public SelectableTenantList {
    tenants = List.copyOf(tenants);
  }
}

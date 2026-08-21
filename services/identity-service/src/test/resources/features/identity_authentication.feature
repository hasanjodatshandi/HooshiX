Feature: Identity local authentication session safety
  Local password authentication must establish only the authority that Identity currently owns.
  Refresh predecessor reuse must revoke the complete refresh family.

  Scenario: Successful local password authentication starts an onboarding session
    Given an active verified local account with a valid password
    When local password authentication is requested
    Then an authenticated onboarding session is created
    And the refresh idle lifetime is 7 days and the absolute lifetime is 30 days
    And ordinary audience token issuance requires tenant selection

  Scenario: Reusing a rotated refresh credential revokes the family
    Given an active onboarding refresh family with a rotated predecessor
    When the rotated predecessor is used to refresh the session
    Then refresh is rejected as credential reuse
    And the complete refresh family is revoked for reuse
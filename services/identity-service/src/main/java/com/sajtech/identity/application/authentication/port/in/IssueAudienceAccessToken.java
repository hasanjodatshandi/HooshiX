package com.sajtech.identity.application.authentication.port.in;

import com.sajtech.identity.application.authentication.model.IssueAudienceAccessTokenCommand;
import com.sajtech.identity.application.authentication.model.SignedAccessToken;

public interface IssueAudienceAccessToken {
  SignedAccessToken issue(IssueAudienceAccessTokenCommand command);
}

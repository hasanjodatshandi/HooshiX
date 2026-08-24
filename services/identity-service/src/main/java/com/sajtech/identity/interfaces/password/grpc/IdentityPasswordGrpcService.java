package com.sajtech.identity.interfaces.password.grpc;

import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.contract.v1.IdentityPasswordServiceGrpc;
import com.sajtech.identity.contract.v1.ChangePasswordRequest;
import com.sajtech.identity.contract.v1.ChangePasswordResponse;
import com.sajtech.identity.contract.v1.RequestPasswordRecoveryRequest;
import com.sajtech.identity.contract.v1.RequestPasswordRecoveryResponse;
import com.sajtech.identity.contract.v1.ConfirmPasswordRecoveryRequest;
import com.sajtech.identity.contract.v1.ConfirmPasswordRecoveryResponse;
import io.grpc.stub.StreamObserver;

public final class IdentityPasswordGrpcService extends IdentityPasswordServiceGrpc.IdentityPasswordServiceImplBase {
  private final ChangePassword changePassword;
  private final RequestPasswordRecovery requestRecovery;
  private final ConfirmPasswordRecovery confirmRecovery;

  public IdentityPasswordGrpcService(ChangePassword c, RequestPasswordRecovery r, ConfirmPasswordRecovery f) {
    this.changePassword=c; this.requestRecovery=r; this.confirmRecovery=f;
  }
  public void changePassword(ChangePasswordRequest r, StreamObserver<ChangePasswordResponse> o) {
    changePassword.change(new ChangePasswordCommand(r.getRefreshCredential(), r.getCurrentPassword(), r.getNewPassword()));
    o.onNext(ChangePasswordResponse.newBuilder().setChanged(true).build()); o.onCompleted();
  }
  public void requestPasswordRecovery(RequestPasswordRecoveryRequest r, StreamObserver<RequestPasswordRecoveryResponse> o) {
    requestRecovery.request(new RequestPasswordRecoveryCommand(r.getPrimaryContact()));
    o.onNext(RequestPasswordRecoveryResponse.newBuilder().setAccepted(true).build()); o.onCompleted();
  }
  public void confirmPasswordRecovery(ConfirmPasswordRecoveryRequest r, StreamObserver<ConfirmPasswordRecoveryResponse> o) {
    confirmRecovery.confirm(new ConfirmPasswordRecoveryCommand(r.getPrimaryContact(), r.getCode(), r.getNewPassword()));
    o.onNext(ConfirmPasswordRecoveryResponse.newBuilder().setChanged(true).build()); o.onCompleted();
  }
}

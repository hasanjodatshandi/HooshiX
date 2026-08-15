package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

public final class DatasetBuilderCli {
  private DatasetBuilderCli() {}

  public static void main(String[] args) {
    DatasetBuildRequest request = DatasetBuildRequest.fromArgs(args);
    new DatasetBuilder().build(request);
  }
}

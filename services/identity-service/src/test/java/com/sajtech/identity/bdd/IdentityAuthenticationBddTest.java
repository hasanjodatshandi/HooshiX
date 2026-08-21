package com.sajtech.identity.bdd;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

import org.junit.jupiter.api.Tag;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@Tag("bdd")
@IncludeEngines("cucumber")
@SelectClasspathResource("features/identity_authentication.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.sajtech.identity.bdd")
class IdentityAuthenticationBddTest {}

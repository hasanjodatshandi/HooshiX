// Copyright 2026 HooshiX contributors
// SPDX-License-Identifier: Apache-2.0
package coraza

import (
	"fmt"
	"strings"
	"testing"

	waf "github.com/corazawaf/coraza/v3"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"go.uber.org/zap/zaptest/observer"
)

func TestHooshixRuleLogsContainOnlyFixedMetadata(t *testing.T) {
	for severity := 0; severity <= 7; severity++ {
		t.Run(fmt.Sprint(severity), func(t *testing.T) {
			core, logs := observer.New(zapcore.DebugLevel)
			engine, err := waf.NewWAF(waf.NewWAFConfig().
				WithErrorCallback(newErrorCb(zap.New(core))).
				WithDirectives(fmt.Sprintf(`
SecRuleEngine On
SecAuditEngine Off
SecRule REQUEST_COOKIES:canary "@streq HOOSHIX_PII_CANARY" "id:1000020,phase:1,deny,status:403,log,severity:%d,msg:'%%{MATCHED_VAR}',logdata:'%%{MATCHED_VAR}'"
`, severity)))
			if err != nil {
				t.Fatal("cannot construct test WAF")
			}
			tx := engine.NewTransaction()
			defer tx.Close()
			tx.ProcessConnection("127.0.0.1", 1234, "127.0.0.1", 8080)
			tx.ProcessURI("/safe-canary", "GET", "HTTP/1.1")
			tx.AddRequestHeader("Host", "localhost")
			tx.AddRequestHeader("Cookie", "canary=HOOSHIX_PII_CANARY")
			interruption := tx.ProcessRequestHeaders()
			tx.ProcessLogging()
			if interruption == nil || interruption.Status != 403 {
				t.Fatal("controlled rule must still block")
			}
			entries := logs.All()
			if len(entries) != 1 {
				t.Fatalf("want one event, got %d", len(entries))
			}
			event := entries[0]
			if event.Message != "waf_rule_match" {
				t.Fatal("non-allowlisted log message")
			}
			fields := event.ContextMap()
			if len(fields) != 2 || fields["rule_id"] != int64(1000020) || fields["severity"] != int64(severity) {
				t.Fatal("non-allowlisted or missing rule metadata")
			}
		})
	}
}

func TestHooshixOpaqueCookieExclusionIsNarrow(t *testing.T) {
	// A synthetic locator, never an issued credential or a file-access request.
	value := "test.wp-config_" + strings.Repeat("a", 33)
	for _, tc := range []struct {
		name, cookie, uri, extra string
		blocked                  bool
	}{
		{"session", "__Host-sajtech-session=" + value, "/", "", false},
		{"preauth", "__Host-sajtech-preauth=" + value, "/", "", false},
		{"wrong-length", "__Host-sajtech-session=" + value + "a", "/", "", true},
		{"other-cookie", "ordinary=" + value, "/", "", true},
		{"argument", "__Host-sajtech-session=" + value, "/?label=" + value, "", true},
		{"json-body", "__Host-sajtech-session=" + value, "/", `SecRule REQUEST_HEADERS:Content-Type "@streq application/json" "id:1000022,phase:1,pass,nolog,ctl:requestBodyProcessor=JSON"`, true},
		{"duplicate", "__Host-sajtech-session=" + value + "; __Host-sajtech-session=" + value, "/", "", true},
		{"other-rule", "__Host-sajtech-session=" + value, "/", `SecRule REQUEST_COOKIES:__Host-sajtech-session "@rx ." "id:1000021,phase:2,deny,status:403,nolog"`, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			engine, err := waf.NewWAF(waf.NewWAFConfig().WithDirectives(`
SecRuleEngine On
SecAuditEngine Off
SecRequestBodyAccess On
Include /tmp/crs/crs-setup.conf.example
Include /tmp/opaque-cookie-exclusions.conf
Include /tmp/crs/rules/*.conf
` + tc.extra))
			if err != nil {
				t.Fatal("cannot construct CRS test WAF")
			}
			tx := engine.NewTransaction()
			defer tx.Close()
			tx.ProcessConnection("127.0.0.1", 1234, "127.0.0.1", 8080)
			tx.ProcessURI(tc.uri, "GET", "HTTP/1.1")
			tx.AddRequestHeader("Host", "localhost")
			tx.AddRequestHeader("User-Agent", "HooshiX synthetic contract test")
			tx.AddRequestHeader("Accept", "application/json")
			tx.AddRequestHeader("Cookie", tc.cookie)
			if tc.name == "json-body" {
				tx.AddRequestHeader("Content-Type", "application/json")
			}
			interruption := tx.ProcessRequestHeaders()
			if interruption == nil {
				if tc.name == "json-body" {
					if _, _, err = tx.WriteRequestBody([]byte(`{"label":"` + value + `"}`)); err != nil {
						t.Fatal("cannot buffer synthetic input")
					}
				}
				interruption, err = tx.ProcessRequestBody()
				if err != nil {
					t.Fatal("cannot process synthetic input")
				}
			}
			if (interruption != nil) != tc.blocked {
				t.Fatal("unexpected exclusion boundary")
			}
		})
	}
}

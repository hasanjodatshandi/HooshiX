# HooshiX Java Onboarding — مسیر یادگیری و تدریس خط‌به‌خط

> **این فایل یک learning track غیرنرماتیف است، نه مرجع معماری.** مرجعیت معماری/مهندسی دقیقاً دست `AGENTS.md` §1، `docs/engineering/current-only-documentation-policy.md` و `docs/adr/decision-register.md` است. این فایل هیچ قانون جدیدی وضع نمی‌کند؛ فقط **ترتیب و دامنه آموزش** را تعیین می‌کند. هر ادعای فنی در جلسات تدریس باید با بازکردن فایل واقعی و ذکر `path:line` تأیید شود، نه از حافظه این سند.

- **ایجاد:** 2026-10-15 روی `HEAD = ccbdbd69`، branch `stage10-parspack-private-probe`
- **مالک:** جلسه تدریس (assistant) + کاربر؛ پیشرفت فقط در §7 این فایل ثبت می‌شود

---

## ۱. پروفایل یادگیرنده (چرا این پلان این شکلی است)

- برنامه‌نویس ارشد **.NET** با سال‌ها تجربه عملی؛ PhD؛ سابقه **Node.js**، **Heroku**، **UI/Frontend** و Backend.
- حدود **۱ سال Java** کار شده، ولی **۵–۶ سال پیش** و عملاً بدون حضور ذهن. یعنی: مفاهیم OO/enterprise در ذهن هست، *نسخه مدرن جاوا و اکوسیستم ۲۰۲۶* هدف یادگیری است.
- هدف دوگانه: **(a)** Java مدرن + Spring Boot مدرن یاد بگیرم، **(b)** روی همین پروژه **onboard** شوم: نقشه کلی، جریان داده، لایه‌ها، تکنولوژی‌ها و کد فایل‌به‌فایل.
- خواستِ صریح ترتیب تدریس: **از پایین‌ترین لایه** (Entity / ORM / Database) **تا بالاترین** (UI).
- **محدودیت زمان:** جلسات کوتاه و فشرده. سطح پیش‌فرض = «کافی برای فهم و حفظ کردن ساختار». جزئیات عمیق فقط **وقتی کاربر بخواهد**.

## ۲. Rules of Engagement (قواعد هر جلسه تدریس)

1. **Timebox:** هر Stage یک جلسه ۴۵–۹۰ دقیقه‌ای. اگر تمام نشد، ادامه در جلسه بعد با ثبت در §7.
2. **قالب ثابت تدریس برای هر فایل:**
   - نقش فایل در یک خط؛
   - بلوک‌های کد مهم + `path:line`؛
   - **ممنوعیت قیاس با .NET:** هر مفهوم را با زبان خودِ Java/JVM توضیح بده؛ مقایسه با دات‌نت **نکن** (درخواست صریح کاربر در ۲۰۲۶-۱۰-۱۵). پیش‌زمینه دات‌نتی فقط یعنی «اصول مقدماتی برنامه‌نویسی را دوباره توضیح نده»، نه اینکه جدول قیاس بسازی؛
   - ارتباط با **قوانین پروژه/ADR** که آن کد را مجبور به این شکل کرده؛
   - یک **سؤال جمع‌بندی** برای اطمینان از فهم.
3. **زبان:** فارسی، با اصطلاحات فنی و path/کد/commands به انگلیسیِ دقیق (طبق `docs/engineering/agent-communication-and-reporting.md` §1.10).
4. **قالب‌بندی ضدبه‌هم‌ریختگی BIDI (تقدیرِ نمایشِ کاربر؛ اجباری در هر پاسخ چت):** چون فارسی RTL و لاتین LTR است، ترکیب درون‌خطیِ آن‌ها در چت به‌هم می‌ریزد. پس:
   - هیچ identifier/path/number لاتین را وسط جمله فارسی نگذار؛ جمله فارسی را با «:» تمام کن و توکن لاتین را در **خط مستقل** (ترجیحاً داخل backtick) بیاور؛
   - داخل خطِ فارسی فقط نام‌های خیلی کوتاه مثل `jOOQ` یا `Flyway`، و آن هم داخل backtick؛
   - **جدول ترکیبی نساز** (ستون‌های فارسی+لاتین بدترین حالت‌اند)؛ از لیستِ خط‌به‌خط استفاده کن؛
   - پرانتز/کروشه/اسلش را دور توکن لاتین نپیچ (`docs/a/b.md` را در خط خودش بنویس)؛
   - شماره‌ها را با رقم فارسی بنویس مگر داخل کد/پورت/version.
5. **این مسیر تدریس هیچ فایل کدی را تغییر نمی‌دهد.** تنها فایلی که نوشته می‌شود همین پلان است (§7 و تیک Stageها). باگ/boilerplate/drift دیدی، در §8 یادداشت کن و **بدون درخواست کاربر اصلاح نکن** (`AGENTS.md` §14: no unrelated refactoring).
6. **گزارش پایانی هر Stage** با فیلدهای اجباری `AGENTS.md` §16 (`Outcome/Remaining work/Continuation action/Retryable/Human action required`).
7. **هیچ ادعای «کار می‌کند/تست پاس شد» بدون اجرای واقعی.** اگر لازم شد کد اجرا/تست شود، اول ذکر شود.

## ۳._snapshot تأییدشده پروژه (ccbdbd69)

### ۳.۱ ماژول‌ها و حجم

| Path | نقش | فایل Java/Kotlin | خط |
|---|---|---|---|
| `services/identity-service/` | احراز هویت، Registration، MFA، Tenant، Profile/Password، Erasure | 333 | 30,688 |
| `services/notification-service/` | قالب‌ها، پذیرش durable، تحویل Email/SMS | 132 | 8,811 |
| `services/web-bff/` | تنها EdgeHTTP برای مرورگر (BFF): session/cookie/OIDC/JWT | 98 | 12,211 |
| `services/conversation-service/` | مکالمه + اجرای مدل + budget/idempotency | 95 | 10,797 |
| `services/authorization-service/` | `CheckPermission` آنلاین، بدون کش | 65 | 7,537 |
| `services/compromised-password-service/` | دامنه SQLite HIBP SHA-1 (ADR-0040) | 38 | 3,337 |
| `contracts/protobuf-contracts/` | package نسخه‌دار proto/grpc contracts (ADR-0052) | 3 | 429 |
| `apps/web-frontend/` | SPA مرورگر | 129 `.ts/.tsx` | — |

### ۳.۲ Stack (از `build.gradle.kts` هر سرویس خوانده شد)

- **Java 25** toolchain (`java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`)، مدیریت با `.sdkmanrc` و `docs/technology/local-development-baseline.md`.
- **Spring Boot 4.1.0** + BOM، **Gradle Kotlin DSL** با `dependencyLocking { lockAllConfigurations() }`.
- **Persistence:** **jOOQ** (`spring-boot-starter-jooq`) + `spring-boot-starter-jdbc` — در کدِ فعلی هیچ JPA/Hibernate نیست (`jakarta.persistence` → صفر تطابق در `services/*/src/main`). نکته: `docs/technology/technology-baseline.md` §1 سطری دارد با عنوان «Spring Data JPA/Hibernate or jOOQ by service responsibility»، یعنی JPA *ممنوع* نیست؛ ولی تصمیم جاری هر سرویسِ پیاده‌شده jOOQ/JDBC است و Notification صریحاً «jOOQ/JDBC, no JPA» است. Migration فقط **Flyway** (`spring-boot-starter-flyway`, `flyway-database-postgresql`)؛ PostgreSQL با **forced RLS** و نقش `NOSUPERUSER NOBYPASSRLS`.
- **RPC:** **gRPC 1.83.1** + protobuf (`com.google.protobuf` plugin, `protoc 4.34.2`, `protoc-gen-grpc-java`)؛ contracts از artifact `com.sajtech.hooshix:protobuf-contracts:1.9.1`.
- **Async messaging:** `spring-boot-starter-kafka` با Transactional Outbox/Inbox.
- **Cache/quota:** **Redis** با **Lettuce** (`io.lettuce:lettuce-core`) در BFF/services.
- **Observability (ADR-0044, Day-One):** `spring-boot-starter-actuator` + Micrometer/Prometheus + OpenTelemetry + JSON structured logging.
- **Quality gates:** Spotless (google-java-format 1.36.1)، **SpotBugs**، **JaCoCo** با آستانه LINE/BRANCH، **ArchUnit** (`architectureTest`)، تفکیک `test` / `integrationTest` / `architectureTest` با JUnit tags.
- **Frontend:** React 19.2.7 + TypeScript + Vite + Tailwind 4 + Radix + `class-variance-authority`، تست: Vitest + Playwright (a11y با axe)، client تولیدشده از **OpenAPI** (`scripts/generate-openapi-client.sh`)، `scripts/check-ui-architecture.mjs`.
- **Platform:** Kubernetes/K3s، **Helm**، **Istio Ambient** mTLS، **Kyverno** CEL admission، **Traefik + WAF**، OpenBao، CloudNativePG/Barman (طبق `infrastructure/` و `docs/architecture/runtime-and-deployment.md`).

### ۳.۳ جریان درخواست (اسکلت ذهنی که در Stage 0 کامل می‌کنیم)

```text
Browser (React SPA, apps/web-frontend)
  → HTTPS + Cookie/session + CSRF
  → web-bff  (interfaces/http/*.java  ← تنها @RestControllerها در کل بک‌اند)
  → gRPC client (infrastructure/client)
  → backend service (identity | authorization | conversation | notification | compromised-password)
       interfaces/*/grpc → application/*/usecase → domain → port/out
       infrastructure/persistence (jOOQ) → PostgreSQL (+RLS)
       infrastructure (Kafka outbox/inbox, Redis quota, OTel/Micrometer)
  → Flyway owns schema; SQLite فقط داخل compromised-password (dataset آفلاین ADR-0040)
```

### ۳.۴ لایه‌بندی hexagonal/DDD که در همه سرویس‌ها تکرار شده

```text
com/sajtech/<service>/
  domain/<feature>/valueobject|...      ← بدون Spring/JOOQ/Kafka/gRPC (AGENTS.md §8)
  application/<feature>/{port/in,port/out,usecase,service,model}
  infrastructure/{persistence,client,quota,worker,security,observability,runtime}
  interfaces/<feature>/grpc             ← adapters ورودی
  configuration/                        ← Spring @Configuration + main application + properties
```

## ۴. نقشه مفاهیم .NET → HooshiX

> **هشدار استفاده (۲۰۲۶-۱۰-۱۵):** کاربر صریحاً گفت در تدریس مقایسه با دات‌نت انجام ندهم. این جدول فقط یک مرجع سکون‌گاهی برای «کجا را نگاه کنم» است و **در جلسات تدریس استفاده نمی‌شود**، مگر خود کاربر بخواهد.

| چیزی که می‌شناسی | معادل اینجا | نکته تفاوت |
|---|---|---|
| `.csproj` / MSBuild / NuGet | `build.gradle.kts` / Gradle / Maven coordinates | lockfile = `gradle.lockfile` با `dependencyLocking`؛ چند module با `settings.gradle.kts` |
| EF Core / Dapper | **jOOQ** (DSL type-safe روی SQL) + JDBC خام | هیچ change-tracking/identity map نیست؛ SQL را خودت می‌نویسی، schema از DB code-gen می‌شود |
| EF Migrations | **Flyway** `V<n>__*.sql` فقط-اضافه‌شدنی | expand → migrate → contract (`AGENTS.md` §9) |
| DI container (`IServiceCollection`) | Spring `@Configuration`/`@Bean`، **constructor injection فقط** | field injection ممنوع؛ singletonها باید stateless/thread-safe باشند |
| `record` C# / immutable DTO | Java `record` (JEP 395+) | default immutability؛ این پروژه value objectها را record می‌گیرد |
| `switch` expression / pattern matching C# | Java pattern matching + `switch` expression + sealed interfaces | در Java 25 بالغ‌تر شده؛ در کد خود پروژه مثال پیدا می‌کنیم |
| `async/await` + Task | **Virtual Threads** (Project Loom) + structured concurrency | thread-per-request ارزان؛ اما «virtual thread ظرفیت downstream نمی‌آفریند» (`AGENTS.md` §13) |
| `System.Text.Json` | Jackson 3 (`tools.jackson.core`) + protobuf generated types | |
| ASP.NET Core MVC | Spring Web MVC (Tomcat 11 pinned) در `web-bff` | بک‌اندهای کسب‌وکار REST ندارند؛ gRPC server دارند |
| gRPC-tools / protoreflect | `com.google.protobuf` Gradle plugin + `protoc-gen-grpc-java` | contracts در package جدا و نسخه‌دار (ADR-0052) |
| Serilog / AppMetrics | Micrometer + OpenTelemetry + JSON allow-list logging | PII/secret هرگز در log/trace/metric (ADR-0031/0044) |
| Roslyn analyzers / Verifier | **ArchUnit** + **SpotBugs** + Spotless + Semgrep | معماری با تست *اجرا می‌شود*، نه با حرف (`AGENTS.md` §12) |
| xUnit/NUnit | JUnit 5 + `spring-boot-starter-test` + tags (`integration`, `architecture`) | |
| Azure App Service / Heroku | K3s/Kubernetes + Helm + Istio Ambient + Kyverno + Traefik/WAF | |

## ۵. مراحل (چک‌لیست اصلی)

ترتیب = **از پایین به بالا** (داده ← دامنه ← کاربرد ← adapters ← HTTP/UI ← پلتفرم)، با دو Stage مقدماتی که بدون آن‌ها بقیه هوابی می‌ایستد. Status: `[ ]` = انجام‌نشده، `[x]` = انجام‌شده (ثبت در §7).

### Stage 0 — نقشه زمین و ابزار ✅ `[x]` (~۴۵ دقیقه)
- **هدف:** بفهمی پروژه «چیه»، چه سرویس‌هایی هست، مرجعیت مستندات چطور کار می‌کند، و چطور محیط را بالا بیاوری.
- **بخوان:** `README.md`؛ `AGENTS.md` (§1, §8, §9, §16)؛ `docs/architecture/README.md`؛ `docs/adr/decision-register.md` (فقط سرجمع)؛ `docs/technology/technology-baseline.md`؛ `docs/runbooks/local-integrated-runtime.md`؛ `Makefile`.
- **ببین و اجرا کن:** `make context-verify` و `make context-bootstrap` (Context Engine ADR-0046)؛ ساختار `services/`، `contracts/`، `apps/`، `infrastructure/`، `deploy/`، `scripts/`.
- **خروجی:** می‌توانی بدون نگاه‌کردن بگویی ۵ سرویس بک‌اند + BFF + frontend چه مسئولیتی دارند و schema گیت‌های repo چطور نگه داشته می‌شوند.

### Stage 1 — Java 25 مدرن روی کد واقعی ✅ `[x]` (~۷۵ دقیقه)
- **هدف:** زبان، نه پروژه: records، final class + factory method، pattern matching برای instanceof و switch، `var` و type inference، null-safety با `Objects.requireNonNull`، `Optional`، Streams، immutable collections، unchecked exceptions، try-with-resources، Semaphore/atomicها و virtual threads.
- **مثال‌های واقعی (نه Toy):** `services/compromised-password-service/src/main/java/com/sajtech/compromisedpassword/domain/lookup/valueobject/Sha1Prefix.java` و `CompromisedHashMatch.java`؛ exceptionها در `application/lookup/LookupOverloadedException.java`؛ پیکربندی record در `configuration/CompromisedPasswordProperties.java`؛ decorator همروندی در `infrastructure/lookup/runtime/BoundedLookupCompromisedPasswords.java`.
- **خروجی:** یک value object پروژه را خط‌به‌خط شرح می‌دهی و می‌گویی invariant کجا اجبار شده و چرا record/کلاس‌عادی انتخاب شده.

### Stage 2 — Gradle و خط لوله کیفیت ✅ `[x]` (~۶۰ دقیقه)
- **هدف:** چرخه build/test/check و منطق Gradle، بدون قیاس با ابزار قبلی.
- **بخوان:** `services/compromised-password-service/settings.gradle.kts` و `build.gradle.kts` (کل فایل، از pluginها تا `jacocoRiskCoverage` و `tasks.check`)؛ `services/identity-service/build.gradle.kts`؛ `docs/engineering/build-and-ci-quality-enforcement.md`؛ `docs/engineering/coding-standards.md`؛ یک `gradle.lockfile`.
- **مفاهیم:** toolchain resolution، version pinning/constraints با `because(...)` امنیتی، dependency locking، تفکیک task با JUnit tags، Spotless/SpotBugs/JaCoCo thresholds، اجرای `./gradlew test integrationTest architectureTest check`.
- **خروجی:** می‌دانی «compile ≠ completion» در این repo یعنی چه و کدام task چه گیتی را می‌شکند.

### Stage 3 — داده: SQL، Flyway، RLS، jOOQ (۲ جلسه, ~۱۵۰ دقیقه) ⭐ قلبِ «لایه پایین»
- **جلسه 3A — Schema & migrations:** `services/identity-service/src/main/resources/db/migration/V1__create_identity_registration_schema.sql` → `V5__enforce_identity_tenant_context_integrity.sql` → `V13__create_data_subject_erasure_schema.sql`؛ `services/authorization-service/src/main/resources/db/migration/V1__create_authorization_schema.sql` و `V3__enforce_tenant_projection_rls.sql`؛ استاندارد `docs/engineering/sql-and-flyway-coding-standards.md`؛ ADR-0027 (RLS الزامی).
  - مفاهیم: table/index/check constraints، generated columns، RLS + `SET LOCAL` tenant context، نقش‌های غیر-owner، expand→migrate→contract، چرا «no SELECT *».
- **جلسه 3B — jOOQ و repositories:** `services/identity-service/src/main/java/com/sajtech/identity/infrastructure/persistence/JooqRegistrationStore.java`، `SpringTransactionRunner.java`، `DatabaseFailureTranslation.java`، `TenantPersistenceSupport.java`؛ `build.gradle.kts` بخش jOOQ/codegen؛ `application/transaction/port/out` (port انتزاعی تراکنش).
  - مفهوم mapping: صراحت column-by-column در Store، جدا بودن DO از domain record، نبود lazy-loading و OSIV (که اینجا ممنوع است)، و چطور N+1 کشف می‌شود.
- **خروجی:** یک مسیر `INSERT`/`SELECT` را از متد جاوا تا SQL واقعی و policy رل‌سطحی رد می‌کنی.

### Stage 4 — Domain لایه (❏ `[ ]`, ~۶۰ دقیقه)
- **بخوان:** `services/identity-service/src/main/java/com/sajtech/identity/domain/registration/` (هرچه زیر `valueobject/` + aggregateها)؛ domainهای دیگر سرویس‌ها را مقایسه‌ای باز کن (`compromisedpassword/domain/lookup`)؛ `services/compromised-password-service/src/test/java/com/sajtech/compromisedpassword/architecture/ArchitectureRulesTest.java`.
- **مفاهیم:** value object vs entity vs aggregate در کد واقعی، invariantها داخل constructor/factory، چرا domain **نباید** Spring/JOOQ/Kafka بشناسد، ArchUnit چطور این را به‌عنوان تست اجرا می‌کند.
- **خروجی:** می‌گویی کدام invariant کجا نگه داشته شده و اگر کسی دور بزند، کدام گیت قرمز می‌شود.

### Stage 5 — Application لایه: ports/use-cases (❏ `[ ]`, ~۹۰ دقیقه)
- **بخوان:** در `services/identity-service/src/main/java/com/sajtech/identity/application/registration/` ترتیب: `port/in` ← `usecase` ← `port/out` ← `model` ← `service`؛ و در `compromisedpassword/application/lookup/usecase/LookupCompromisedPasswordsUseCase.java`؛ `application/transaction/`.
- **مفاهیم:** dependency inversion عملی، input/output port، use case به‌عنوان واحد behavior، idempotency، مرز تراکنش کوتاه، خطاهای domain vs application، الگوی port/in↔interactor.
- **خروجی:** یک use case را خط‌به‌خط توضیح می‌دهی: ورودی، ports، transaction، error paths، side effects.

### Stage 6 — Infrastructure adapters (۲ جلسه, ~۱۵۰ دقیقه)
- **6A:** SQLite dataset خالص: `infrastructure/lookup/dataset/SqliteCompromisedPasswordRepository.java`، `DatasetGuard.java`، `DatasetReleaseManifestReader.java` و `datasetbuild/DatasetBuilderCli.java`/`DatasetBuilder.java` (+ ADR-0040)؛ باندل/مصرف `org.xerial:sqlite-jdbc`؛ task `buildCompromisedPasswordDataset` و `bootJar` excludeها.
- **6B:** Kafka Outbox/Inbox و Redis quota و gRPC clientها: از `services/identity-service` (`infrastructure/` زیر `persistence`/`worker`/`quota`/`client` و `configuration/ErasureKafkaConfiguration.java`) و `services/web-bff/src/main/java/com/sajtech/webbff/infrastructure/{session,quota,client,security}/`؛ `docs/architecture/data-and-messaging.md`؛ ADR-0024 (quota)، ADR-0015 (Kafka durability)، ADR-0013 (Authorization آنلاین بدون کش).
- **مفاهیم:** at-least-once + idempotent consumer، atomic Inbox/Outbox، deadline/timeout، retry تک‌مالک، breaker/fallback، fail-closed، secret material.
- **خروجی:** می‌توانی بگویی یک effect کجا durable می‌شود و چه چیزی هرگز داخل تراکنش DB انجام نمی‌شود.

### Stage 7 — Contracts (protobuf) و Interface لایه gRPC (❏ `[ ]`, ~۹۰ دقیقه)
- **بخوان:** `contracts/protobuf-contracts/settings.gradle.kts` + `src/main/proto/hooshix/identity/v1/identity_registration.proto` و `hooshix/compromisedpassword/v1/compromised_password.proto`؛ سپس `services/compromised-password-service/src/main/java/com/sajtech/compromisedpassword/interfaces/lookup/grpc/CompromisedPasswordGrpcService.java` و `interfaces/observability/grpc/SafeTracingServerInterceptor.java`؛ `infrastructure/runtime/grpc/GrpcServerLifecycle.java`.
- **مفاهیم:** proto3 → کد تولیدشده، versioned packages (v1)، mapping proto DTO ↔ application model (چرا transport model جداست - `AGENTS.md` §9)، Status/خطاها و deadline/cancellation، interceptorها، server lifecycle در Spring Boot.
- **خروجی:** یک operation از `.proto` تا response را بدون مکث رد می‌کنی.

### Stage 8 — web-bff: تنها مرز HTTP مرورگر (❏ `[ ]`, ~۹۰ دقیقه)
- **بخوان:** `services/web-bff/src/main/java/com/sajtech/webbff/interfaces/http/` → `RegistrationController.java`، `MfaController.java`، `PasswordController.java`، `OidcController.java`، `BrowserAuthController.java`، `BffExceptionHandler.java`؛ سپس `application/` و `infrastructure/session/`؛ `docs/adr/0016-define-web-bff-browser-oidc-and-session-security-v1.md` و `docs/adr/0023-define-identity-jwt-signing-key-lifecycle-v1.md`؛ endpoint `https://localhost:18443`.
- **مفاهیم:** BFF الگو، cookie/session security، CSRF، JWT صدور/اعتبارسنجی، OIDC flow با Google، mapping خطای gRPC→HTTP problem، rate-limit/quota لبه، validation (`interfaces/validation`).
- **خروجی:** امنیت یک درخواست لاگین را از مرورگر تا Identity و برگشت trace می‌کنی.

### Stage 9 — Frontend: React 19 + Vite + Tailwind/Radix (❏ `[ ]`, ~۹۰ دقیقه)
- **بخوان:** `apps/web-frontend/package.json` و `apps/web-frontend/vite.config.ts`؛ `apps/web-frontend/src/main.tsx`، `src/app/routing/`، `src/layouts/{public,panel}/`، یک feature کامل مثل `src/features/registration/` و `src/features/session/`، `src/shared/{ui,errors,validation,session}/`؛ `apps/web-frontend/scripts/check-ui-architecture.mjs`؛ `docs/engineering/frontend-coding-standards.md` و `docs/engineering/ui-structure-and-localization-fa.md`؛ `apps/web-frontend/scripts/generate-openapi-client.sh`.
- **مفاهیم:** feature-first UI، state management بدون framework سنگین، OpenAPI client تولیدشده، design tokens/Tailwind 4، accessibility gate، bundle budget، Playwright/Vitest.
- **خروجی:** یک فرم ثبت‌نام را از DOM تا call به `/api` BFF توضیح می‌دهی (با فارسی/RTL پروژه).

### Stage 10 — conversation-service و ML Ops governance (❏ `[ ]`, ~۶۰–۹۰ دقیقه)
- **بخوان:** `services/conversation-service/src/main/resources/db/migration/V1__create_conversation_foundation.sql` تا `V6__add_model_run_feedback.sql`؛ کد `application`/`infrastructure` مربوط به model run acceptance/budget/worker؛ `docs/architecture/mlops-evaluation-and-safety.md`؛ `mlops/{prompts,schemas,governance,evaluations}/`؛ `docs/adr/0054-*.md` و `docs/adr/0057-*.md`؛ ADR-0041 (Reference Data محلی).
- **خروجی:** می‌فهمی اجراي مدل چطور budgeted، versioned و قابل بازگشت (canary/rollback) نگه داشته شده.

### Stage 11 — زیرساخت و پلتفرم (۲ جلسه, ~۱۵۰ دقیقه)
- **بخوان:** `docs/architecture/platform-architecture.md`، `network-architecture.md`، `runtime-and-deployment.md`، `security-architecture.md`؛ `infrastructure/{kind,calico,istio,kyverno,traefik,waf,gateway-api,observability,production,staging,registry}/`؛ `deploy/{clusters,releases,staging}/`؛ ADR-0042 (single-server)، ADR-0043، ADR-0017 (admission/signing)، ADR-0034 (CloudNativePG)، OpenBao/ADR-0011؛ `docs/runbooks/local-production-fidelity-staging.md`.
- **اجرا (اختیاری، با تأیید کاربر):** `make local-cluster-up`، `make production-fidelity-up`، `make production-fidelity-verify`.
- **خروجی:** امنیت workload، mTLS Ambient، NetworkPolicy deny-by-default، probes/resources و مسیر secretها را توضیح می‌دهی.

### Stage 12 — CI/CD و زنجیره DevSecOps (❏ `[ ]`, ~۶۰ دقیقه)
- **بخوان:** `.github/workflows/repository-baseline.yml` + workflow هر سرویس؛ `docs/architecture/devsecops-security-toolchain.md` و ADR-0045؛ `scripts/baseline/verify_repository.py` (گیت FILE_INDEX/ADR)؛ `scripts/ci/quality/verify_repository_sources.sh`؛ `.gitleaks.toml`، `.semgrep/`.
- **خروجی:** می‌دانی کدام tool چه چیزی را و در کدام مرحله blocking چک می‌کند و چرا Gitleaks/Semgrep/OSV/Syft/Grype/Cosign/Kyverno جایگزین هم نیستند.

### Stage 13 — جمع‌بندی end-to-end + Capstone onboarding (❏ `[ ]`, ~۹۰ دقیقه)
- **اجرا:** `make local-runtime-up` و `make local-runtime-status` (طبق `docs/runbooks/local-integrated-runtime.md`)؛ در صورت نیاز `make local-runtime-smoke-erasure`.
- **سناریو:** یک journey کامل (registration → verification → login/MFA → یک فراخوانی authorize → یک notification/erasure effect) را روی runtime زنده trace می‌کنیم: log ساختاریافته، metric، trace، رول DB.
- **Capstone:** با هم یک تغییر کوچک واقعی را از `docs/architecture/APPLICATION-IMPLEMENTATION-ROADMAP.md` انتخاب می‌کنیم و تو مسیر کامل change workflow را (branch → review scope → گیت‌ها → PR) تعریف می‌کنی؛ من فقط نظارت می‌کنم و اجرا نمی‌کنم مگر بخواهی.
- **تعریف «onboard شدی»:** می‌توانی (۱) جریان داده را از UI تا DB بگویی، (۲) بدانی هر لایه چرا جداست و کدام گیت آن را اجبار می‌کند، (۳) یک فایل جدید در جای درست بگذاری، (۴) بگویی کدام چیزها هنوز **NOT VERIFIED** هستند و نباید ادعا شوند.

## ۶. برآورد زمان

۱۳ Stage، ≈ **۱۶–۱۸ ساعت** تدریس خالص (شامل ۵ جلسه ۲-بخشی). امکان فشرده‌سازی: اگر فقط «مهندسی روزمره» را می‌خواهی، مسیر کوتاه‌تر = **0، 1، 2، 3A، 3B، 4، 5، 7، 8، 9** (~۹–۱۰ ساعت) و ۶B/۱۰/۱۱/۱۲ بعداً.

## ۷. Progress log (فقط این بخش در طول کار بازنویسی می‌شود)

| تاریخ | Stage | وضعیت | HEAD | یادداشت/شواهد |
|---|---|---|---|---|
| 2026-10-15 | — (پلان ایجاد شد) | created | ccbdbd69 | هیچ Stage آموزشی شروع نشده. |
| 2026-10-15 | Stage 0 | done | ccbdbd69 | `make context-verify` و `make context-bootstrap` در WSL اجرا و PASSED شد؛ `java -version` = Temurin 25.0.4+7؛ wrapper Gradle 9.6.1؛ داکس‌های §۵ Stage 0 خوانده شد؛ دو drift در §۸ ثبت شد |
| 2026-10-15 | Stage 1 | done | ccbdbd69 | هفت فایل واقعی سرویس رمز نشت‌شده خط‌به‌خط تدریس شد (value object، properties record، ports، exceptions، decorator همروندی، switch/var/try-with-resources)؛ از این جلسه قاعده «ممنوعیت قیاس با دات‌نت» به §۲ اضافه شد |
| 2026-10-16 | Stage 2 | done | ccbdbd69 | `services/compromised-password-service/settings.gradle.kts` + `build.gradle.kts` + `gradle.lockfile` (۲۰۶ سطر) + `gradle/verification-metadata.xml` + `gradle.properties` + `config/spotbugs/exclude.xml` + `services/identity-service/build.gradle.kts` (pitest) + `contracts/protobuf-contracts/build.gradle.kts` + `.github/workflows/compromised-password-service.yml` + `docs/engineering/build-and-ci-quality-enforcement.md` + `docs/engineering/coding-standards.md` تدریس شد (wrapper pinning، toolchain بدون auto-provisioning، configurations، `platform`/`constraints`+`because`، locking با `--write-locks`/`cmp`، verification با sha256، تفکیک تست با JUnit tags، risk-based JaCoCo، Spotless/SpotBugs/pitest، `bootJar` exclude و assert منفی `jar tf`، wiring `tasks.check` در برابر گیت‌های granular CI). هیچ task Gradle/Coverage اجرا نشد. دو قیاس باقی‌مانده در §۵ Stage 3B/5 حذف شد |

> قفل‌شدگی سطر: برای هر Stage یک سطر اضافه کن با `done`/`partial` + `HEAD` همان لحظه. اگر کد پروژه بین جلسات تغییر کرد، **اول فایل‌های آن Stage را دوباره از دیسک تأیید کن** (این پلان snapshot است، نه مرجع).

## ۸. Resume protocol — اگر ارتباط قطع شد

دستور دقیق برای هر session جدید (یا هر agent جدید):

```bash
cd /home/coder/workspace/Hooshix
git rev-parse --short HEAD
make context-verify && make context-bootstrap   # bootstrap رسمی مخزن (ADR-0046)
```

بعد:
1. این فایل را از §7 بخوان: **اولین Stage با `[ ]` = نقطه ادامه**.
2. §۲ (قواعد) و §۵ همان Stage را کامل بخوان.
3. قبل از تدریس، **هر path همان Stage را با Read/Glob از دیسک تأیید کن**؛ اگر جابه‌جا/تغییر کرده، همان‌جا در §8 زیر این فهرست یادداشت کن و با مسیر واقعی ادامه بده.
4. تدریس را شروع کن؛ در پایان Stage: تیک `[x]` + سطر §7 + گزارش با فیلدهای `AGENTS.md` §16.
5. این پلان **هیچ‌وقت جایگزین `AGENTS.md`/ADRs نمی‌شود**؛ اگر تعارض دیدی، مخزن می‌برد و §۳/§۵ اصلاح می‌شود.

### یادداشت‌های انحراف و driftهای مشاهده‌شده (فقط یادداشت؛ اصلاح با درخواست کاربر)
- `docs/architecture/platform-architecture.md:144` Conversation را `implementation NOT PRESENT` می‌داند، ولی `docs/architecture/implementation-status.md:413` آن را `IMPLEMENTED` (post-canary safe-disabled) و سورس `services/conversation-service/` با ۹۵ فایل/۱۰٬۷۹۷ خط موجود است. طبق `AGENTS.md` §1 مرجعِ evidence همان `implementation-status.md` است؛ این سطر platform-architecture کهنه به‌نظر می‌رسد.
- `docs/runbooks/local-integrated-runtime.md:13-21` فهرست کامپوننت فقط PostgreSQL/Redis و پنج سرویس را نشان می‌دهد، ولی `scripts/local/runtime.py:206` و `provision_kafka_topics()` یک broker کافکا (پورت 19092 از `KAFKA_PORT`) را هم بالا می‌آورد و topicها را provision می‌کند؛ پورت Kafka در runbook ذکر نشده.
- `python3 scripts/baseline/verify_repository.py` روی این working tree fail است (`.brud/`, `.worktrees/stage10-host-audit/`, ۳۴ فایل `docs/HooshiX_Design_Handoff/` بدون ایندکس). Pre-existing، نامربوط به این پلان.
- Stage 2 (۲۰۲۶-۱۰-۱۶): هیچ سرویسی `src/main/proto` ندارد و `build/classes/java/main/com/sajtech/compromisedpassword/` فقط پنج بستهٔ دست‌نویس دارد؛ کلاس‌های `*.contract.v1` از artifact منتشرشدهٔ `protobuf-contracts` می‌آیند. پس بلوک `protobuf { ... }` در build هر سرویس عملاً فقط configurationهای `compileProtoPath`/`testCompileProtoPath` را می‌سازد (که در lockfile قفل‌اند) و `config/spotbugs/exclude.xml` یک محافظ است، نه نیاز فعلی.
- Stage 2: هیچ toolchain auto-provisioning resolver ثبت‌شده نیست (نه `foojay` در `settings.gradle.kts`، نه در `gradle.properties`)؛ JDK 25 باید از قبل روی ماشین باشد (CI با setup-java، محلی با `.sdkmanrc`). این محدودیت عمدی و supply-chain-محور است، نه فراموشی.
- Stage 2: CI هیچ‌وقت `build` یا `check` صدا نمی‌زند؛ هر گیت یک step جدا با `--write-locks` و `--dependency-verification strict` است. `tasks.check` فقط مسیر محلی/IDE است.

## ۹. Backlog عمقِ «با درخواست» (Deep dives — پیش‌فرض خوانده نمی‌شوند)

این‌ها را فقط وقتی باز می‌کنیم که بگویی «برو عمیق»:

- Argon2id password storage و key lifecycle کامل (ADR-0023) و material encryption در Notification (ADR-0014).
- ماشین حالت Session/RefreshFamily و rotation/revocation.
- کل زیرمسیر Erasure چهار-مشارکت‌کننده (ADR-0028) + `make local-runtime-smoke-erasure-recovery`.
- Quota/Redis الگوریتم ADR-0024 (common-clock، aggregate-prefix pressure، fail-closed).
- Authorization semantics و breaker recovery (ADR-0013/0026/0032/0036).
- Telemetry pipeline کامل (Collector/Loki/Tempo/Prometheus/Grafana/Alertmanager) و ADR-0031 PII-safe logging.
- Supply chain: SBOM/Cosign/provenance/Kyverno admission (ADR-0017/0035) و OpenBao recovery rehearsal.
- `docs/audit/` و `docs/engineering/` مابقی؛ `mlops/` governance v1/v2؛ `scripts/performance/` و capacity evidence.
- تولید واقعی: `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` + `implementation-status.md` (چیزی که **نیست** هم مهم است).

## ۱۰. گیت‌های repo که این فایل را نگه می‌دارند

- این فایل در `FILE_INDEX.txt` ثبت شده؛ `make baseline-verify` (و `python3 scripts/baseline/verify_repository.py`) بدون آن سطر **fail** می‌شود. اگر این فایل را جابه‌جا/حذف کردی، همان‌جا `FILE_INDEX.txt` و ترتیب lexicographic را اصلاح کن.
- این سند در `docs/architecture/SOURCES.md` ثبت **نشده**، چون مرجع معماری نیست؛ `verify_repository.py` فقط برعکسش را چک می‌کند (refs شکسته).

# نصب اولیهٔ mesh و OpenBao روی VPS

این راهنما متعلق به تغییر یکپارچهٔ Stage 10 است: استفاده از CA و storage موجود،
نصب محدود Istio و OpenBao، و ثبت نتیجهٔ واقعی هر مرحله. ساخت دوبارهٔ Root،
CSR یا filesystem هشت GiB، تغییر SSH/ایمیل و حذف دسترسی مدیر در این تغییر نیست.

انتشار تصویر با نصب روی VPS فرق دارد. receipt انتشار فقط امضا، provenance،
SBOM و scan همان digest را ثابت می‌کند؛ جای staging یا admission زنده را نمی‌گیرد.
پیش از فعال‌سازی PV/workload، الزام‌های ADR-0011 و ADR-0017 باید برقرار باشند.
نصب اولیه از استثنای محدود ADR-0030 و ورود محلی رمز sudo استفاده می‌کند؛
این مسیر مجوز عمومی CI برای مدیریت Production نیست.

## وضعیت

Implementation: admission, disposable signed staging and supervised host installer implemented; executed CI/target evidence required
VPS installation: Not run by this change
Production readiness: Not verified

## آزمون خودکار، بدون استفاده از VPS

workflow «Platform commissioning staging» در PR داخلی مرتبط به‌صورت خودکار
اجرا می‌شود. بعد از merge می‌توان در GitHub ← Actions ← Run workflow آن را
اجرا کرد؛ دو ورودی، شمارهٔ اجرای موفق انتشار mesh و OpenBao هستند، نه کلید.
انتشار باید از workflow دقیق `production-release.yml` روی `main` باشد.

این lane روی runner موقت یک کلاستر مستقل می‌سازد: Calico واقعی، Istio Ambient،
Kyverno واقعی، چهار image منتشرشده با همان digest، امضای دقیق CI، provenance و
SBOM. سیاست ابتدا Audit و سپس Deny آزموده می‌شود. signer اشتباه، SBOM ناموجود،
revision اشتباه، هویت غیرمجاز و plaintext باید رد شوند. آزمون native قبلی نیز
TLS، حالت sealed، Shamir مصنوعی ۳/۲، restart/PVC، ACL و لغو root را بررسی می‌کند.
هیچ Root، کلید یا سهم واقعی مالک به CI نمی‌رود؛ فقط token موقت `packages:read`
در حافظه و Secret کلاستر موقت استفاده می‌شود. cleanup پیش‌نیاز receipt موفق است.

artifact عمومی `platform-staging-<run>-<attempt>` نتیجهٔ واقعی را ثبت می‌کند.
صرف وجود workflow یا موفقیت unit test، نتیجهٔ این lane محسوب نمی‌شود. مسیر CNI
kind، Root مصنوعی و storage موقت جای بررسی هدف K3s، CA موجود و PV محدود VPS را
نمی‌گیرند. این workflow به VPS وصل نمی‌شود و هیچ دستور نصب Production ندارد.

برای render عمومی کنترل‌ها، روی checkout بازبینی‌شده و با Helm پین‌شده:

```bash
python3 scripts/production/render_platform_admission.py \
  --mesh-publication /path/to/mesh/receipt.json \
  --openbao-publication /path/to/openbao/receipt.json
```

خروجی شامل candidate و چهار policy پایدار CEL است. namespaceهای بسته، SA/image
دقیق، seccomp، منع host namespace و mount خارج از استثنای دو جزءٔ شبکه، و امضای
provenance/SBOM اعمال می‌شوند. Secret فقط با نام `hooshix-ghcr-read` ارجاع می‌شود؛
policy مستقیماً روی همهٔ Podها، از جمله Podهای ساخته‌شده توسط controller، اجرا
می‌شود. autogen برای controllerها خاموش است تا بازنویسی constraint در Kyverno
۱٫۱۸ محدودهٔ namespace این استثنای نصب را گسترش ندهد؛ کنترل Pod خاموش نمی‌شود.
دو ValidatingPolicy یک selector محدود و یکسان برای هر دو namespace دارند، چون
Kyverno ۱٫۱۸ webhook مشترک می‌سازد؛ شرط CEL هر policy فقط namespace خودش را
کنترل می‌کند. آزمون native رد درخواست در هر دو namespace الزامی است.
محتوای credential در خروجی نیست. metadata receipt به‌تنهایی اصالت رمزنگاری‌شده
یا مجوز apply نیست. خروجی را مستقیماً به `kubectl apply` ندهید.

خطای واقعی candidate قبلی نیز اصلاح شد: chart ztunnel از
`multiCluster.clusterName` استفاده می‌کند، نه `clusterName` در سطح بالای values.
نام کلاستر اکنون با istiod یکسان است. مرجع، chart vendored 1.30.5 و
[راهنمای رسمی Helm Ambient](https://istio.io/latest/docs/ambient/install/multicluster/multi-primary_multi-network/) است.

## نصب واقعی روی VPS

این بخش فقط پس از موفقیت workflow بالا، بازبینی و merge تغییر به `main` مجاز است.
روی checkout تمیز همان `main`، بستهٔ عمومی را خارج از پروژه بسازید؛ این دستور
هیچ credential نمی‌خواند و به VPS وصل نمی‌شود. `STAGING_RUN` باید اجرای موفق
برای tree دقیق همین نسخه باشد؛ سازنده، نتیجه و artifact را از GitHub احراز می‌کند.

```bash
python3 scripts/production/build_platform_commissioning_bundle.py \
  --staging-run STAGING_RUN \
  --public-ca /mnt/c/Users/Coder/Downloads/HooshiX-stage10-intermediate-20261005/signed-intermediate-82360de7a72240eba9ced5e3522f7625 \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-platform-commissioning
```

دو شمارهٔ انتشار پیش‌فرض، mesh=`37588183736` و OpenBao=`37611729931` هستند.
اگر evidence بیش از پنج روز عمر دارد، publication/staging تازه لازم است، نه ساخت Root.
بسته شامل source/plan بازبینی‌شده، hashها، چهار فایل عمومی گواهی و همین راهنماست؛
هیچ کلید یا رمز خصوصی در بسته نیست. پوشهٔ خروجی باید تازه و بیرون پروژه باشد.

در Windows ابتدا کنسول نجات کارا و یک نشست مستقل خصوصی SSH باز نگه دارید.
سپس در PowerShell محلی، بدون transcript یا ضبط صفحه:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\Downloads\HooshiX-platform-commissioning\run-platform-commissioning.ps1" -RescueAndSecondSessionReady
```

رمز sudo، token موجود GHCR با `read:packages`، و رمز **Intermediate قبلی**
را فقط در prompt مخفی همین پنجره وارد کنید. token را از فایل خصوصی قبلی
خودتان بردارید؛ هیچ مقدار محرمانه‌ای در گفتگو نفرستید. رمز Root لازم نیست.
این script منبع/hash را قبل از اجرای root کنترل می‌کند و Python را با `-I`
اجرا می‌کند؛ Secret از حافظه و stdin API ساخته می‌شود، نه فایل user-writable
یا argv. احراز محلی و deadline بیست‌دقیقه‌ای همچنان استثنای محدود ADR-0030 است.

برای گزارش‌گیریِ منع ephemeral container، یک ClusterRole فقط با مجوز
`get/list/watch` روی `pods/ephemeralcontainers` به reports-controller موجود
تجمیع می‌شود؛ مجوز تغییر Pod، اجرای debug یا خواندن Secret اضافه نمی‌شود.

ترتیب اجرا: هدف/audit/encryption/storage موجود، pull Secret محدود، چهار policy
با Deny، mesh، TLS سرویس با CA قبلی، PV محلی Retain و StatefulSet. محدودیت PSA
فقط در `istio-system` و بعد از policy فعال، برای دو جزءٔ شبکهٔ تأییدشده اعمال
می‌شود؛ `hooshix-secrets` همچنان Restricted است. هشت GiB دوباره ساخته نمی‌شود.
کلید TLS جدید فقط موقتاً در پوشهٔ root-only و سپس Secret رمزگذاری‌شدهٔ Kubernetes
قرار می‌گیرد؛ پوشهٔ موقت پاک می‌شود. leaf اولیه ۳۰ روز اعتبار دارد؛ قبل از بازکردن
Production باید تمدید تحت secret authority و هشدار انقضا تکمیل شود.

موفقیت فقط با `OPENBAO_INSTALLATION=Passed` و receipt عمومی پذیرفته می‌شود.
نصب، digest واقعی، TLS/SAN، probeهای sealed، اتصال به PV مشخص و حفظ سرویس‌ها
بررسی می‌شوند. `Ready=false` در این مرحله طبیعی است: OpenBao **sealed و هنوز
initialize نشده** است. script هیچ root token یا سهم Shamir ایجاد نمی‌کند.
فعال‌سازی، نگهداری امن سه سهم با threshold=2، off-host snapshot/restore، ESO،
audit/JIT و دروازهٔ ترافیک Production هنوز مراحل بعدی‌اند.

در شکست، workload/PVC/CA و marker root-only حفظ می‌شوند؛ script هیچ rollback
با حذف داده ندارد و `--force-conflicts` استفاده نمی‌کند. فقط source و فایل‌های
عمومی همان upload با UUID پاک می‌شوند. خطای ثابت/مرحله و receipt را بفرستید؛
قبل از retry علت را برطرف کنید. snapshot/کلید/رمز یا خروجی Secret را نفرستید.
desired state عمومی همین bundle باید در reconciliation بعدی GitOps از منبع
reviewed Git حفظ شود؛ استثنا، مجوز drift یا مدیریت عادی بدون JIT نیست.

هیچ کلید، رمز، سهم Shamir یا token را در گفتگو ارسال نکنید.

## گزارش بازبینی تغییر

Architecture review mode: full-read
Architecture document version/commit: main@ffc299093b965450e4ef433a2d8392a01c404e0c
Architecture sections reviewed: platform, runtime, network, security, supply chain, OpenBao, testing, reliability, capacity, readiness
ADRs reviewed or changed: ADR-0002/0011/0017/0030/0042/0043/0045 reviewed; None changed
Changed bounded context/module: production platform commissioning tooling only
Contracts changed: optional disposable platform rehearsal; exact namespace-scoped CEL admission
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: finite native tool/rollout deadlines; 35-minute disposable job
Retry/cancellation/concurrency behavior: bounded condition waits; cleanup on failure; no deployment retry or force-conflicts
Kafka/event and idempotency behavior: Not applicable
Security impact: fixed imports and exact signer/provenance/SBOM; no production secret or operator credential in CI
Istio identity and authorization impact: fixture-only exact probe principal; production remains default deny
Logging and PII impact: fixed step labels and public receipt; private raw diagnostics suppressed
Observability added or changed: content-free staging checks; no production telemetry changes
Build/CI/architecture enforcement changed: native staging workflow plus deterministic production tests
Tests executed: local production/static/context verification Passed; native platform staging Not run until CI executes
Architecture deviations: None; staging evidence never substitutes target validation or traffic readiness
Rollback considerations: no VPS changes; disposable cleanup deletes only its unique kind cluster

# گواهی میانی برای ادامهٔ نصب Stage 10

مالک در ۲۰۲۶-۱۰-۰۵ bootstrap محدود را تأیید کرد؛ اختیار و پایان این استثنا در
[ADR-0030](../adr/0030-define-production-human-jit-access-v1.md#owner-approved-commissioning-bootstrap-2026-10-05)
است. Root موجود طبق [ADR-0002](../adr/0002-define-production-istio-trust-and-enrollment.md)
دوباره ساخته نمی‌شود. این راهنما مرحلهٔ CSR است، نه ادعای نصب OpenBao یا آمادگی Production.

## ۱. فقط یک اجرا روی Windows متصل

دو فایل `bootstrap_intermediate_csr.py` و `run-intermediate-csr.ps1` از نسخهٔ
merge‌شده و CI موفق، در پوشهٔ محلی Windows خارج مخزن کپی می‌شوند. launcher در
PowerShell اجرا می‌شود؛ `-ReviewedCommit` همان SHA کامل main بازبینی‌شده است:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-stage10-intermediate-20261005\run-intermediate-csr.ps1' -ReviewedCommit <verified-main-sha>
```

رمز sudo را همان پنجره وارد کنید؛ سپس یک رمز **جدید** برای کلید میانی، دو بار.
کنسول نجات موجود را در طول این اجرای مدیریتی باز نگه دارید.
آن را در password manager نگه دارید؛ به چت نفرستید. رمز Root را اینجا وارد نکنید.
اسکریپت هیچ رمز یا terminal transcript ضبط نمی‌کند. رمز میانی ۲۰ تا ۱۲۸ کاراکتر
و بدون کاراکتر کنترلی است. پنجره را تا پایان نبندید.

کلید RSA-4096 فقط در حافظهٔ فرایند root ساخته و با native OpenSSL در قالب PKCS#8،
AES-256-CBC و PBKDF2-HMAC-SHA256 با یک میلیون iteration رمز می‌شود. فایل خصوصی
فقط در `/var/lib/hooshix-pki/istio-system/ca-key.enc.pem` روی VPS، root-owned/0600
و پوشه‌های 0700 می‌ماند. رمز در argv، environment یا فایل قرار نمی‌گیرد؛ core dump
خاموش است. این محافظت، مصونیت مقابل root آلوده یا بازیابی forensic حافظه را ادعا نمی‌کند.
فقط CSR عمومی و receipt محتوا-امن دانلود می‌شوند. نصب package، دانلود image،
نوشتن Kubernetes Secret/PV، تغییر storage/SSH/فایروال/ایمیل یا init OpenBao انجام نمی‌شود.

پیش‌شرط‌های همان اجرای محدود: هدف و نسخهٔ دقیق OpenSSL مصوب، auditd سالم و lost=0،
فعال‌بودن K3s، secrets encryption فعال و hashهای منطبق، نبود istiod فعال. آزمون‌های
موفق storage/reboot/fault دوباره اجرا نمی‌شوند. هر native command timeout محدود
و retry صفر دارد؛ lock انحصاری از تولید هم‌زمان جلوگیری می‌کند.

پس از موفقیت، `PUBLIC_CSR` مسیر فایل `cluster-intermediate.csr.pem` و
`CSR_SHA256` هش مستقل سرور/دانلود را می‌دهند. فقط همین فایل عمومی را به کامپیوتر
آفلاین ببرید و hash را جداگانه نگه دارید. وجود CSR، نصب OpenBao نیست.

## ۲. امضا با همان Root روی دستگاه آفلاین

بستهٔ عمومی موجود `production-offline-signing-d086adcb` را استفاده کنید؛ فایل
`unpacked\HooshiX-production-offline-ca\run-sign.cmd` را روی دستگاه **آفلاین** اجرا کنید.
Root قبلی روی همان دستگاه/فلش‌های شما می‌ماند. `run-root.cmd`، `run-backup.cmd`
یا تولید ToOnline تازه لازم نیست.

ابزار این ورودی‌ها را می‌خواهد:

1. در صورت نبود state محلی، مسیر پوشهٔ **پشتیبان رمزدار قبلی Root** دارای
   `root-key.enc.pem`، `root-cert.pem` و `backup-receipt.json`؛ نه پوشهٔ عمومی.
2. مسیر CSR عمومی جدید.
3. مقدار دقیق `CSR_SHA256` از اجرای سرور، بدون تغییر.
4. رمز **Root** فقط روی دستگاه آفلاین.

باید `INTERMEDIATE_CERTIFICATE=PASSED_NOT_INSTALLED` و `PUBLIC_SIGNED_OUTPUT` ببینید.
فقط پوشهٔ `signed-intermediate-*` شامل `ca-cert.pem`، `cert-chain.pem`،
`root-cert.pem` و `signing-receipt.json` را به Windows متصل برگردانید. کلید/رمز
Root و کلید خصوصی میانی را جابه‌جا نکنید. نام پوشهٔ عمومی برگشتی کافی است؛
هیچ secret به گفتگو ارسال نشود.

## ۳. نقطهٔ ادامه و خطا

پس از برگشت امضای عمومی، import بازبینی‌شده باید chain/subject/pathlen/expiry،
تطبیق کلید محلی با CSR و گواهی، hash Root و encryption واقعی Kubernetes را بررسی
کند؛ سپس `cacerts` فقط در `istio-system` پیش از istiod نصب می‌شود. این import و
نصب mesh/GitOps/OpenBao، custody واقعی Shamir، backup/restore، audit/JIT و مجوز
traffic هنوز مرحله‌های اجرا‌نشده‌اند. ابزار CSR آن‌ها را `Passed` اعلام نمی‌کند.

اگر اتصال قطع شد، همان launcher را اجرا کنید: با state کامل و hashهای درست فقط
همان CSR عمومی دانلود می‌شود؛ کلید تازه نمی‌سازد و رمز دوباره لازم نیست. state
ناقص/تغییرکرده، symlink، hardlink، مجوز باز یا directory اضافی باعث توقف است؛
فایل‌ها **حفظ** می‌شوند. آن‌ها را برای عبور از خطا پاک نکنید و blind retry نکنید.
خروجی خطا فقط code محدود است و متن خام crypto یا secret ندارد. source/receipt
عمومی موقت `.cache` پس از دریافت پاک می‌شوند؛ کلید خصوصی سرور پاک نمی‌شود.

## شواهد و حدود تغییر

Architecture review mode: full-read
Architecture document version/commit: main@51b78e4cacf35b0cfd0bcb88827107d5390dbbbc
ADRs reviewed or changed: ADR-0002/0011/0017/0030/0042/0043/0045; ADR-0030 bootstrap added
Changed bounded context/module: controlled VPS intermediate CSR custody and public offline handoff
Contracts changed: local CLI/public CSR receipt; no application contract
Database migration: Not applicable
Transaction boundary: exclusive root-directory lock; create-only fsynced files; partial state retained
Timeout/deadline behavior: whole privileged process 600s including passphrase entry; native command 20s; RSA generation 120s; transport connect 8s
Retry/cancellation/concurrency behavior: no automatic write retry; exclusive nonblocking lock; interruption preserves state
Security impact: owner-approved local sudo only; encrypted intermediate; Root never online
Istio identity and authorization impact: prepares existing trust hierarchy; no workload/mesh/API mutation
Logging and PII impact: fixed error codes/content-free metadata plus public CSR; no private material
Observability added or changed: public hash-bound receipt; protected existing OS audit required; off-host audit Not verified
Build/CI/architecture enforcement changed: existing production test discovery and Windows launcher validation
Tests executed: focused synthetic native crypto and negative fixtures Passed; final PR/main CI recorded in PR #173
Architecture deviations: only explicit ADR-0030 commissioning bootstrap, not a traffic/security waiver
Rollback considerations: never overwrite/delete existing PKI; no live workload or Root changes

مراجع نسخه‌ای: [OpenSSL 3.5 PKCS#8](https://docs.openssl.org/3.5/man1/openssl-pkcs8/)،
[passphrase descriptor](https://docs.openssl.org/3.5/man1/openssl-passphrase-options/)،
[Istio plug-in CA](https://istio.io/latest/docs/tasks/security/cert-management/plugin-ca-cert/).
Context7 و مستندات رسمی برای این رفتار بررسی شدند؛ مصدر نسخهٔ Ubuntu موجود
`openssl`/`libssl3t64:amd64` برابر `3.5.5-1ubuntu3.7` است، نه مجوز upgrade خودکار.

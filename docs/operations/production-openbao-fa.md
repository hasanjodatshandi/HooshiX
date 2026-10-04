# راه‌اندازی OpenBao در Stage 10

## محدودهٔ این تغییر

تغییر جاری، مقصد انتشار کاندیدای OpenBao 2.6.4 را به بستهٔ خصوصی مصوب تغییر
می‌دهد؛ نه فعال‌کردن Production، init واقعی، تغییر SSH/MCP یا حذف sudo دائمی.
تعریف reusable در `infrastructure/production/secrets/` می‌ماند و تا عبور از
gateهای promotion وارد ریشهٔ فعال `deploy/clusters/production` نمی‌شود.

## ترتیب اجرا و نقطهٔ ادامه

بستهٔ کاری جاری: انتشار همان digest رسمی OpenBao به GHCR خصوصی، اسکن و امضای
کاندیدا در workflow محافظت‌شدهٔ موجود. این کار ساخت مجدد upstream، نصب روی VPS،
staging مصوب، تحویل Shamir یا اجازهٔ Production نیست. دامنهٔ نصب فعلی مالک
`hooshix.com` است؛ تنظیمات شبکه و HTTPS آن شاهد جداگانه لازم دارند.

### انتشار کاندیدای خصوصی بدون نصب

مقصد جدیدِ تأییدشدهٔ مالک `hooshix/platform-openbao-private` است. اجرای
`37222311660` کپی همان digest را گذراند ولی gate خصوصی‌بودن را رد کرد؛ API
GitHub برای بستهٔ قبلی `hooshix/platform-openbao` مقدار `public` برگرداند.
بستهٔ قبلی حذف یا تغییر نمی‌کند و در انتشار بعدی مقصد معتبر نیست. طبق
[مستندات رسمی GHCR](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility#configuring-visibility-of-packages-for-your-personal-account)،
بستهٔ عمومی دوباره خصوصی نمی‌شود؛ بستهٔ تازه در حساب شخصی پیش‌فرض خصوصی دارد،
اما pipeline همچنان وضعیت واقعی آن را پیش از اسکن و امضا بررسی می‌کند.
تغییر نام به‌تنهایی شاهد انتشار، staging یا نصب نیست.

اجرای اولیهٔ `37218037965` بعد از approval مالک در مرحلهٔ copy شکست خورد؛
هیچ receipt موفق یا امضایی صادر نشد و auth موقت پاک شد. Cosign 3.0.6 گزینهٔ
`--platform` را فقط برای multiarch index قبول می‌کند؛ pin فعلی manifest تک‌معماری
است. فرمان copy بنابراین **بدون `--platform`** همان digest را می‌گیرد؛ Syft و
validator معماری و digest مقصد را همچنان بررسی می‌کنند. این اصلاحِ merge‌شده
سازگاری copy، تست regression و diagnostics امن آن را پوشش می‌دهد؛
اجازهٔ نصب یا عبور از approval/scan/signature را تغییر نمی‌دهد.

بعد از merge و موفقیت `Repository baseline` برای **همان SHA روی main**:

1. GitHub ← Actions ← **Production release evidence** ← Run workflow.
2. branch را `main` و `release_kind` را `openbao-candidate` انتخاب کنید.
3. `release_manifest_path` برای این حالت خالی می‌ماند؛ برای `applications` همچنان
   manifest واقعیِ هفت برنامه الزامی است و gateهای قبلی تغییر نمی‌کنند.
4. مالک در environment محافظت‌شدهٔ `production-release`، **Review deployments**
   را بررسی و approve کند. کلید خصوصی، PAT یا credential VPS وارد CI نکنید.
5. فقط اگر job و cleanup `Passed` شدند، artifact عمومی
   `openbao-publication-<run_id>-<attempt>` و `receipt.json` را بررسی کنید.
   نبود receipt یا job ناموفق، موفقیت انتشار/امضا نیست.

فایل عمومی `attempt.json` فقط ثبت شروع تلاش است و حتی هنگام خطای اولیه باقی
می‌ماند؛ مدرک موفقیت نیست. خطای native فقط با category محدود
`OPENBAO_TOOL_FAILURE` گزارش می‌شود؛ متن خام پاسخ/error/credential منتشر نمی‌شود.

مقصد ثابت `ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private@sha256:<same-digest>`
است؛ tag فقط locator کاندیداست، نه authority استقرار. Cosign 3.0.6 از installer
checksum-pin‌شدهٔ موجود، `copy` همان manifest Linux/amd64 را بدون rebuild/force
انجام می‌دهد. این فرمان در همین نسخه deprecated اما موجود است؛ این مسیر محدود
برای reuse ابزار فعلی است، نه افزودن ابزار یا policy موازی. artifactهای legacy
upstream ممکن است کپی شوند؛ جای امضای مورد انتظار workflow خودمان نیستند.
خصوصی‌بودن package پس از copy و پیش از sign الزام است؛ خطای API، package عمومی،
digest/نسخه/architecture نامنطبق، scanner/feed نامعتبر یا High/Critical اجازهٔ sign
نمی‌دهند. کپی ناقص ممکن است در registry بماند؛ بدون receipt/امضا قابل promotion نیست.

SBOM از **تصویر نهایی در مقصد** تولید می‌شود. تصویر، provenance نوع
`unchanged-upstream-import` و CycloneDX با همان OIDC issuer/subject دقیق workflow
موجود امضا و payload/digest آن‌ها بررسی می‌شوند؛ signer اشتباه باید رد شود.
این provenance ادعای ساخت binary upstream توسط HooshiX یا اثبات independent upstream
build نیست. receipt، staging/admission/deployment/promotion را `Not verified` نگه
می‌دارد؛ policy هفت برنامه و renderer کاندیدا خودکار به این mirror تغییر نمی‌کنند.
جایگزینی digest در GitOps فقط با staging/admission/شبکه/PKI/storage/recovery معتبر
و PR promotion مستقل انجام می‌شود. فایل auth موقت runner پاک و فقط SBOM/scan/
provenance/receipt عمومی ۳۰ روز نگهداری می‌شوند؛ Root/Shamir/کلید خواندن VPS در آن نیستند.

مراجع نسخهٔ دقیق: [Cosign copy 3.0.6](https://github.com/sigstore/cosign/blob/v3.0.6/cmd/cosign/cli/copy.go)،
[platform selection 3.0.6](https://github.com/sigstore/cosign/blob/v3.0.6/pkg/oci/platform/platform.go)،
[attestation 3.0.6](https://github.com/sigstore/cosign/blob/v3.0.6/pkg/cosign/attestation/attestation.go).
Context7 و سورس رسمی همین tag برای رفتار copy/attestation تطبیق داده شدند.

### گزارش تغییر مقصد خصوصی

Architecture review mode: full-read
Architecture document version/commit: main@e5e932d1f63faca4dea2054b551a60dde4b357b1
Architecture sections reviewed: supply chain, secrets, runtime, testing, readiness, delivery
Search terms used: platform-openbao, PACKAGE_API, private, mirror, visibility
ADRs reviewed or changed: ADR-0011/0017/0045 reviewed; None changed
Changed bounded context/module: platform OpenBao candidate publisher/validator/tests/runbook
Contracts changed: exact candidate mirror name only; upstream pin and seven-application contracts unchanged
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: unchanged bounded tool/API/job deadlines
Retry/cancellation/concurrency behavior: unchanged; no force, overwrite, new retries or package deletion
Kafka/event and idempotency behavior: Not applicable
Security impact: reject old public mirror; require actual new-package private visibility before scan/sign
Istio identity and authorization impact: None; no workload/admission promotion
Logging and PII impact: unchanged finite diagnostics; no raw credentials/provider output
Observability added or changed: None
Build/CI/architecture enforcement changed: publisher and validator exact repository expectations; existing CI retained
Tests executed: focused publisher/artifact regression Failed before change, Passed after; final PR/main CI tracked in PR #169
Architecture deviations: None
Rollback considerations: source revert cannot authorize signing public package; existing package and VPS untouched

پیش‌نیاز موجود: آزمون Kubernetes بنیاد OpenBao در pipeline Repository baseline اجرا می‌شود.
این آزمون از credential واقعی، VPS یا Root مالک استفاده نمی‌کند؛ نتیجهٔ آن فقط
برای API/PVC/TLS/probe است و به‌تنهایی staging مصوب یا مجوز promotion نیست.

### استفاده از آزمون خودکار Kubernetes

در GitHub ← Actions ← Repository baseline ← Run workflow، branch/commit موردنظر
را انتخاب کنید. job جدید `OpenBao Kubernetes foundation` اجباری است؛ شکست آن
`Baseline verify` را رد می‌کند. PR و main نیز خودکار همین آزمون را اجرا می‌کنند.
دستور داخلی job برای runner موقت است؛ روی VPS یا لپ‌تاپ اجرا نکنید:

```bash
python3 scripts/production/rehearse_openbao_kubernetes.py --ci \
  --tools-dir "$RUNNER_TEMP/openbao-kubernetes-tools" \
  --receipt "$RUNNER_TEMP/openbao-kubernetes-receipt.json"
```

kind 0.32.0 و kubectl 1.35.6 با SHA256 ثبت‌شده در `infrastructure/kind/pins.env`
نصب می‌شوند. node همان digest قبلی kind/Kubernetes 1.35.5 است؛ **این آزمون
جای شواهد K3s 1.35.6+Calico+Istio+Kyverno هدف را نمی‌گیرد**. CRDهای Istio از
chart vendored دارای hash نصب می‌شوند تا schema با API واقعی بررسی شود؛ mesh
یا admission امضای artifact در این fixture راه‌اندازی نشده است. default CNI
kind اثبات اجرای NetworkPolicy نیست؛ این محدودیت در receipt ثبت می‌شود.

آزمون، همان candidate و digest OpenBao را بدون تغییر security context و منابع
اجرا می‌کند: پذیرش Pod محدود و رد privileged، خواندن Secret با fsGroup، TLS/SAN
و hostname اشتباه، عدم Ready/restart در حالت sealed، Shamir سه سهم/آستانه دو،
ACL منفی، restart، نگهداری همان PVC پس از scale صفر و خواندن داده پس از بازگشت،
لغو root token و بررسی عدم نشت در stdout container. همهٔ secretها مصنوعی‌اند؛
کلیدها، سهم‌ها، tokenها، kubeconfig و خروجی خام در log/artifact منتشر نمی‌شوند.
cleanup بخشی از موفقیت است؛ cluster و پوشهٔ خصوصی حذف می‌شوند و تنها receipt
عمومی `openbao-kubernetes-<run_id>-<attempt>` به مدت ۳۰ روز نگهداری می‌شود.
هنگام شکست نام مرحلهٔ ثابت عمومی منتشر می‌شود و receipt موفق ایجاد نمی‌شود.
فقط در schema و پیش از ساخت TLS/Secret، خطای عمومی API به‌صورت escaped و با
سقف ۴KiB نمایش داده می‌شود؛ پس از آن diagnostics خام همیشه بسته است.
در timeout/cancel، runner موقت GitHub جمع‌آوری می‌شود؛ این ابزار اصلاً context
یا credential یک کلاستر موجود را استفاده نمی‌کند.

حجم دادهٔ fixture کوچک است؛ PVC local-path سقف واقعی هشت GiB را تضمین نمی‌کند.
quota/پرشدن دیسک، snapshot خارج سرور، custody واقعی، staging مصوب، امضا، Argo CD
و نصب روی VPS همچنان مرحله‌های جداگانهٔ commissioning هستند. Root قبلی مالک
و پورت MCP ‏۲۲۲۲ و SSH/sudo در این آزمون هیچ نقشی ندارند.

1. آماده‌کردن workload تک‌نمونهٔ Raft، PVC با نگهداری هنگام حذف workload،
   TLS خصوصی، ServiceAccount مستقل، منابع محدود، NetworkPolicy و STRICT mTLS.
2. آزمون ساختار و کنترل‌های منفی در pipeline موجود؛ آزمون native TLS/Raft
   موجود نیز الزامی باقی می‌ماند. خروجی renderer فقط candidate است، نه مجوز deploy.
3. بررسی storage class، PKI/SAN، mesh/admission و staging همان digest،
   SBOM/scan/signature؛ سپس promotion بازبینی‌شده از مسیر Argo CD.
4. init واقعی یک‌باره، تحویل خصوصی سه سهم با آستانهٔ دو، policy محدود، لغو root
   token، snapshot رمز‌شدهٔ ساعتی خارج از سرور و restore مستقل.
5. materialization credential برای exporter ممیزی؛ سپس JIT و آزمون قطع sink،
   expiry/revoke. حذف دسترسی دائمی فقط پس از تأیید جایگزین کامل و مسیر نجات.

وضعیت فعلی: آزمون Kubernetes موقت بنیاد در
[run 37210210133](https://github.com/hasanjodatshandi/HooshiX/actions/runs/37210210133)،
job `111459680073` روی head ‏`8e52592ec7187543b5932f153744da31f751a82e`،
همهٔ ۱۳ check و cleanup را `Passed` ثبت کرد. artifact کوچک عمومی
`openbao-kubernetes-37210210133-1` نتیجه را دارد؛ secret یا فایل fixture ندارد.
دو خطای آماده‌سازی آزمون، نبود ServiceAccount هنگام dry-run Pod و ناسازگاری
ساختاری Pod منفی، در همین PR اصلاح شدند؛ رد منفی اکنون باید صریحاً از PSA باشد.
gateهای final-head و main پس از merge جداگانه بررسی می‌شوند. استقرار
واقعی، recovery عملیاتی و Stage 10 همچنان `Not verified` هستند. کلید WireGuard گزارش‌شده به‌عنوان افشاشده نیاز به
چرخش کنترل‌شده با کنسول نجات فعال دارد؛ کلید یا محتوای فایل آن را در گفتگو
نفرستید. این PR شبکهٔ مدیریت یا پورت MCP را تغییر نمی‌دهد.

## استفاده از بستهٔ عمومی

در checkout بازبینی‌شدهٔ همین PR/نسخهٔ merge‌شده در WSL:

```bash
python3 scripts/production/render_openbao_candidate.py \
  --candidate --storage-class <reviewed-storage-class>
```

`<reviewed-storage-class>` را با نام واقعی storage class هدف جایگزین کنید؛
هیچ نام پیش‌فرضی فرض نشده است. خروجی JSON معتبر Kubernetes List است و فقط
metadata عمومی دارد. آن را مستقیم به `kubectl apply` pipe نکنید؛ ابتدا شواهد
promotion و مسیر Argo CD زیر باید آماده شوند. ابزار هیچ کلید، token یا فایل
credential نمی‌خواند، image دانلود نمی‌کند، فایل نمی‌نویسد و به سرور وصل نمی‌شود.

خروجی شامل Namespace با PSA restricted و Ambient، ServiceAccount بدون token،
ConfigMap مشتق از `openbao-server.json`، Service داخلی headless و StatefulSet
تک‌نمونه است. PVC هنگام حذف/کاهش نمونه نگه داشته می‌شود؛ prune نیاز به تأیید
دارد. راه‌اندازی هیچ init container ریشه یا قابلیت افزوده‌ای ندارد.
پیش‌فرض candidate: درخواست ۲۵۰ میلی‌CPU/۲۵۶MiB، سقف ۱ CPU/۵۱۲MiB، PVC هشت GiB
و tmp حافظه‌ای ۱۶MiB. اینها سقف شروع آزمون هستند، **نه ظرفیت تأییدشدهٔ Production**.
درخواست هشت GiB برای PVC سقف مصرف filesystem را ثابت نمی‌کند؛ برخی storage classها
مثل local-path آن را quota نمی‌کنند. پیش از نصب، enforcement واقعی quota/فضای
رزرو و رفتار پرشدن volume باید آزموده شود؛ storage فاقد کنترل رشد تأیید نمی‌شود.
برای VPS فعلی، [فضای ext4 محدود و راهنمای اجرای محلی](production-openbao-storage-fa.md)
بنیاد این حد است؛ نصب filesystem به‌تنهایی PV، guard mount-loss، OpenBao یا
آمادگی Production را تأیید نمی‌کند.
حجم audit، rotation/export و reserve دیسک باید قبل از استفادهٔ واقعی اندازه‌گیری
و محدود شوند؛ PVC پرشده نباید باعث حذف evidence یا ادامهٔ grant جدید شود.

TLS Secret به نام `openbao-server-tls` باید خارج از Git با `tls.crt`، `tls.key`
و `ca.crt` معتبر آماده شود. SAN لازم شامل IP `127.0.0.1` برای probe داخلی و
DNSهای `openbao.hooshix-secrets.svc` و
`openbao-0.openbao.hooshix-secrets.svc` برای API/Raft است. CA خصوصی bootstrap
باید جدا و کنترل‌شده باشد؛ کلید root CA Istio وارد OpenBao، Kubernetes یا این
Secret نمی‌شود. mount TLS فقط‌خواندنی و mode `0440` با fsGroup اختصاصی است.

probeهای startup/liveness فرمان native `bao read -field=sealed sys/seal-status`
را با TLS معتبر اجرا می‌کنند و فقط exit صفر را زنده می‌دانند؛ این endpoint در
حالت sealed/uninitialized هم قابل خواندن است. readiness از `bao status -format=json`
استفاده می‌کند و فقط exit صفر (unsealed) را می‌پذیرد، نه exit دو (sealed).
خطای TLS/شبکه/فرایند موفق نیست. خروجی فقط metadata عمومی وضعیت است؛ timeout
سه ثانیه و retry صفر است و shell وجود ندارد. unseal دستی دلیل restart مداوم نیست.
StatefulSet از `OnDelete` استفاده می‌کند: تغییر config/image به‌تنهایی pod را
restart نمی‌کند؛ replacement باید پس از snapshot، پنجرهٔ نگهداری و آمادگی
دو سهم با Argo CD و رویهٔ بازبینی‌شده انجام شود. downgrade یا حذف PVC rollback
محسوب نمی‌شود.

## gateهای پیش از نصب

- خروجی روی API هدف Kubernetes 1.35.6 و CRDهای Istio 1.30.3 اعتبارسنجی شود؛
  fsGroup/CSI، TLS خواندنی برای UID 10001، PVC پس از restart و وضعیت sealed
  در staging واقعی آزموده شوند. unit test جای این آزمون نیست.
- digest موجود باید SBOM، تصمیم vulnerability و signature/provenance مورد قبول
  admission داشته باشد؛ image upstream یا آزمون Docker به‌تنهایی مجوز promotion
  نیست. سیاست Kyverno باید namespace جدید را نیز پوشش دهد؛ policy فعلی platform
  به‌طور خودکار همهٔ namespaceهای جدید را پوشش نمی‌دهد.
- NetworkPolicy هر دو جهت و AuthorizationPolicy دسترسی client را بسته‌اند؛ فقط
  DNS روی TCP/UDP 53 به podهای kube-dns در kube-system مجاز است تا نام Raft/API
  قابل حل باشد. labels/resolver واقعی هدف نیز باید پیش از نصب تطبیق داده شوند.
  kube exec/port-forward مسیر کنترل Kubernetes است، نه اثبات workload mTLS.
  قبل از ESO/host exporter، هویت، مسیر ارتباط، TokenReview محدود، trust domain،
  deadline، CA و policy دقیق آن edgeها در یک تغییر بازبینی‌شده اضافه و آزموده شوند؛
  namespace-wide allow یا خاموش‌کردن STRICT راه‌حل نیست.
- فقط پس از این شواهد، promotion PR تعریف فعال را به ریشهٔ Argo CD اضافه کند.
  Secretهای واقعی، سهم‌ها و root token در این PR یا CI قرار نمی‌گیرند.
- snapshot رمز‌شدهٔ ساعتی، export ممیزی، هشدار دیسک/backlog، sealed/startup،
  rotation گواهی و restore خارج از failure domain باید واقعاً اجرا شوند.

## آزمون و گزارش بازبینی

```bash
python3 -m unittest discover -s scripts/production/tests -p 'test_openbao*.py'
make production-verify
```

pipeline موجود `Repository baseline` همهٔ production testها و render عمومی را
اجرا می‌کند. job اجباری `OpenBao TLS and Raft recovery` آزمون native با secretهای
مصنوعی را انجام می‌دهد و اکنون فرمان probe را نیز در حالت sealed/unsealed بررسی
می‌کند. runner موقت cleanup دارد؛ این آزمون را روی لپ‌تاپ/VPS با دادهٔ واقعی
اجرا نکنید. وضعیت run همان commit را بررسی کنید؛ run سبز قبلی شاهد تغییر جدید نیست.

### ادامهٔ بررسی artifact در CI

job اجباری `OpenBao pinned artifact security` در `Repository baseline` روی PR،
main، اجرای روزانه و اجرای دستی فعال است. ابزارهای Syft 1.51.0 و Grype 0.117.0
از installer با checksum موجود نصب می‌شوند؛ تصویر عمومی دقیق Linux/amd64 یک‌بار
catalog می‌شود و SBOMهای Syft و CycloneDX تولید می‌شوند. دیتابیس در همان run
به‌روز می‌شود؛ update ناموفق، دیتابیس نامعتبر/قدیمی‌تر از ۱۲۰ ساعت (پیش‌فرض
نسخهٔ پین‌شده)، خطای scanner یا High/Critical بدون استثنا job و gate نهایی را
رد می‌کند. این حد صرفاً معیار evidence کاندیداست، نه تأیید freshness سیاست Production.

برای استفاده: در GitHub ← Actions ← Repository baseline ← Run workflow، branch
موردنظر را انتخاب کنید. نتیجهٔ همین commit و job بالا را ببینید و artifact
`openbao-artifact-<run_id>-<attempt>` را دانلود کنید. SBOMها، گزارش کامل Grype،
زمان ساخت DB و نسخهٔ ابزارها نگه‌داری می‌شوند؛ receipt موفق شامل digest، commit،
hash فایل‌ها، زمان اسکن و شمارش severity است. artifact به مدت ۳۰ روز موجود است.
در run ناموفق گزارش عمومی را بررسی کنید؛ نبود receipt، موفقیت نیست. به‌روزکردن
digest یا پذیرش ریسک نیازمند بررسی مستقل است؛ gate را خاموش نکنید.

این بررسی نصب، امضای artifact، آزمون staging یا اجازهٔ promotion نیست؛
receipt صریحاً promotion و signature/provenance را `Not verified` ثبت می‌کند.
هیچ کلید Production برای این job لازم نیست؛ دانلود فقط روی runner موقت GitHub
انجام می‌شود و job هیچ دسترسی OIDC/signing/registry-secret ندارد.

مراجع رسمی نسخه‌ها:
[Syft image metadata](https://github.com/anchore/syft/blob/v1.51.0/syft/source/image_metadata.go)،
[Grype DB status](https://github.com/anchore/grype/blob/v0.117.0/grype/vulnerability/provider.go)،
[Grype freshness](https://github.com/anchore/grype/blob/v0.117.0/cmd/grype/cli/options/database.go).
Context7 برای CLI اسکن و DB freshness استفاده شد و schema با tagهای دقیق تطبیق یافت.

نتیجهٔ واقعی run `37186602660` روی commit `d1cbce42`:
job اسکن `Failed` شد (exit 2)؛ ۸ High و ۲ Critical روی
`libssl3/libcrypto3=3.5.7-r0`، با fix گزارش‌شدهٔ `3.5.8-r0` وجود دارد.
این نتیجه مربوط به digest قبلی نسخهٔ 2.6.1 است. اصلاح در همان PR #163 نسخهٔ
امنیتی رسمی 2.6.4 را در شاخهٔ 2.6.x انتخاب می‌کند؛ ADR-0011، baseline، قراردادها
و تست‌ها هم‌زمان به‌روز می‌شوند. image و manifest/config با hash دقیق و metadata
نسخه/commit بررسی شدند؛ نتیجهٔ UBI در انتهای این راهنما ثبت شده است. نصب نکنید.
اسکن و بازیابی native باید روی همان digest جدید تکرار شوند. این خطا
نباید با suppression، `--only-fixed` یا خاموش‌کردن gate دور زده شود.

Architecture review mode: full-read
Architecture document version/commit: main@58c067ecac27b5fdbab93801c765d59b282b3404
Architecture sections reviewed: platform, runtime/deployment, security/secrets, readiness, capacity, recovery, delivery
Search terms used: OpenBao, Raft, Shamir, PVC, GitOps, JIT, audit, promotion
ADRs reviewed or changed: ADR-0011, ADR-0030, ADR-0042..0045 reviewed; None changed
Changed bounded context/module: Production secret-authority foundation candidate
Contracts changed: public review-only JSON Kubernetes List renderer CLI
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: status client 3s, probe 5s, finite startup/liveness thresholds
Retry/cancellation/concurrency behavior: zero CLI retries; one replica; controlled OnDelete replacement
Kafka/event and idempotency behavior: Not applicable
Security impact: no secret generation/reading, TLS required, non-root bounded retained storage
Istio identity and authorization impact: dedicated SA, Ambient/STRICT, deny-all pending reviewed client edges
Logging and PII impact: probe output discarded; existing non-raw audit configuration unchanged
Observability added or changed: startup/readiness/liveness only; exporter/alert/capacity Not verified
Build/CI/architecture enforcement changed: existing production unit/render gate and native recovery probe checks
Tests executed: 18 focused OpenBao tests and 165 production tests Passed locally; PR #162 final-head and post-merge main baseline (37183798133) and frontend E2E (37183797999) Passed; Kubernetes runtime Not verified
Architecture deviations: None; candidate is deliberately outside active GitOps roots
Rollback considerations: retain PVC, reviewed prior digest/config, never blind downgrade/delete; live rollback Not verified

Artifact continuation review:

Architecture review mode: full-read
Architecture document version/commit: main@d8aef05a23103a2f8637730dbc105de94cd8d736
Architecture sections reviewed: delivery/supply chain, secret authority, readiness, security tool ownership
Search terms used: OpenBao, SBOM, Grype, Syft, freshness, immutable digest, promotion
ADRs reviewed or changed: ADR-0011 security patch selected; ADR-0042 version reference aligned; ADR-0017/0035/0038/0045 reviewed without semantic changes
Changed bounded context/module: credential-free CI platform artifact evidence
Contracts changed: bounded public candidate scan receipt CLI; existing release chain unchanged
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: CI 12m; DB update 180s, catalog 300s, scan 180s
Retry/cancellation/concurrency behavior: single explicit DB update, no layered retry; isolated disposable runner
Kafka/event and idempotency behavior: Not applicable
Security impact: exact public digest, nonempty SBOM, freshness and severity fail closed; no Production credentials
Istio identity and authorization impact: Not applicable; no runtime identity or grants changed
Logging and PII impact: public upstream package/CVE inventory only; no user data or secrets
Observability added or changed: hash-bound candidate receipt and retained failed scan reports
Build/CI/architecture enforcement changed: required artifact job plus Baseline aggregation; negative contract tests
Tests executed: 9 artifact tests and all 176 production tests Passed locally; context/index/diff/script static verification Passed; rejected 2.6.1 and UBI scans Failed; current distroless artifact security and native TLS/Raft recovery Passed at 4bc15ff / run 37189671503, frontend E2E Passed in run 37189671363; final protected PR/main runs remain merge evidence
Architecture deviations: None; no platform promotion or application release trust expansion
Rollback considerations: source change revert only; no live deployment/data/access changes

منابع: Context7 برای OpenBao فراخوانی شد و رفتار exitهای status، پارامترهای CA،
timeout و retry با مستندات رسمی tag دقیق 2.6.4 تطبیق داده شد:
[status](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/commands/status.mdx)،
[CLI](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/commands/index.mdx)،
[configuration](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/configuration/index.mdx).

انتخاب patch با [release امنیتی رسمی 2.6.4](https://github.com/openbao/openbao/releases/tag/v2.6.4)
و مستندات Context7 تطبیق داده شد؛ نسخهٔ major/minor، Shamir 3/2، Raft/PVC،
سیاست ممیزی، TLS و عدم تماس hot-path تغییری ندارند. downgrade کور با data/PVC
جدید مجاز نیست؛ برای deployment آینده، snapshot رمز‌شده و restore مستقل لازم است.
این pipeline صرفاً CI disposable است و خودش Argo CD/Production را اجرا نمی‌کند.

### گزارش تغییر آزمون Kubernetes

Architecture review mode: full-read
Architecture document version/commit: main@d086adcb41d4d7c6e71e5eb1a54e86664ea8046e
Architecture sections reviewed: platform, runtime/deployment, secrets, testing, readiness, delivery, capacity
Search terms used: OpenBao, PVC, fsGroup, PSA, sealed, kind, GitOps, staging
ADRs reviewed or changed: ADR-0002/0011/0017/0042/0045 reviewed; None changed
Changed bounded context/module: disposable CI OpenBao Kubernetes foundation
Contracts changed: CI-only CLI and public receipt; no production contract
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: job 15m; creation 240s; API 10s; probes 3s; cleanup 120s
Retry/cancellation/concurrency behavior: finite readiness polling; no API write retries; unique isolated cluster
Kafka/event and idempotency behavior: Not applicable
Security impact: synthetic secrets only, pinned tools/images, explicit kubeconfig/context, no real credentials
Istio identity and authorization impact: CRD schema only; actual mesh enforcement Not verified
Logging and PII impact: fixed steps; only bounded escaped pre-secret schema errors; private native output withheld; successful public receipt only
Observability added or changed: commit/digest-bound public fixture receipt
Build/CI/architecture enforcement changed: blocking Kubernetes job in existing baseline; checksum and cleanup checks
Tests executed: 190 production unit tests, production contract verifier, repository/context/static/diff checks Passed locally; Kubernetes job 111459680073 in run 37210210133 Passed at 8e52592; final-head/main protected checks remain separate merge evidence
Architecture deviations: None; fixture explicitly does not authorize staging/promotion
Rollback considerations: source revert only; no live data/SSH/sudo change; disposable cluster/private files removed

Tool documentation: Context7 kind/OpenBao docs and exact chart/tool integrity sources;
kind release asset SHA256 from official `v0.32.0` metadata, kubectl SHA256 from
`https://dl.k8s.io/release/v1.35.6/bin/linux/amd64/kubectl.sha256`.

در run `37187139233` نسخهٔ Alpine 2.6.4 بازیابی را گذراند، ولی اسکن یک High
برای zlib (`CVE-2026-85091`) نشان داد. کاندیدای ردشده variant رسمی
`ghcr.io/openbao/openbao-ubi` نسخهٔ 2.6.4، پایهٔ UBI10 minimal است؛ source commit
باینری همان است و هیچ image سفارشی یا suppression ایجاد نشده. digest/index و
حجم فشردهٔ حدود ۱۴۲ MB در `openbao-image.json` ثبت شده‌اند. آزمون‌های نسخهٔ قبلی
برای این variant کفایت ندارند؛ jobهای اسکن و بازیابی خود آن باید سبز شوند.
اسکن شامل بسته‌های RPM/OS و باینری Go است؛ default dev entrypoint همچنان override
می‌شود و UID، filesystem و منابع محدود تغییر نکرده‌اند. هیچ دانلود image روی
WSL/VPS شما انجام نمی‌شود. ظرفیت دیسک و runtime واقعی قبل از نصب جداگانه بررسی شود.

نتیجهٔ UBI روی commit `8ba84ee7`، [run 37187635536](https://github.com/hasanjodatshandi/HooshiX/actions/runs/37187635536):
بازیابی `Passed`؛ اسکن `Failed` با ۱۰ match از پنج CVE:
`CVE-2026-76642` روی libmount/libfdisk/libblkid؛ `CVE-2026-75804` و
`CVE-2026-84782` روی openssl-libs/openssl؛ `CVE-2026-76641` روی expat؛
`CVE-2026-86145` روی pcre2-syntax/pcre2. scanner برای همهٔ آن‌ها `not-fixed`
و بدون نسخهٔ اصلاح‌شده گزارش داده است. این شمارش بسته‌هاست، نه ۱۰ CVE مستقل.
تمام jobهای دیگر baseline گذشتند ولی gate نهایی به‌درستی رد شد.

برای ادامه، همین digest را نصب یا دوباره بدون تغییر تست نکنید. یک اصلاح artifact
یا adjudication مستند طبق سیاست فعلی لازم است و سپس scan/recovery روی digest جدید
تکرار می‌شود. این نتیجهٔ تاریخی UBI مجوز نصب آن نیست. جایگزینی distroless نیازمند
بررسی سازگاری sealed/TLS/probe بود؛ این بررسی و نتیجهٔ جدید در بخش بعد آمده است.
نتیجهٔ UBI اجازهٔ init واقعی، نصب JIT یا حذف sudo را نمی‌دهد.
مراجع بررسی اولیهٔ vendor:
[Expat](https://access.redhat.com/security/cve/cve-2026-76641)،
[OpenSSL DTLS](https://access.redhat.com/security/cve/cve-2026-84782).
وجود توضیح vendor به‌تنهایی false-positive یا exception را اثبات نمی‌کند.

### کاندیدای فعلی: distroless رسمی و probe بدون shell

ADR-0011 اکنون variant رسمی `openbao-distroless:2.6.4` را انتخاب می‌کند؛ باینری
همان release است، image سفارشی ساخته نمی‌شود و حجم فشرده حدود ۷۷ MB است.
نیازی به shell نیست: startup/liveness فرمان `bao read -field=sealed sys/seal-status`
را مستقیم اجرا می‌کنند؛ readiness فرمان `bao status -format=json` را اجرا می‌کند و
فقط exit صفر را قبول می‌کند. وضعیت sealed/uninitialized باعث restart بیهوده نمی‌شود
ولی اجازهٔ Ready شدن نمی‌دهد. خروجی probe فقط metadata عمومی وضعیت است؛ token یا
محتوای secret خوانده نمی‌شود.

همهٔ probeها همچنان HTTPS loopback، CA نصب‌شده، timeout سه‌ثانیه و صفر retry دارند؛
TCP/HTTP probe، خاموش‌کردن TLS verification، sidecar یا فایل اجرایی جدید نداریم.
CI هر دو مسیر native را با hostname گواهی اشتباه هم آزمایش می‌کند و انتظار خطا دارد.
روی head `4bc15ff56877d821bdce649fa0eb95b591fa97c5`،
[run 37189671503](https://github.com/hasanjodatshandi/HooshiX/actions/runs/37189671503)
اسکن امنیتی digest دقیق و بازیابی native TLS/Raft را مستقل با نتیجهٔ `Passed` اجرا کرد؛
[frontend E2E](https://github.com/hasanjodatshandi/HooshiX/actions/runs/37189671363)
هم `Passed` شد. ۱۷۶ تست production و بررسی static اسکریپت‌ها نیز محلی `Passed` شدند.
این مانع کاندیدای image/probe رفع شده است؛ وضعیت نهایی PR/main باید پیش از merge
بررسی شود. نصب واقعی همچنان به امضا/provenance، staging، storage/TLS/custody و
recovery واقعی نیاز دارد؛ SSH، پورت MCP و sudo تغییری نکرده‌اند.

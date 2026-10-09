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
VPS installation: Passed at main eabc4475ed3065a3db1c2bf782383e9ef99e23e6 on 2026-10-08; sealed, not initialized
Production readiness: Not verified

receipt عمومی `hooshix-platform-d6c52f209c034d019f67ee38b727724a.json` نصب mesh،
Kyverno 1.19.1، OpenBao، retained PVC، TLS/probes و حفظ audit/storage guard و
سرویس‌ها را `Passed` ثبت کرده است. نصب را تکرار نکنید؛ قدم بعدی
[فعال‌سازی با custody رمزدار](production-openbao-activation-fa.md) است.

### مانع دریافت اعتماد Sigstore روی VPS

بررسی فقط‌خواندنی ۸ اکتبر، credential و pull چهار image را Passed کرد، اما
آماده‌سازی TUF در کنترلر با HTTP 403 از `tuf-repo-cdn.sigstore.dev` شکست خورد.
همان metadata عمومی در `https://sigstore.github.io/root-signing/` از میزبان و
فضای شبکهٔ کنترلر HTTP 200 دارد. این مقصد، مخزن preproduction رسمی Sigstore
است، نه یک CA یا signer جدید؛ پذیرش آن باید همچنان از Root عمومی تعبیه‌شدهٔ
Sigstore و زنجیرهٔ امضا/نسخه/انقضای TUF عبور کند. اعتبار HTTP به‌تنهایی شاهد
اعتماد نیست. چهار policy امضا همین mirror را صریحاً می‌گیرند؛ هیچ `tuf.root`،
CA سفارشی، `--staging` یا گزینهٔ نادیده‌گرفتن transparency/SCT اضافه نمی‌شود.
Root آفلاین HooshiX ربطی به این Root عمومی Sigstore ندارد و جابه‌جا نمی‌شود.
این مقصد پیش از CDN منتشر می‌شود؛ دسترس‌پذیری یا تازگی آن تضمین نمی‌شود و
metadata نامعتبر/منقضی admission تازه را fail-closed می‌کند، نه اینکه گیت حذف شود.
CI همان تصویر را با Root نامعتبر رد و پس از بازگرداندن Root تعبیه‌شده قبول
می‌کند؛ خطوط `PLATFORM_TUF_UNTRUSTED_ROOT=Denied` و
`PLATFORM_SIGNED_TUF_MIRROR=Passed` همراه تمام negativeهای signer/provenance/SBOM
لازم‌اند. تا receipt نصب واقعی، OpenBao و Production همچنان Not verified هستند.

استفاده: پس از موفقیت pipeline و merge، بستهٔ جدید را با دستور ساخت همین راهنما
و `--resume-bundle` به **بستهٔ اصلی** نصب ناموفق متصل کنید، نه بستهٔ اصلاحی میانی.
فقط installer بازبینی‌شده را با ورود محلی رمزها اجرا کنید؛ نیازی به تعویض توکن
صحیح GHCR یا امضای دوبارهٔ CA نیست. پس از اعمال policy جدید، installer فقط دو
Deployment متعلق به همین نصب (`kyverno-admission-controller` و
`kyverno-reports-controller`) را با همان image و Pod spec rollout می‌کند؛ singleton
کتابخانهٔ TUF مقصد/خطای نخست را در حافظه نگه می‌دارد و بدون process تازه می‌تواند
باز هم به CDN قبلی وصل شود. هر rollout حداکثر ۱۲۰ ثانیه دارد؛ گیت‌ها خاموش نمی‌شوند
و نبود controller درخواست‌های مشمول را fail-closed می‌کند. قبل/بعد، UID و spec
حفظ می‌شوند و CI همین مسیر را با `PLATFORM_VERIFIER_PROCESS_REFRESH=Passed` اجرا
می‌کند. annotation عملیاتی restart تنها تغییر template metadata است، نه مجوز drift
در spec یا حذف cache. در rollback بازبینی‌شده فقط فیلد `tuf` از
چهار attestor برداشته و همین دو controller rollout می‌شوند تا CDN پیش‌فرض برگردد؛ اگر هنوز 403 دهد درخواست تازه
رد خواهد شد. marker، workload/PVC، CA و credential برای rollback حذف نمی‌شوند.

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

همین آزمون ابتدا Kyverno ۱٫۱۸٫۲ فعلی VPS را نصب و سپس با chart رسمی ۳٫۹٫۱ به
۱٫۱۹٫۱ ارتقا می‌دهد. نسخهٔ جدید اشکال اعتماد به annotation نتیجهٔ image verification
را رفع می‌کند؛ آزمون annotation جعلی نیز باید رد شود. پنج تصویر upstream با
امضای رسمی release و digest ثابت تأیید می‌شوند. chart و Helm ۴٫۲٫۴ نیز hash ثابت دارند.
طبق [راهنمای رسمی ارتقا](https://kyverno.io/docs/installation/upgrading/)، تغییر صرف
image برای ارتقا کافی نیست. نصب قبلی به release محدود Helm منتقل می‌شود؛ CRDها
با حفظ resourceVersion و نسخه‌های ذخیره‌شده به‌روزرسانی می‌شوند، نه حذف/ساخت مجدد.
وجود resource متعلق به نصب دیگر یا نسخهٔ ذخیره‌شدهٔ ناسازگار عملیات را متوقف می‌کند.
policy موجود باید با همان UID و spec پس از ارتقا باقی بماند. adoption نخست فقط
قبل از workloadهای برنامه مجاز است؛ retry همان release و digest مجاز، حذف خودکار نیست.
در شکست، uninstall/rollback خودکار یا حذف policy/CRD انجام نمی‌شود.
CRDهای CEL رسمی نسخهٔ قبلی label مالکیت ندارند؛ در این مورد فقط fingerprint کامل
spec رسمی قدیم/جدید با نرمال‌سازی دو default شناخته‌شدهٔ Kubernetes پذیرفته می‌شود،
نه صرفاً نام مشابه. schema یا conversion متفاوت خودکار بازنویسی نمی‌شود.

پاسخ عمومی دو CRD روی VPS حدود ۲٫۲۹ MB است. سقف خواندن تک CRD عمومی باید
۴ MiB باشد؛ سقف سایر فرمان‌ها، از جمله Secret، همان ۲ MiB می‌ماند. خروجی خام
در گزارش یا لاگ نمایش داده نمی‌شود و بررسی مالکیت/schema حذف نمی‌شود.

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

خروجی شامل candidate و شش policy پایدار CEL است. namespaceهای بسته، SA/image
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
رسید OpenBao از workflow موفق main در ۷ اکتبر با revision دقیق
`ffc299093b965450e4ef433a2d8392a01c404e0c` است؛ digest ثابت تغییر نمی‌کند.
publication و زمان ساخت پایگاه اسکن هر دو باید در پنجرهٔ پنج‌روزه باشند.
با امضای مجدد همان digest، انتخاب payload چند attestation ممکن است مبهم شود؛
revision دقیق رسید همچنان باید در آزمون native همان تصویر پذیرفته شود.
صرف تازه‌بودن metadata مجوز نصب نیست. شرط امضا، SBOM و freshness پنج‌روزه
حذف نشده است؛ evidence منقضی یا تغییر image، انتشار و staging تازه لازم دارد.
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
در CNI و ztunnel، mount خودکار token با mount صریحِ فقط‌خواندنی و token
projected یک‌ساعته جایگزین شده تا سیاست دقیق با mount تزریقی تصادفی تعارض نداشته
باشد. هویت و RBAC قبلی حفظ می‌شود و mount/حجم‌های این دو جزء در policy پین هستند.
امضای هر attestation در متغیر request-local یک بار بررسی می‌شود؛ payload فقط
پس از تأیید امضا خوانده می‌شود. بررسی cold تصویر خصوصی در آزمون به مهلت اولیهٔ
۱۵ و سپس ۳۰ ثانیه رسید؛ برای OpenBao بررسی image، provenance و SBOM در سه
webhook مستقل با همان شرط‌های امنیتی انجام می‌شود. مهلت هر webhook برابر ۳۰
ثانیه، درخواست API برابر ۱۲۰ و فرمان محلی ۱۳۵ ثانیه است؛ هیچ بررسی حذف نمی‌شود.
در خطا یا timeout، پذیرش همچنان fail-closed است؛ deadline بیست‌دقیقه‌ای نصب و
SLO درخواست‌های کاربران/authorization تغییری نمی‌کند. این بودجه، شاهد ظرفیت
کل stack نیست و آزمون کامل ظرفیت Production همچنان لازم است.

ترتیب اجرا: هدف/audit/encryption/storage موجود، ارتقای بررسی‌شدهٔ Kyverno، pull Secret محدود، شش policy
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
برای ادامهٔ شکستِ نصب‌کننده با نسخهٔ اصلاح‌شده، در دستور ساخت بستهٔ جدید
`--resume-bundle /path/to/previous-public-bundle` را اضافه کنید. سازنده evidence
قبلی را از GitHub احراز می‌کند و تمام desired state را با بستهٔ جدید مقایسه می‌کند؛
فقط source/evidence جدید، افزودن دقیق policy خروجی HTTPS بررسی تصویر و افزودن
`cosign.tuf.mirror` دقیق فوق، بدون override ریشه، مجاز است. هر تغییر دیگری در image،
signer/issuer، validationهای admission، منابع یا storage
این مسیر را متوقف می‌کند. روی VPS فقط marker دقیق همان source/hash قبلی پذیرفته
می‌شود؛ marker، PVC، CA و داده‌ها حذف یا جایگزین نمی‌شوند. evidence جدید همچنان
باید برای tree دقیق اصلاح‌شده تازه و موفق باشد؛ این گزینه گیت CI را دور نمی‌زند.
desired state عمومی همین bundle باید در reconciliation بعدی GitOps از منبع
reviewed Git حفظ شود؛ استثنا، مجوز drift یا مدیریت عادی بدون JIT نیست.

### رفع timeout خروجی شبکهٔ بررسی تصویر

اگر میزبان به GHCR وصل می‌شود اما Pod کنترلر Kyverno timeout دارد، policy قدیمی
`private-admission-boundary` را حذف یا باز نکنید. نصب‌کنندهٔ بازبینی‌شده فقط
`kyverno/hooshix-image-verifier-https` را اضافه می‌کند: TCP/443 عمومی IPv4 فقط برای
admission-controller و reports-controller. شبکه‌های خصوصی، loopback، metadata،
CGNAT و reserved در این مجوز تازه مستثنا هستند؛ مجوزهای API/DNS قبلی حفظ می‌شوند.
این NetworkPolicy فیلتر نام دامنه نیست؛ در سطح شبکه، HTTPS عمومی برای این دو
کنترلر ممکن است. TLS و digest/signer/issuer/provenance/SBOM دقیق همچنان اجباری‌اند.
هیچ برنامه، SSH، فایروال میزبان یا تنظیم ایمیلی مجوز تازه دریافت نمی‌کند.

CI همان محدودیت خروجی را روی Calico واقعی بازسازی می‌کند؛ همان Pod امضاشده باید
پیش از اصلاح رد و پس از اصلاح پذیرفته شود و UID/spec سیاست قبلی تغییر نکند.
خطوط `PLATFORM_VERIFIER_RESTRICTED_EGRESS=Denied` و
`PLATFORM_VERIFIER_HTTPS_REPAIR=Passed` شواهد این regression هستند؛ receipt staging
فقط پس از همهٔ آزمون‌های امضا، mesh و OpenBao موفق صادر می‌شود.
بستهٔ جدید را با `--resume-bundle` به بستهٔ عمومی اصلی نصب ناموفق متصل کنید؛
marker قبلی حذف نمی‌شود. policy هم‌نام با مالکیت یا spec متفاوت عملیات را متوقف
می‌کند؛ حذف/بازنویسی خودکار ندارد. تکرار policy دقیق و متعلق به همین نصب بدون write
است. پس از ورود محلی رمزها، مراحل admission → TLS → mesh → storage → OpenBao
ادامه می‌یابند. موفقیت نهایی یعنی OpenBao نصب‌شده ولی sealed و initialize‌نشده؛
آمادگی Production یا اجازهٔ داده واقعی نیست. rollback این مجوز، در صورت نیاز،
باید بازبینی و با احراز هویت مالک انجام شود؛ حذف آن بررسی‌های تازه را fail-closed
می‌کند و policy قدیمی، CA، PVC و داده را نباید حذف کرد.

هیچ کلید، رمز، سهم Shamir یا token را در گفتگو ارسال نکنید.

### ادامه پس از تعارض Secret خواندن GHCR

Secretهای `hooshix-ghcr-read` فقط در سه namespace همین نصب مدیریت می‌شوند.
تکرار نصب یا تعویض token نباید به حذف Secret یا اجبار مالکیت فیلدها نیاز داشته
باشد. اصلاح این مسیر، نام/namespace، نوع، دو label مالکیت و تنها کلید داده را
کنترل می‌کند؛ مقدار یکسان بدون نوشتن مجدد پذیرفته می‌شود. تغییر مقدار با
`resourceVersion` همان خواندن انجام می‌شود تا تغییر هم‌زمان از دست نرود.
Secret متعلق به دیگری، immutable یا پاسخ فاقد نسخه برای تغییر حفظ می‌شود و
نصب متوقف می‌شود. رمز/token همچنان فقط در prompt مخفی محلی و stdin API است.

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
Tests executed: local production/static/context verification Passed; complete native staging and target installation require executed receipts
Architecture deviations: None; staging evidence never substitutes target validation or traffic readiness
Rollback considerations: no VPS changes; disposable cleanup deletes only its unique kind cluster

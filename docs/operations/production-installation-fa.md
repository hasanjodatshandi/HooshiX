# نصب مستقل HooshiX روی سرور جدید — راهنمای فارسی

این راهنما برای مالک سامانه است. مراحل را به ترتیب انجام دهید. دستورهای `PowerShell` روی
Windows، دستورهای `bash` روی Linux/WSL، و دستورهای بخش سرور فقط در نشست مجاز همان سرور اجرا می‌شوند.
هر جا `Not verified` نوشته شده، هنوز اجازه بازکردن ترافیک Production وجود ندارد.

## ۱. چه چیزهایی را برای مشتری بعدی می‌توان استفاده کرد؟

| قابل استفاده مجدد | برای هر نصب از نو بسازید |
| --- | --- |
| سورس، تست‌ها، اسکریپت‌ها، قراردادهای زیرساخت و این راهنما | Root CA و intermediate و کلیدهای خصوصی آن نصب |
| فرآیند ساخت و امضای artifact | WireGuard هر دستگاه، هویت انسانی و دسترسی JIT |
| قالب‌های deployment و سیاست‌های امنیتی | OpenBao، unseal shares، رمزهای DB و کلیدهای کاربردی |
| نسخه‌های مصوب Technology Baseline | دامنه، IP، peer inventory، backup، monitoring و تنظیمات provider |

کلید/دیتابیس/حساب provider مشتری قبلی را برای مشتری جدید کپی نکنید. نصب جدید به معنی انتقال
داده مشتری قبلی نیست. برای محصول مشتق‌شده، تغییرات کد و نام دامنه/هویت/registry باید در PR
همان محصول بررسی شوند؛ `trustDomain` و `meshID` فعلی را خودسرانه عوض نکنید.

## ۲. برگه اطلاعات نصب را پر کنید

یک شناسه کوتاه مثل `customer-alpha` انتخاب کنید: ۳ تا ۴۰ حرف کوچک انگلیسی، عدد یا `-`؛
حرف اول انگلیسی باشد. برای دو نصب مستقل یک شناسه تکراری نگذارید.

اطلاعات **عمومی** زیر را جدا از رمزها ثبت کنید:

1. شناسه نصب، Git revision، دامنه سایت و دامنه ایمیل.
2. IP اصلی، IP دوم در صورت نیاز، gateway، interface و تنظیمات ارائه‌شده توسط میزبان.
3. CPU/RAM/دیسک واقعی، تعداد کاربران و درخواست هم‌زمان به مدل، حجم گفتگو و مدت نگهداری داده.
4. دستگاه‌ها و مالکان WireGuard؛ مسئول platform/security و مسیر بازیابی اضطراری.
5. registry و هویت signer؛ مقصد off-site backup و مانیتور خارج از سرور.
6. provider مدل، Email، SMS و **نام مرجع** credential آن‌ها؛ خود credential را اینجا ننویسید.
7. IP/CIDR دقیق L4، قابلیت PROXY protocol v2 و حفاظت حجمی upstream.

رمزها، private key، token و unseal share در این برگه، Git، chat یا CI قرار نمی‌گیرند.
برای مقدار واقعی secrets از جریان مصوب OpenBao و دسترسی کوتاه‌مدت استفاده کنید.

## ۳. سورس درست و نسخه ابزارها

در checkout همان محصول، در Linux/WSL:

```bash
git status --short --branch
git fetch origin --prune
make context-verify
make context-bootstrap
make production-verify
```

اگر worktree تغییر دارد، آن را قبل از update بررسی کنید؛ `reset --hard`، `clean` یا overwrite
راه‌حل عمومی نیست. تغییرات نصب از branch و PR می‌گذرند. نسخه‌های platform را از
[Technology Baseline](../technology/technology-baseline.md) و
[compatibility matrix](../technology/production-compatibility-matrix.md) بگیرید؛ «آخرین نسخه» را
به جای نسخه مصوب نصب نکنید. این راهنما نسخه‌نامه مستقل از آن فایل‌ها نیست.

## ۴. بسته ساخت کلید را روی Windows آماده کنید

این مرحله فقط **بسته عمومی ابزار** می‌سازد؛ هیچ کلیدی تولید نمی‌کند. Windows x64،
Windows PowerShell 5.1 و Git for Windows لازم است. سورس ابزار باید قبلاً commit و بررسی شده باشد.
اگر Execution Policy سازمان اجرای فایل را منع کرد، همان سیاست را با مدیر سیستم حل کنید؛
دستور تغییر سراسری Execution Policy یا خاموش‌کردن کنترل امنیتی لازم نیست.

در PowerShell، از ریشه سورس پروژه:

اگر checkout اصلی در WSL است، برای **ساخت بسته عمومی ابزار** یک checkout محلی Windows
از revision بررسی‌شده داشته باشید؛ checkout اصلی اپلیکیشن همچنان WSL است. مثال برای نصب جدید:

```powershell
git clone https://github.com/hasanjodatshandi/HooshiX.git D:\HooshiX\PublicToolSource
cd D:\HooshiX\PublicToolSource
git checkout <REVIEWED_GIT_SHA>
```

از داخل مسیر `\\wsl.localhost\...` اجرا نکنید؛ Windows ممکن است آن را مسیر شبکه‌ای حساب کند و
طبق `RemoteSigned` رد کند. checkout محلی باید از منبع مورد اعتماد باشد؛ policy را bypass نکنید.
در دستور زیر مسیر و شناسه نمونه را با نصب خودتان عوض کنید:

```powershell
.\scripts\production\offline-ca\build-package.ps1 `
  -InstallationId customer-alpha `
  -OutputDirectory D:\HooshiX\customer-alpha-offline-ca
```

والد `D:\HooshiX` باید موجود باشد. مسیر خروجی تازه و خارج از repository انتخاب کنید.
اسکریپت archive رسمی و pin‌شده PortableGit را دانلود، SHA256 آن را بررسی، فقط OpenSSL و license
لازم را استخراج و hash تک‌تک فایل‌ها را بررسی می‌کند. config محدود OpenSSL از سورس خود ابزار
می‌آید و از config نمونه vendor استفاده نمی‌شود. سپس بسته را با `ValidateOnly` کنترل می‌کند.
هیچ برنامه‌ای از archive پیش از بررسی SHA256 اجرا نمی‌شود.

برای اینترنت قطع/دانلود قبلی، `-ArchivePath D:\Downloads\PortableGit-2.55.0.3-64-bit.7z.exe`
اضافه کنید. برای cache عمومیِ یک بسته معتبر قبلی، `-ToolBundleDirectory D:\HooshiX\old-public-package`
قابل استفاده است؛ فقط پنج فایل عمومی pin‌شده از آن خوانده می‌شوند؛ کلیدهای نصب قبلی استفاده نمی‌شوند.
نسخه OpenSSL در `tools.lock.json` ثبت شده و فقط برای این ابزار محدود بررسی شده است؛
قبل از استفاده آتی، تغییرات امنیتی vendor را بررسی و هر update را با PR و آزمون انجام دهید.

باید `PACKAGE_INTEGRITY=PASSED`، `VALIDATE_ONLY=PASSED_NO_KEY_GENERATION` و `ZIP_SHA256=...`
ببینید. SHA256 ZIP را جدا نگه دارید. ZIP را روی رایانه آفلاین ببرید، hash آن را با مقدار
رایانه سازنده مقایسه کنید و از ZIP در یک مسیر محلی تازه استخراج کنید:

```powershell
Get-FileHash D:\HooshiX\customer-alpha-offline-ca.zip -Algorithm SHA256
```

hash خود بسته تضمین اصالت ندارد اگر بسته و hash هر دو از یک منبع نامطمئن آمده باشند؛
مقدار را از دستگاه/کانال سازنده‌ای که به آن اعتماد دارید مقایسه کنید.

## ۵. Root CA، دو پشتیبان و آزمون بازیابی

### نصب فعلی مالک: ادامه با گواهی موجود، بدون تکرار ساخت

مالک در ۲۰۲۶-۱۰-۰۴ استفاده از گواهی موجود را برای `hooshix-production` و فقط profile
`production-single-server` طبق ADR-0002 تأیید کرده است. فایل‌های عمومی
`D:\HooshiX\production-ca-public\root-cert.pem` و `backup-receipt.json` دریافت شده‌اند؛
پوشه `ToOnline` شرط جداگانه نیست و برای این نصب ساخت Root/backup دوباره درخواست نمی‌شود.
SHA256 دقیق **فایل** گواهی مصوب:

```text
f6d49249e221fa49138771c3d86037575f55eee5f581af373f4523008e424b94
```

گواهی روی Windows متصل ساخته شده بود؛ پذیرش مالک این منشأ را تغییر نمی‌دهد.
نگهداری آفلاین و دو پشتیبان، تأیید مالک است؛ آزمون بازیابی مستقل توسط دستیار `Not verified` است.
رسیدهای قدیمی و نام `NOT Production Approved` گواهی بازنویسی نمی‌شوند؛ تأیید جدید در ADR/policy ثبت است.
کلید/رمز Root را آنلاین نیاورید. برای این نصب مرحله ساخت زیر را تکرار نکنید؛ وقتی CSR cluster
آماده شد، از مرحله ۶ با ابزار تازه و همان backup رمزدار روی کامپیوتر آفلاین ادامه دهید.

### نصب جدید یا profile دیگر: ساخت آفلاین طبق روال عادی

روی رایانه آفلاین، ساعت/تاریخ درست را کنترل کنید، کابل شبکه را جدا و Wi-Fi، Bluetooth networking،
VPN و آداپتورهای مجازی را خاموش کنید. ابزار هم نبود آداپتور فعال را بررسی می‌کند؛ این بررسی جای
نگهداری فیزیکی امن رایانه را نمی‌گیرد.

```powershell
cd D:\HooshiX\customer-alpha-offline-ca
.\run-root.cmd
.\run-backup.cmd
```

1. در مرحله Root، `OFFLINE` را بنویسید. رمز تازه را دو بار وارد کنید.
2. رمز ۲۰ تا ۱۲۸ کاراکتر است؛ فارسی/انگلیسی و فاصله مجاز است؛ control character و emoji مجاز نیست.
3. رمز را جدا از USBها نگه دارید. این ابزار رمز را ذخیره نمی‌کند و به DPAPI وابسته نیست.
4. در backup حروف **دو فلش فیزیکی متفاوت** را بدهید. فایل‌های موجود نامرتبط حفظ می‌شوند؛
   برخورد مسیر یا عدم تطابق باعث توقف می‌شود.
5. از هر فلش **جداگانه** پوشه `HooshiX-OfflineRootCA-...` را روی دستگاه آفلاین کپی کنید.
6. `run-verify.cmd` را اجرا کنید و مسیر آن نسخه را بدهید. SHA256 فایل گواهی Root را از
   رسید عمومی `ToOnline/root-public-receipt.json` که جدا نگه داشته‌اید وارد کنید؛ این مقدار
   `root_certificate_sha256` است، با fingerprint داخلی گواهی اشتباه نگیرید.
7. رمز را بزنید. برای **هر دو نسخه** باید `OFFLINE_RECOVERY_TEST=PASSED` بگیرید.
8. دو USB را در دو محل امن جدا نگه دارید. فقط `ToOnline` و receiptهای عمومی به دستگاه آنلاین برمی‌گردند.

کلید اصلی در `%LOCALAPPDATA%\HooshiX\OfflineRootCA\customer-alpha` است؛ RSA4096، PKCS8 رمزدار
AES256/PBKDF2-SHA256 با ۱٬۰۰۰٬۰۰۰ iteration. Root عمر ۱۰ سال تقویمی و `pathlen:1` دارد.
فایل خصوصی `root-key.enc.pem` هرگز به سرور، Kubernetes، OpenBao یا Git منتقل نمی‌شود.
فایل‌ها و رمز باید برای بازیابی مستقل در دسترس مالک بمانند. حذف نسخه خصوصی اضافه فقط پس از
دو بازیابی موفق، بررسی مسیر دقیق و تأیید مالک انجام می‌شود؛ پاک‌کردن فایل تضمین پاک‌شدن snapshot نیست.

## ۶. intermediate را برای همان cluster امضا کنید

کلید intermediate فقط داخل مرز کنترل‌شده `istio-system` همان نصب ساخته و در حالت ذخیره رمزدار
نگهداری می‌شود. ساخت/ورود آن نیازمند دسترسی JIT مصوب است. هنوز installer عمومیِ custody،
import و rotation این بخش در سورس کامل نیست؛ قبل از نصب mesh باید آن بخش تکمیل و آزمون شود.
کلید intermediate را برای گرفتن امضا به رایانه Root نبرید: فقط CSR عمومی و SHA256 آن منتقل می‌شود.

قرارداد CSR: RSA4096 و subject دقیق `CN=customer-alpha Cluster Intermediate CA`.
SHA256 CSR را در سرور با `sha256sum cluster.csr.pem` بگیرید و جدا از فایل مقایسه کنید.
روی رایانه آفلاین دارای Root:

```powershell
.\run-sign.cmd
```

برای Root موجود مالک، بسته تازه را با `InstallationId=hooshix-production` بسازید؛ بسته‌های قدیمی
schema ۱/۲ این استثنا را نمی‌شناسند. فقط ابزارهای عمومی را به دستگاه آفلاین ببرید.
CI بعد از موفقیت fixtureها بسته عمومی همان نصب را به‌عنوان artifact
`hooshix-public-offline-signing-<GIT_SHA>` نگه می‌دارد؛ فقط artifact اجرای موفقِ commit
بازبینی‌شده/merged را استفاده کنید، نه artifact PR ناشناس. SHA256 ZIP در خروجی build ثبت است.
اگر state همان نصب در دستگاه آفلاین موجود نیست، `run-sign.cmd` مسیر پوشه backup قبلی را می‌پرسد:
داخل آن `root-key.enc.pem`، `root-cert.pem` و `backup-receipt.json` باشند.
SHA256 گواهی عمومیِ مصوب بالا و SHA256 مستقل CSR را وارد کنید؛ رمز فقط در دستگاه آفلاین.
این امضای CSR تازه است، نه ساخت دوباره Root یا الزام به تحویل ToOnline.

مسیر CSR و hash تأییدشده را بدهید، سپس رمز Root را وارد کنید. ابزار CSR signature/subject/key
و اعتبار کافی Root را بررسی می‌کند، extensionهای CSR را کپی نمی‌کند، intermediate یک‌ساله با
`CA:true,pathlen:0` و serial تصادفی می‌سازد و chain را بررسی می‌کند. فقط پوشه عمومی
`signed-intermediate-...` به مرز مصوب cluster برمی‌گردد؛ خروجی ناقص در صورت خطا نصب نشود.
`ca-cert.pem`، `cert-chain.pem` و `root-cert.pem` عمومی‌اند؛ `ca-key.pem` در این بسته وجود ندارد.
import و فعال‌سازی mesh فقط پس از بررسی تطابق کلید و گواهی، custody و manifest مصوب انجام شود.
rotation از ۹۰ روز قبل از انقضا با حداقل ۳۰ روز overlap؛ workload TTL برابر ۲۴ ساعت است.
اگر دستگاه اصلی Root از دست رفته است، ابتدا backup را طبق مرحله ۵ روی دستگاه آفلاین verify کنید؛
سپس می‌توانید بدون بازیابی plaintext از همان backup رمزدار امضا کنید:

```powershell
.\offline-ca.ps1 -Action SignIntermediate -BackupDirectory D:\HooshiX-OfflineRootCA-REPLACE
```

در این حالت hash مستقل Root هم خواسته می‌شود؛ مسیر نمونه را با مسیر همان نسخه verify‌شده عوض کنید.

## ۷. ابتدا سرور را بدون تغییر بررسی کنید

در نشست SSH فعلی، دستورهای زیر رمز/کلید یا محتوای log را نمی‌خوانند:

```bash
uname -m
uname -r
nproc
free -h
lsblk -o NAME,SIZE,TYPE,ROTA
df -hT
timedatectl show -p NTPSynchronized
ip -brief address
ip route
ss -lnt
systemctl is-active caddy nginx postfix dovecot k3s wg-quick@wg-hooshix
```

غیرفعال بودن یک سرویس در خروجی یعنی باید علت/نیازش بررسی شود؛ به معنی خراب بودن کل سرور نیست.
روی سرور موجود، ایمیل/Caddy/nginx و پروژه‌های دیگر را پیش از هر تغییر شناسایی کنید.
خروجی raw config/log ممکن است secret داشته باشد؛ آن را در chat/CI قرار ندهید.
RAM/CPU/دیسک با ظرفیت واقعی کامل stack سنجیده می‌شود؛ اندازه دیسک به‌تنهایی readiness نیست.

## ۸. مدیریت، WireGuard و firewall

آموزش ساخت روی سرور جدید، تنظیم Windows، کاربرد روزمره، revoke و recovery در
[راهنمای عملی WireGuard](wireguard-management-fa.md) آمده است. اتصال موجود را از نو نسازید.

1. برای هر دستگاه یک peer و کلید مستقل بسازید؛ موجودی مالک/دستگاه و روش revoke ثبت شود.
2. private key روی دستگاه مالک با دسترسی محدود بماند؛ config خصوصی را در repository نگذارید.
3. ابتدا overlay را از یک نشست دوم تست کنید. نشست SSH فعلی را باز نگه دارید.
4. تغییر firewall/netplan باید backup، زمان rollback خودکار و امکان بازیابی بررسی‌شده داشته باشد.
5. فقط table/rule متعلق به همین نصب را تغییر دهید؛ `flush ruleset` و حذف fail2ban یا تنظیم ایمیل مجاز نیست.
6. public SSH را قبل از آزمون مسیر مدیریت و بازیابی نبندید. وضعیت bootstrap موقت تأیید Production نیست.
7. برای go-live، SSH فقط از WireGuard با کلید مستقل Ed25519 رمزدار (FIDO2 اختیاری)، JIT حداکثر ۳۰ دقیقه و یک reviewer (با پذیرش ریسک تفکیک وظایف)
   برای write، بدون root/password/shared key و audit خارج از host لازم است.

قالب‌های فعلی: `infrastructure/production/host/` و `network/trust-policy.json`.
این قالب‌ها را مستقیم روی میزبان فعال overwrite نکنید. برای WireGuard، package/kernel واقعی را
pin و config هر host را بازبینی کنید. تولید کلید را با ابزار رسمی `wg` و مجوز فایل `0600` انجام دهید؛
کلید هرگز در argv، history یا stdout ظاهر نشود. روش نرم‌افزاری حفاظت سخت‌افزاری FIDO2 ندارد؛
این کاهش اطمینان در ADR-0030 ثبت شده و فقط به ورود انسانی profile تک‌سرور مربوط است.
توضیح ساده JIT و peer و مراحل اجرایی در [راهنمای دسترسی انسانی](production-human-access-prerequisites-fa.md) آمده است.

## ۹. IP دوم، DNS، TLS و ایمیل موجود

1. Reserved IP را فقط با روش تأییدشده میزبان به interface همان سرور اضافه کنید؛ primaryIP،
   gateway و DNS فعلی را تغییر ندهید. خروجی panel به‌تنهایی ثابت نمی‌کند IP در OS فعال است.
2. روی میزبان دارای netplan از فایل تغییر مستقل و `netplan try` با timeout/rollback استفاده کنید؛
   قبل از پذیرش، اتصال مدیریت و دسترسی خارجی IP جدید را از دستگاه دوم بررسی کنید.
3. ابتدا ingress IP جدید را quarantine کنید؛ بازکردن 80/443 آخر کار است.
4. Caddy/nginx/mail را با bind دقیق IP جدا کنید؛ wildcard listener ممکن است هر دو IP را اشغال کند.
5. `mail` و MX و rDNS ایمیل روی IP ایمیل می‌مانند. DKIM/SPF/DMARC و کلیدهای mail را از مشتری قبلی کپی نکنید.
6. DNS سایت (`@`/`www`) فقط بعد از آزمون مسیر جدید تغییر می‌کند؛ wildcard را بی‌دلیل جابه‌جا نکنید.
7. DNS/TLS، renew و reboot persistence را آزمون کنید؛ ACME قبل از cutover باید راه معتبر challenge داشته باشد.

**IP دوم جای L4 نیست.** مسیر مصوب فعلی نیازمند upstream volumetric protection و external L4
دارای PROXY v2 و CIDRهای مبدأ ثابت/دقیق است. Origin فقط همان CIDRها را می‌پذیرد.
نبود این امکان یک blocker واقعی go-live است؛ direct-origin یا trusting arbitrary headers راه‌حل مصوب نیست.
Roundcube رابط webmail است؛ برای ارسال سامانه، SMTP واقعی با TLS، sender مجاز و آزمون delivery لازم است.
تعویض Gmail با mail server نیازمند credential در OpenBao و آزمون fail/ambiguity هم هست.

## ۱۰. foundation خصوصی K3s/Calico/Kyverno

قراردادهای جاری: `infrastructure/production/k3s/config.yaml`، `platform-contracts.json` و
profile. یک node و SQLite control plane داریم؛ HA و failover ادعا نمی‌شود.

ترتیب commissioning:

1. نسخه و SHA256 binary/airgap image K3s را با baseline و release رسمی بررسی کنید.
2. Flannel، K3s network-policy، Traefik و ServiceLB bundled خاموش؛ secrets encryption روشن.
3. API/management خصوصی؛ Pod Security و audit metadata-only؛ token/datastore backup رمزدار off-host.
4. Calico pin‌شده را نصب و readiness/CNI/deny-by-default را آزمون کنید.
5. مسیر CNI را از **config مؤثر containerd** همان نسخه بخوانید؛ حدس یا متن قدیمی کافی نیست.
   در foundation قبلی مسیر واقعی `/opt/cni/bin` و `/etc/cni/net.d` بود؛ remap اشتباه علت failure شد.
6. Kyverno pin‌شده، dedicated SA، bounded resources، CEL stable و fail-closed admission را نصب کنید.
7. negative tests برای privileged/hostNetwork/tag-only/unsigned و network isolation انجام دهید.
8. reboot و بازیابی و نبود fail-open window را آزمون کنید. ترافیک عمومی هنوز بسته بماند.

اسکریپت‌های `.platform-runtime` قبلی host-specific و Git-ignored بودند؛ مسیر یا hash همان میزبان
installer مشتری جدید نیست. installer عمومی کامل host هنوز تحویل‌شده محسوب نمی‌شود؛ این راهنما
قرارداد و ترتیب آزمون را ثبت می‌کند و جای dry-run، rollback و manifest بررسی‌شده را نمی‌گیرد.
اگر دستور `k3s` شکست خورد، خطای فیلترشده، API liveness، CRD/controller و CNI path را بررسی کنید؛
cluster state را پاک نکنید و installer یکسان را بی‌تغییر پشت سر هم اجرا نکنید.

## ۱۱. اجزای باقیمانده stack و secrets

برای هر ردیف، manifest مصوب + آزمون واقعی + rollback/recovery لازم است. قراردادهای `infrastructure/production/`
مقادیر هدف را تعریف می‌کنند؛ به‌تنهایی نصب واقعی نیستند.

| بخش | شرطی که باید اجرا و ثابت شود |
| --- | --- |
| PostgreSQL/CNPG/Barman | DB/role/Flyway مستقل هر service، forced RLS، runtime غیرمالک، pool مجموع ≤۷۰٪ |
| Backup | daily base + continuous WAL، مقصد off-site مستقل، PITR ۳۵ روز، monthly retention ۱۲ ماه |
| Redis | TLS/ACL مستقل، noeviction، AOF everysec، ≥۳۰٪ memory reserve، quota clock/fail-closed |
| Kafka | یک combined KRaft، RF/minISR=1، acks=all/idempotence، TLS/ACL؛ transport است، نه business authority |
| OpenBao/ESO | Raft یک PVC، Shamir سه سهم/threshold دو، custody جدا، hourly snapshot رمزدار off-PVC، restore/unseal |
| Istio | intermediate مصوب، strict mTLS، SA مستقل، least privilege، positive/plaintext/wrong-SA negatives |
| Edge | L4 → Traefik → Caddy/Coraza؛ `/api` به BFF و سایر مسیرها به frontend؛ bypass/forged-IP negatives |
| Monitoring | Collector/Prometheus/Loki/Tempo/Grafana/Alertmanager داخلی، allow-list و بدون PII؛ audit جدا و durable |
| External monitor | بیرون failure domain، آزمون خاموشی کامل host و رسیدن alert |
| Argo CD | GitOps مصوب، exact digest، prune امن برای داده، بدون secret در values/Git |

نام منطقی secrets و referenceها در Git قابل ثبت است؛ مقدار secrets فقط در مرز مصوب secret management.
unseal shares و recovery credentials را روی همان VPS یا یک حساب مشترک جمع نکنید.
installer عمومی کامل همه این اجزا هنوز بخشی از کار باقیمانده Stage 10 است؛ از `kind` یا
developer runtime برای اثبات Production استفاده نکنید.

## ۱۲. ساخت و انتشار همان نسخه آزمایش‌شده

شش Java service و frontend هفت artifact مستقل‌اند. جریان موجود repository را استفاده کنید:

1. gateهای source، secret/history، dependency و tests اجرا شوند.
2. final image ساخته و به immutable digest در registry ثبت شود.
3. با `scripts/production/release_supply_chain.sh`/workflow مصوب، Syft CycloneDX، Grype و
   Cosign signature/provenance/SBOM به **همان digest** متصل شوند؛ CLI help/ورودی‌های script را از نسخه جاری بخوانید.
4. staging به همان digest deploy و آزمون شود؛ در Production دوباره build نکنید.
5. release manifest نسخه ۲ با public metadata و referenceهای evidence/Secret کامل شود؛
   schema در `infrastructure/production/release-manifest.schema.json` است؛ مثال unit test مدرک واقعی نیست.

در Linux/WSL:

```bash
python3 scripts/production/verify_release.py --manifest /safe/release.json
python3 scripts/production/render_gitops.py --manifest /safe/release.json --output /safe/review/rendered
```

renderer فقط فایل می‌سازد؛ secret نمی‌خواند و cluster را تغییر نمی‌دهد. خروجی بازبینی‌شده از PR
به `deploy/releases/production` و `deploy/clusters/production` می‌رود؛ Argo CD desired state را اعمال می‌کند.
admission باید unsigned/wrong signer/missing provenance/SBOM را رد کند.

## ۱۳. آزمون‌های واقعی قبل از DNS cutover و go-live

1. Email و SMS delivery واقعی، failure/ambiguity و idempotency؛ sandbox مدرک delivery نیست.
2. model snapshot/prompt/price/evaluation tuple دقیق و privacy approval provider؛ canary و rollback.
3. browser journey واقعی از edge، tenant isolation و AI conversation؛ credential به browser نمی‌رسد.
4. complete stack load/soak با background backup/telemetry؛ بدون OOM و sustained swap، حداقل ۳۰٪
   headroom CPU/RAM و peak امنیتی معتبر. کاربران حاضر در سایت با درخواست AI هم‌زمان تفاوت دارند.
5. restore ایزوله PostgreSQL با RPO≤۵ دقیقه؛ service-specific restore به سایر DBها آسیب نزند.
6. monthly restore و quarterly cold DR کل platform با هدف RTO≤۴ ساعت؛ miss را پنهان نکنید.
7. reboot، recovery، quota clock، revoked peer، mesh/admission outage و external host-down alert.
8. همه نقش‌های business/platform/privacy/product/security مالک نتیجه را تأیید کنند.

مرجع کامل [Production Readiness Checklist](../architecture/PRODUCTION-READINESS-CHECKLIST.md) است.
evidence نسخه ۲ شامل تمام gateها، referenceهای واقعی و Git revision دقیق promoted release است:

```bash
python3 scripts/production/readiness.py /safe/readiness-evidence.json --expected-revision <PROMOTED_GIT_SHA>
```

قبل از اجرای واقعی، `PASS`/`go_live_approved=true` ننویسید. سبز بودن validator فقط ساختار ادعا را بررسی
می‌کند؛ owner باید شواهد را واقعاً بررسی کند. فقط بعد از تمام موارد، origin policy، DNS/TLS و ترافیک فعال می‌شوند.

## ۱۴. نگهداری و rollback

- هر backup cycle verification؛ daily base/WAL monitoring، hourly OpenBao snapshot.
- ماهانه isolated restore، هر فصل cold DR؛ expiration گواهی‌ها و intermediate rotation را پایش کنید.
- advisory/feed freshness و deployed-digest rescan، capacity/clock/audit/telemetry/provider alerts را فعال نگه دارید.
- rollback اپلیکیشن با PR/Git revert فقط با schema/data compatibility؛ Flyway اجراشده را ویرایش نکنید.
- cluster-wide PITR را روی DBهای زنده سایر serviceها overwrite نکنید؛ ابتدا isolated restore.
- در incident، MFA/WAF/mTLS/admission/quotas/backup را برای «بالا آمدن سایت» خاموش نکنید.
- cleanup فقط فایل‌های مشخص و owned را بعد از inventory انجام دهید؛ پوشه‌های data/keys/backup را با wildcard پاک نکنید.
- با هر تغییر commissioning، همین راهنما، قراردادها و تست‌های مربوط باید در همان PR به‌روز شوند.

## ۱۵. وضعیت این راهنما و کارهای هنوز باقی‌مانده

ابزار عمومی ایجاد Root، دو backup، verify و امضای intermediate در سورس وجود دارد. این ابزار
کلیدهای نصب فعلی را import یا جایگزین نمی‌کند. گواهی قبلی مالک با منشأ متصل، اکنون فقط برای
نصب مشخص و hash دقیق طبق ADR-0002 مورد پذیرش مالک است؛ این پذیرش یا خروجی recovery
به‌تنهایی نصب mesh یا آمادگی Production را اثبات نمی‌کند.

این راهنما production appliance آماده یا one-click installer همه stack نیست. تکمیل host provisioning،
custody/import/rotation intermediate، manifestهای تمام platform، دسترسی و آزمون provider/backup/DR/capacity
همچنان gate واقعی Stage 10 هستند. تا آن زمان عبارت صحیح **Production Readiness: Not verified** است.

### منابع و تست ابزار

- منابع فعلی: ADR-0002، 0011، 0030، 0042، 0043 و source order در `AGENTS.md`.
- رفتار ابزار: مستندات رسمی OpenSSL 3.5 برای `genpkey`، `pkcs8`، `req`، `x509` و `verify`.
- دریافت vendor: Git for Windows release `v2.55.0.windows.3`؛ archive و پنج فایل لازم در `tools.lock.json` pin شده‌اند.
- integrity فایل‌های متنی با UTF-8 strict و normalization LF/CRLF؛ binaryها byte-for-byte بررسی می‌شوند.
- آزمون Windows ابزار: `scripts/production/tests/test_offline_ca.ps1`؛ کلیدهای تصادفی fixture، مستقل از کلید واقعی.

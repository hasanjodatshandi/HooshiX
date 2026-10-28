# راهنمای زندهٔ استقرار Production — Stage 10

## مرز اصلاح امنیتی تصاویر زیرساخت

اصلاح وابستگی JOSE در مسیر ساخت candidate مستقل از تصاویر نصب‌شده انجام
می‌شود. جایگزینی تصویر مقصد فقط پس از ساخت، اسکن، امضا، آزمون سازگاری و
بازیابی، و پذیرش digest جدید مجاز است؛ اصلاح سورس به‌تنهایی شاهد رفع مشکل روی
VPS نیست. کلیدها، CA، داده‌ها و مرز بستهٔ دسترسی مقصد تغییر نمی‌کنند.

برای ساخت جدید از `jose-platform-sources.json` و مسیر فعلی
`build_patched_platform.py` استفاده شود؛ recipe قبلی برای اعتبارسنجی provenance
تصاویر موجود بدون تغییر باقی می‌ماند. candidate از JOSE v4.1.6 و patch دارای
checksum برای مهاجرت CAP v0.14.0 و Okta SDK v2.20.0 در OpenBao استفاده می‌کند؛ وجود v3 در باینری
یا v4 قدیمی پذیرفته نیست. آزمون‌های JWT/OIDC و احراز هویت Kubernetes پیش از
ساخت باینری اجرا می‌شوند. تست‌های واحد SDK و امضا/رد امضای نامعتبر client assertion
هم الزامی‌اند؛ گراف واقعی packageهای OpenBao باید پیش از build فاقد JOSE قدیمی
باشد. سقف پیش‌فرض JWK/RSA برابر ۴۰۹۶ بیت حفظ می‌شود؛
سازگاری provider خارجی با کلید بزرگ‌تر باید پیش از پذیرش تصویر بررسی شود.
این مرحله هیچ فرمان تعویض سرویس، init، unseal یا تغییر کلید روی VPS ندارد.

## هدف و قاعدهٔ نگهداری

این فایل راهنمای مرکزی فارسی برای آماده‌سازی سرور جدید و استقرار HooshiX است.
به دستور مالک، تا پایان Stage10 هر تغییر مرتبط با نصب یا عملیات، باید در همین
فایل و در همان تغییر منسجم ثبت شود. فقط روش نهاییِ پشتیبانی‌شده نوشته می‌شود؛
تاریخچهٔ خطا، تلاش‌های ناموفق، transcript و دستورهای منسوخ وارد این فایل نمی‌شوند.
روش اصلاح‌شده جای روش قبلی را می‌گیرد. اطلاعات محرمانه هرگز در این فایل نیست.

این راهنما نسخه‌نامه یا مرجع معماری مستقل نیست. اولویت با
[Decision Register](../adr/decision-register.md)،
[نسخه‌های مصوب](../technology/technology-baseline.md)،
[سازگاری](../technology/production-compatibility-matrix.md) و
[دروازهٔ آمادگی](../architecture/PRODUCTION-READINESS-CHECKLIST.md) است.
وضعیت واقعی در [implementation-status](../architecture/implementation-status.md)
و رسیدهای همان نصب بررسی می‌شود. وجود کد، موفقیت CI یا نتیجهٔ سرور اول، موفقیت
سرور دوم را اثبات نمی‌کند. هیچ بخش این راهنما مجوز دورزدن gateها نیست.

محدوده فقط Stage10 است؛ MCP، پنل قدیمی و مهاجرت آن‌ها جزو مراحل این نصب نیستند.
Stageهای قبلی ساخت/آزمون محصول‌اند و برای نصب مجدد لازم نیست تکرار شوند.

پیش از ساخت بستهٔ نصب، رفع آسیب‌پذیری وابستگی‌های Java باید در تغییر مستقل
بررسی شود؛ اسکن lockfile و تأیید integrity هر شش سرویس همچنان الزامی است.
هر شش سرویس Java وابستگی موجود Guava را با constraint به `33.7.2-android`
محدود می‌کنند؛ همین نسخه باید در lockfile و metadata تأیید checksum ثبت شود.
checksum فایل `guava-parent` همان نسخه نیز برای هر شش سرویس لازم است؛
تأیید integrity را دست‌کم یک بار با cache خالی بررسی کنید.
مختصات وابستگی gRPC حفظ می‌شود؛ Gradle مانند قبل فایل گونهٔ JVM را انتخاب می‌کند.
نسخهٔ همراه `jspecify` برابر `1.0.1` است. این تغییر مجوز انتشار یا تعویض تصویر
برنامه روی VPS نیست و آزمون سرویس و gateهای تصویر نهایی همچنان لازم‌اند.
پنج سرویس دارای Kafka نیز `snappy-java` را به `1.1.10.9` محدود می‌کنند؛
Compromised Password این وابستگی را ندارد. lockfile، checksum و سازگاری native
باید بررسی شوند؛ تنظیم compression و اطلاعات broker با این اصلاح تغییر نمی‌کند.

ابزار تست وب و پوشش آن (`vitest` و `@vitest/coverage-v8`) باید هر دو دقیقاً
نسخهٔ `4.1.12` باشند؛ lockfile باید وابستگی‌های داخلی همان نسخه، از جمله
`@vitest/mocker`، را نگه دارد. نصب با `npm ci` از lockfile بررسی‌شده انجام می‌شود.
این ابزار فقط build/test است و نباید به سرویس عمومی Production تبدیل شود.
پیش از ساخت تصویر وب، اسکن lockfile، آزمون/coverage و build باید موفق باشند؛
عبور اسکن را با ignore یا override روی نسخهٔ آسیب‌پذیر شبیه‌سازی نکنید.
آزمون مرورگر فعلی فقط Chromium است؛ در CI وابستگی‌های همان مرورگر را با
`npx playwright install --with-deps chromium` نصب کنید. این انتخاب تعداد تست‌ها،
آزمون accessibility یا gateهای تصویر/اسکن را تغییر نمی‌دهد؛ فقط دریافت مرورگرهای
بدون مصرف را حذف می‌کند.

## وضعیت پوشش فعلی

پایهٔ بررسی‌شده: `main@5fcaca8540875d742e4194ab215054610d872e9b`، ۲۰۲۶-۱۰-۱۲.
این SHA مرجع این ویرایش است، نه الزام استفادهٔ دائمی از نسخهٔ قدیمی.
سرور اول OpenBao نصب‌شده، initialized و unsealed دارد؛ recovery ایزولهٔ محلی
با سهم‌های اصلی `Passed` است. نصب ESO و Ready بودن شش SecretStore روی مقصد
`Passed` است. تأیید نسخه/retention مقصد backup، scheduler ساعتی، تحویل اسرار واقعی،
JIT کامل، داده‌ها و برنامه‌های Production هنوز کامل نشده‌اند.
Stage10 و Production readiness همچنان `Not verified` هستند.

تحویل TLS کافکا در ۲۰۲۶-۱۰-۲۳ با `KAFKA_TLS_DELIVERY=Passed` پایان یافت؛ اجرا
از `main@e6ed7d859dea75eccdb1856290dc85dcd2376b6c` و plan SHA-256
`cdc84930059dc68a553ad8f3d29c3bc4f30e8cfd85c4ccb9139d321f40465843` بود.
رسید عمومی: `~/.local/share/hooshix-kafka-tls/delivery-00170c7e127744a38fcbf73f6cfc96a6.json`.
این عملیات از issuer/OpenBao/ESO موجود استفاده کرد و Root را حفظ کرد؛ broker،
claim یا PVC نساخت. این رسید فقط تحویل TLS را اثبات می‌کند؛ وضعیت broker و
topic/ACL در بخش مربوط و رسید مستقل همان گام مشخص می‌شود. recovery، ظرفیت و
Production readiness همچنان `Not verified` هستند.

| مرحله | وضعیت سرور اول | تکرار برای نصب دوم |
| --- | --- | --- |
| مدیریت SSH/WireGuard | آزمون‌های محدود ورود و محدودسازی Passed | هویت و آزمون مستقل |
| K3s/Calico و audit | foundation و شواهد محدود موجود | نصب/commissioning مستقل |
| storage/guard | نصب و آزمون reboot/fault/recovery Passed | storage و هویت مستقل |
| CA/intermediate | CSR، امضا و import Passed | intermediate و binding مستقل |
| Kyverno/Istio/OpenBao | نصب هدف Passed | بستهٔ اختصاصی مقصد |
| OpenBao custody/init/unseal | Passed؛ توزیع custody تأیید مالک | custody و سهم‌های تازه |
| snapshot | ciphertext/hash Passed؛ نسخهٔ باکت Not verified | مقصد و شواهد مستقل |
| recovery ایزوله | Passed؛ بازیابی VPS Not run | آزمون backup همان نصب |
| scoped auth OpenBao | اجرای مالک Passed؛ شش هویت فقط‌خواندنی، root محفوظ | بسته و آزمون مستقل مقصد |
| ESO و شش SecretStore | نصب مقصد Passed؛ اسرار واقعی Not verified | نصب و آزمون مستقل مقصد |
| Kafka بسته | broker داخلی، TLS پنج هویت و رسید ۶ topic/۴۷ ACL با مهاجرت health موجود Passed؛ دسترسی برنامه/عمومی CLOSED | بستهٔ اختصاصی، staging همان revision و رسید مستقل مقصد؛ ظرفیت/بازیابی همچنان لازم است |
| JIT/داده/GitOps/برنامه/edge | کامل نشده | مراحل بعد این راهنما |

## ۱. برگهٔ عمومی نصب

برای هر نصب این برگه را خارج از فایل‌های credential پر کنید:

```text
Installation ID:
Reviewed Git revision:
Public IP / hostname:
OS / version / architecture:
CPU / RAM / disk:
Administrative username:
SSH alias / port:
WireGuard server and client addresses:
Pod / Service CIDRs:
Kubernetes node name:
Public product hostname:
Backup endpoint / bucket / dedicated prefix:
Root trust: shared approved environment or independent installation
Emergency console: successful login confirmed
Platform/security owner:
```

سرور دوم alias جدا مثل `hooshix-server-2` داشته باشد. alias سرور اول را تغییر
ندهید. شبکهٔ WireGuard با نصب اول، LAN، VPN و Pod/Service CIDR تداخل نداشته باشد.
تا cutover مصوب، DNS اصلی `hooshix.com` به سرور دوم منتقل نمی‌شود.
منابع باید برای کل stack سنجیده شوند؛ ۸GiB زیر فقط storage OpenBao است، نه
حداقل دیسک یا RAM کل محصول. ظرفیت تضمین‌شده بدون آزمون complete-stack ادعا نمی‌شود.

## ۲. سورس و محیط اجرا

روی WSL یک checkout تمیز و مستقل بسازید. محل اصلی توسعهٔ برنامه تغییر نمی‌کند.
`REVIEWED_GIT_SHA` را با SHA کاملِ merge‌شده، بررسی‌شده و دارای CI موفق عوض کنید:

```bash
git clone https://github.com/hasanjodatshandi/HooshiX.git /home/coder/workspace/Hooshix-production-2
cd /home/coder/workspace/Hooshix-production-2
git fetch origin --prune
git checkout --detach REVIEWED_GIT_SHA
make context-verify
make context-bootstrap
make production-verify
```

روی checkout موجود، clone را تکرار یا داده را overwrite نکنید. تغییرات نصب از
branch/PR می‌گذرند. اصلاحات امنیتی Java/frontend جزو سورس منتخب‌اند؛ patchهای
قدیمی دستی تکرار نمی‌شوند. [راهنمای پایه](production-installation-fa.md).

ابزار frontend از Vite ثابت `8.2.4` و lockfile متناظر استفاده می‌کند؛ این patch
اصلاح امنیتی [GHSA-698v-4r3x-3vcv](https://github.com/advisories/GHSA-698v-4r3x-3vcv)
برای اجرای editor روی Windows را دارد. وابستگی‌ها را با `npm ci` نصب کنید؛
اسکن OSV، build، coverage، آزمون مرورگر و دسترس‌پذیری باید موفق باشند.
در Production فقط خروجی `dist` توسط Caddy سرو می‌شود؛ `vite preview` سرور Production نیست.

## ۳. inventory و مسیر نجات

در نشست مجاز فقط سرور دوم اجرا کنید:

```bash
hostname -f
cat /etc/os-release
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
```

قبل از تغییر مدیریت/storage، ورود واقعی کنسول اضطراری و یک نشست دوم مستقل
را امتحان کنید. حساب انسانی اختصاصی و sudo اولیه برای bootstrap داشته باشید.
نشست اولیه باز بماند. سرویس و دادهٔ موجود شناسایی/حفظ شوند. روی سرور خالی
ایمیل، Caddy قدیمی، پنل یا MCP نصب نکنید؛ پیش‌نیاز HooshiX نیستند.

## ۴. WireGuard و SSH

[راهنمای کامل WireGuard](wireguard-management-fa.md) مرجع ساخت کلید، نصب، اتصال،
revoke و recovery است. برای هر دستگاه و سرور کلید مستقل بسازید. private key
در همان دستگاه، با مجوز محدود و backup رمزدار بیرون Git بماند. AllowedIPs
دستگاه مدیر فقط مسیر مدیریت لازم باشد؛ اینترنت یا subnet کلاستر را بی‌دلیل route نکنید.

در PowerShell محلی، برای نصب دوم کلید Ed25519 تازه و رمزدار بسازید:

```powershell
ssh-keygen.exe -t ed25519 -a 100 -f "$env:USERPROFILE\.ssh\hooshix-prod2"
```

فقط فایل `.pub` در حساب مدیر مقصد نصب می‌شود. SSH config مستقل:

```sshconfig
Host hooshix-server-2
    HostName MANAGEMENT_IP_2
    User ADMIN_USER_2
    Port SSH_PORT_2
    IdentityFile ~/.ssh/hooshix-prod2
    IdentitiesOnly yes
```

این block template است؛ placeholderها را با دادهٔ مقصد جایگزین کنید. host key
سرور را از مسیر مورد اعتماد تطبیق دهید؛ بررسی host key را خاموش نکنید.
پس از تنظیم peer و UDP مجاز، از نشست تازه امتحان کنید:

```powershell
ssh.exe hooshix-server-2 hostname
```

خروجی باید hostname سرور دوم باشد. سپس با backup و rollback زمان‌دار:
root/password/keyboard-interactive login ممنوع، public-key فعال، forwarding
حساب انسانی ممنوع و SSH عمومی بسته شود؛ مدیریت فقط از WireGuard.
ورود تازه و ماندگاری پس از reboot آزموده شوند. FIDO2 در پروفایل تک‌سرور اختیاری
است؛ Ed25519 رمزدار مجاز است. پورت ۲۲۲۲ نیاز این محصول نیست.
sudo اولیه تا commissioning واقعی JIT و recovery حفظ می‌شود.
گیت CI روی runner دورریختنی، انقضای cgroup، توقف فرزند پس‌زمینه و آزادشدن
قفل مدیر را بررسی می‌کند؛ PID آزمون فقط پس از نوشتن کامل به‌صورت اتمی منتشر
می‌شود. موفقیت این آزمون جای آزمون JIT واقعی روی سرور مقصد را نمی‌گیرد.
[دسترسی انسانی](production-human-access-prerequisites-fa.md).

## ۵. audit میزبان

[راهنمای host audit](production-host-audit-fa.md): نصب auditd از مخزن رسمی همان OS
با نسخهٔ بررسی‌شده، daemon فعال/boot-enabled، قواعد بازبینی‌شده از
`infrastructure/production/host/audit.rules`، حدود فایل و رفتار خطا، canary
بدون محتوای محرمانه و بررسی lost event/kernel audit لازم است.
نسخهٔ package سرور اول روی OS متفاوت تحمیل نمی‌شود. فایل‌ها دسترسی محدود دارند؛
log خام/رمز/کلید را در chat یا CI نگذارید. audit محلی، تأیید off-host audit نیست.

## ۶. K3s، Calico و Kubernetes audit

نسخه‌های منتخب این ویرایش: K3s `v1.35.6+k3s1`، Calico `3.32.1`، Kyverno
`1.19.1`، Istio Ambient `1.30.5` و OpenBao `2.6.4`. هنگام ساخت بسته با pins
جاری، digest، معماری و شواهد امنیتی تطبیق دهید؛ از `latest` استفاده نکنید.

پایهٔ config از `infrastructure/production/k3s/config.yaml`:

```yaml
write-kubeconfig-mode: "0600"
secrets-encryption: true
flannel-backend: none
disable-network-policy: true
disable:
  - servicelb
  - traefik
protect-kernel-defaults: true
```

پیش از first start، آدرس‌ها، node name، kernel defaults و audit مقصد باید
تکمیل شوند؛ این block تمام تنظیمات provisioning نیست. binary/image رسمی نسخهٔ
دقیق با checksum معتبر تهیه شود. Calico جای CNI پیش‌فرض نصب و readiness، DNS
و deny-by-default آزموده شود. مسیر CNI از config مؤثر containerd خوانده شود.
API/management و kubeconfig عمومی نشوند. API audit برای bootstrap Metadata-only
است؛ body عملیات Secret ثبت نمی‌شود. secrets encryption فعال/سالم باشد.

```bash
sudo /usr/local/bin/k3s kubectl --request-timeout=15s get nodes
sudo /usr/local/bin/k3s kubectl --request-timeout=15s get pods -A
sudo /usr/local/bin/k3s secrets-encrypt status
```

این‌ها بررسی سلامت‌اند، نه جایگزین آزمون network/admission.
[تنظیم رسمی K3s](https://docs.k3s.io/installation/configuration).

## ۷. storage OpenBao

[راهنمای storage](production-openbao-storage-fa.md): filesystem ext4 مستقل داخل
فایل از پیش رزروشدهٔ حداکثر ۸GiB، mount مستقل، دادهٔ UID/GID `10001`، guard
هویت filesystem/backing و dependency K3s لازم است. مسیرهای همان سرور:

```text
/var/lib/hooshixstorage/openbao.ext4
/var/lib/hooshixstorage/openbao
/var/lib/hooshixstorage/openbao/data
```

ترتیب: فضای آزاد/reserve → ساخت فقط مسیر تازه → ثبت هویت → mount unit/guard →
start سالم → reboot کنترل‌شده → fault/recovery مصوب → PV محلی Retain با node
affinity مقصد. failure محافظ K3s را متوقف می‌کند؛ توقف همهٔ containerهای موجود
ادعا نمی‌شود. بازگشت mount نباید re-arm خودکار باشد. دادهٔ موجود فرمت/حذف نمی‌شود.

## ۸. CA و intermediate

[راهنمای PKI](production-pki-bootstrap-fa.md). برای دو محیط مستقل، Root مستقل؛
برای یک مرز اعتماد مشترک، استفاده از Root قبلی فقط پس از انتخاب/تأیید همان
مرز. در هر حالت intermediate و کلیدش برای نصب دوم مستقل‌اند.
کلید intermediate رمزدار در سرور دوم ساخته شود؛ فقط CSR عمومی و hash خارج شوند.
روی کامپیوتر آفلاین، CSR با Root منتخب امضا و فقط خروجی عمومی بازگردانده شود.
کلید/رمز Root آنلاین نمی‌آید. import به `istio-system/cacerts` باید CSR، Root
و state همان نصب را تطبیق دهد؛ Secret متعارض overwrite نمی‌شود.

ورودی ابزار آفلاین:

| ورودی | مقدار |
| --- | --- |
| Root backup folder | پوشهٔ backup رمزدار Root منتخب |
| CSR file | مسیر کامل `cluster-intermediate.csr.pem` همان نصب |
| CSR SHA256 | رشتهٔ hash مستقل فایل، نه مسیر آن |
| Password | رمز Root فقط روی دستگاه آفلاین |

## ۹. GHCR و artifact

[راهنمای GHCR](production-ghcr-fa.md). artifact مشترک لازم نیست برای هر سرور
دوباره build شود؛ digest مصوب، signature، provenance و SBOM همان release
استفاده می‌شوند. credential pull فقط `read:packages` و در prompt مخفی/فایل
خصوصی مصوب است. مقدار واقعی token، نه نام token یا رمز GitHub، وارد شود.
run ID تاریخی را معتبر دائمی فرض نکنید؛ freshness/publication/staging هنگام
ساخت بسته بررسی شود. image tag-only و waiver آسیب‌پذیری مجاز نیست.

## ۱۰. بستهٔ platform و نصب

[راهنمای commissioning](production-platform-commissioning-fa.md) مرجع جزئیات است.
بستهٔ نهایی تمام اصلاحات فعلی را یکجا دارد؛ patchهای تاریخی جدا اجرا نمی‌شوند.
ترتیب: Kyverno/CRD → pull Secret محدود → policyهای fail-closed → خروجی محدود
HTTPS verifier و TUF معتبر → TLS → Istio → PV/PVC Retain → OpenBao StatefulSet.
CNI/ztunnel فقط استثنای محدود مصوب دارند؛ privilege عمومی برای برنامه‌ها نیست.
OpenBao خصوصی، TLS معتبر و یک replica/Raft/PVC دارد. نصب init نمی‌کند.
نتیجهٔ لازم `OPENBAO_INSTALLATION=Passed` با رسید مربوط به مقصد/plan/revision است.
public ingress باز نمی‌شود؛ PVC/marker/data برای retry حذف نمی‌شوند.

### مرز اجرای سرور دوم

ابزارهای فعلی هنوز generic installer نیستند. بعضی launcherها alias
`hooshix-server` دارند؛ ابزار CSR hostname و Root hash نصب اول را کنترل می‌کند؛
storage نسخه‌های package میزبان اول را قبول می‌کند؛ plan شامل node/IP و هویت
PV است. تغییر alias اول، کپی marker/PVC، یا حذف این کنترل‌ها راه نصب دوم نیست.
پیش از اجرای mutation باید بستهٔ مخصوص نصب دوم، با اطلاعات مرحلهٔ ۱، در PR
ساخته/بررسی/آزموده شود. تا آن زمان دستور نصب اختصاصی مقصد دوم **Not available**
است؛ مثال‌های راهنماهای لینک‌شدهٔ سرور اول را روی سرور دوم اجرا نکنید.

## ۱۱. OpenBao custody، init و unseal

[راهنمای activation](production-openbao-activation-fa.md). custody تازه خارج Git،
رمز تازه در password manager و سه recipient مستقل ساخته شوند. recovery private
exportها پیش از init بررسی شود. فقط برای store قطعاً uninitialized همان نصب:
Shamir سه سهم/threshold دو، سهم‌ها و root token از ابتدا PGP-encrypted، دریافت
ciphertext و readback/recovery → توزیع امن → unseal با دو سهم تحت نظارت.
private key و رمز custody روی VPS نمی‌روند؛ plaintext ذخیره/نمایش نمی‌شود.
حداقل دو سهم و کلید متناظرشان در دسترس عادی یک دستگاه جمع نشوند. نگهداری یک
مالک با یک رمز را custody چندنفره ادعا نکنید.

| prompt | credential لازم |
| --- | --- |
| sudo | حساب مدیریتی همان سرور |
| GHCR | مقدار token خواندن package |
| intermediate | رمز کلید intermediate همان نصب |
| custody | رمز custody OpenBao همان نصب، نه Root/CA/sudo |

نتیجهٔ لازم `OPENBAO_ACTIVATION=Passed` و receipt مستقل است. init فقط یک بار؛
برای state موجود، resume/unseal با همان custody. گزینهٔ recovery initialization
گمشده مرحلهٔ نصب عادی نیست. [init رسمی](https://openbao.org/docs/commands/operator/init/).

## ۱۲. backup و recovery ایزوله

[راهنمای backup](production-openbao-backup-fa.md): مقصد و prefix مستقل و credential
محدود → snapshot همان store → encryption با recipientهای همان نصب → دانلود/hash →
PUT یک key یکتا بدون overwrite → version-specific GET و تطبیق bytes/hash →
decrypt حافظه‌ای → restore clone خصوصی RAM-backed → unseal با سهم‌های اصلی →
authentication/audit → حذف clone و شبکهٔ متعلق به همان آزمون.
نتیجهٔ لازم `OPENBAO_ISOLATED_RECOVERY=Passed`؛ این restore روی VPS اصلی نیست.
VersionId نامعتبر/null یعنی نسخه/retention `Not verified`، حتی اگر ciphertext/hash
و recovery Passed باشند. کلید قبلی دوباره PUT نمی‌شود. backup/custody سرور اول
برای init نصب دوم استفاده نمی‌شوند. scheduler ساعتی هنوز مرحلهٔ بعد است.

اگر ارائه‌دهنده فعلاً API نسخه‌سازی یا قفل را پشتیبانی نمی‌کند، این بخش
`Not verified` باقی می‌ماند؛ probe/upload دوره‌ای یا ارسال مجدد همان key انجام
نشود. نسخهٔ رمزدار محلی و custody حفظ شوند. آماده‌سازی مستقل ESO و منابع
استقرار می‌تواند ادامه پیدا کند، اما این تعویق مجوز بازکردن ترافیک Production،
لغو root یا صدور JIT بدون ممیزی امن نیست. پس از اعلام رفع مشکل ارائه‌دهنده،
مسیر نسخه/retention و readback مستقل دوباره تأیید شود.

## ۱۳. رسیدن به نقطهٔ فعلی و مراحل بعد

### احراز هویت محدود OpenBao؛ مرحلهٔ اجراشده

[راهنمای اجرای شش هویت](production-openbao-auth-fa.md) شامل دستور owner-local،
ورودی‌ها، expected result، سطح ACL و recovery است. ابزار از store/custody موجود
استفاده می‌کند؛ init/restart/root revocation تکرار نمی‌شود. هر `eso-<service>`
فقط خواندن مسیر `hooshix/data/production/<service>/*` را با token حداکثر پنج
دقیقه دارد. TokenReview واقعی با JWT تازه و audience دقیق، بدون reviewer
دائمی انجام می‌شود. برای ESO، خواندن `auth/token/lookup-self` و لغو همان token
با `auth/token/revoke-self` نیز لازم است؛ ایجاد، تمدید یا لغو token دیگران
مجاز نیست و policy پیش‌فرض اضافه نمی‌شود. اجرای مجدد ابزار supervised فقط
ACL قبلی دقیق همین شش حساب را به این قرارداد ارتقا می‌دهد؛ ACL ناشناخته
متوقف می‌شود. این ارتقا روی VPS اول با همین نسخهٔ بررسی‌شده اجرا و آزموده شده است.
SecretStore فقط قرارداد آماده‌شده است؛ ESO یا secret
واقعی هنوز نصب/همگام نشده است. اجرای مالک روی VPS اول `OPENBAO_SCOPED_AUTH=Passed`
گزارش کرده است؛ رسید عمومی `auth-6ae8b0c63752493fab47c6ad55500e6a.json`
خارج Git نگهداری می‌شود. این نتیجه فقط scoped auth را اثبات می‌کند، نه ESO،
تحویل اسرار، audit خارج میزبان یا آمادگی Production. رسید جدید برای هر شش
هویت، login، خواندن مسیر خود، رد دسترسی خارج از محدوده و لغو token را
`Passed` ثبت کرده است؛ دسترسی عمومی همچنان `CLOSED` است.
بستهٔ فعلی به سرور اول bind است؛ برای نصب دوم، داده‌های مرحلهٔ ۱ لازم‌اند.

آزمون disposable نیز همان قاعدهٔ خروجی محدود TokenReview را با IPهای API همان
کلاستر اعمال می‌کند؛ آدرس‌های VPS وارد fixture نمی‌شوند. شواهد انتشار امضاشده
باید publication و پایگاه اسکن تازه در پنجرهٔ پنج‌روزه داشته باشند؛ عبور از این
پنجره نیازمند رسید تازهٔ workflow معتبر main است، نه افزایش مهلت گیت.
برای artifact upstream واردشدهٔ ثابت، revision امضای ورود به digest دقیق در
`openbao-image.json/import_provenance` bind است؛ revision اجرای اسکن تازه
جای امضای ورود را نمی‌گیرد. هر دو مستقل بررسی می‌شوند: امضای دقیق و معتبر،
و metadata/پایگاه اسکن تازهٔ workflow معتبر main. پس از انقضای رسید، بدون روش
refresh بازبینی‌شده ادامه ندهید؛ روش تکرارپذیر انتشارِ مجدد هنوز آماده نیست.
جزئیات در [راهنمای commissioning](production-platform-commissioning-fa.md) است.

برای نصب دوم، رسیدهای مدیریت، foundation، audit، storage/guard، PKI، platform،
activation، snapshot و isolated recovery باید مربوط به خود همان نصب باشند.
وضعیت هر کنترل جدا ثبت شود؛ قبول شواهد یک بخش، بخش دیگر را تأیید نمی‌کند.

### پیش‌نیاز artifact نصب ESO

نسخهٔ مصوب ESO `2.12.0` است؛ تصویر رسمی amd64 و chart همان نسخه در
`infrastructure/production/secrets/eso-image.json` با digest/checksum دقیق
به‌عنوان ورودی سورس/base قفل شده‌اند. job اجباری `ESO pinned artifact security`
در Repository baseline، chart را با SHA-256 بررسی می‌کند؛ در PR/push/manual
تصویر نهاییِ بازسازی‌شده از recipe ثابت را با Syft/Grype و پایگاه حداکثر
پنج‌روزه اسکن می‌کند. اجرای scheduled تصویر upstream قبلی را جداگانه پایش
می‌کند و یافته‌های آن را رفع‌شده فرض نمی‌کند؛ High/Critical موجب توقف است. artifact عمومی
`eso-artifact-<run>-<attempt>` شامل رسید و گزارش‌هاست؛ scan Passed فقط بررسی
artifact است، نه امضا، نصب، تحویل secret یا آماده‌بودن Production.

برای مرور pin و آزمون محلی، بدون دسترسی VPS یا credential:

```bash
python3 -m unittest discover -s scripts/production/tests -p test_eso_artifact.py
```

نصب ESO از بستهٔ عمومیِ وابسته به staging ترکیبی موفق انجام می‌شود؛ روش آن
در بخش «نصب محدود ESO روی OpenBao موجود» آمده است. نصب namespace-scoped
است، cluster stores/push secrets و TokenRequest عمومی را غیرفعال می‌کند؛ فقط
TokenRequest شش حساب `eso-<service>` مجاز است. ingress OpenBao و شبکه/mesh ESO
به‌طور مستقل و محدود آزموده می‌شوند. این مرحله root را لغو نمی‌کند.

مراجع نسخه‌ای: [chart رسمی 2.12.0](https://github.com/external-secrets/external-secrets/releases/tag/helm-chart-2.12.0)،
[RBAC محدود](https://github.com/external-secrets/external-secrets/blob/v2.12.0/docs/guides/security-best-practices.md).

upstream این نسخه را با Kubernetes `1.36` تست می‌کند؛ سازگاری با K3s
`1.35.6` فعلی هنوز `Not verified` است. قبل از نصب، آزمون native موقت با همین
نسخهٔ Kubernetes، تحویل محدود اسرار و رد دسترسی خارج از scope لازم است.
این انتخاب نسخه، مجوز ارتقای Kubernetes یا نصب با تنظیمات پیش‌فرض chart نیست.
مرجع سازگاری: [سیاست پشتیبانی نسخهٔ 2.12.0](https://github.com/external-secrets/external-secrets/blob/v2.12.0/docs/introduction/stability-support.md).

### آماده‌سازی manifest محدود ESO، بدون نصب

پیش از انتخاب مسیر اتصال ESO به mesh موجودِ سرور اول، وضعیت واقعی دو
DaemonSet شبکه و Deployment کنترل‌پلین خوانده شود؛ این دستور نه نصب می‌کند،
نه credential می‌خواند و نه OpenBao را تغییر می‌دهد:

```powershell
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl get deployments,daemonsets -n istio-system -o wide'
```

هر workload موجود باید Ready باشد و image آن با نصب بررسی‌شدهٔ همان سرور
مطابقت داشته باشد. این inventory شاهد نصب ESO یا آزمون اتصال آن نیست؛ از
روی موفقیت آزمون runner نباید وضعیت فعلی mesh روی VPS را حدس زد.

`scripts/production/render_eso_candidate.py` chart دارای checksum مصوب را با
Helm `4.2.4` برای Kubernetes `1.35.6` render می‌کند. خروجی عمومی فاقد credential
است و سه Deployment تک‌نمونهٔ controller، webhook و cert-controller دارد؛
تصویر هر سه به digest مصوب تبدیل می‌شود. هر container غیر-root است، همهٔ
capabilityها حذف می‌شوند، filesystem فقط‌خواندنی و seccomp از نوع
`RuntimeDefault` است. منابع هر container: request برابر `50m/64Mi` و limit
برابر `500m/256Mi`، با فضای موقت `16Mi/64Mi`؛ liveness/readiness الزامی است.

استثنای مصوب ADR-0002 فقط روی Podِ `hooshix-eso-webhook` است:
`istio.io/dataplane-mode=none`. controller و cert-controller صریحاً برچسب
`ambient` دارند؛ namespace، STRICT و default-deny تغییر نمی‌کنند. دلیل استثنا
callbackِ HTTPS از API کلاسترِ خارج mesh است. webhook همچنان گواهی serving و
CA bundle معتبر، `failurePolicy=Fail` و timeout پنج‌ثانیه‌ای دارد؛ هیچ Service
عمومی یا TLS نامعتبر مجاز نیست. `network(api_addresses)` در همان renderer پنج
NetworkPolicy محدود می‌سازد: API/DNS برای ESO، فقط APIهای دقیق `/32` روی 10250
برای ingress webhook، کنترل‌پلین Istio روی 15012 برای دو workload Ambient، و
8200/15008 فقط از controller به OpenBao. `authorization()` تنها principal
`prod.sajtech.internal/ns/platform-apps/sa/hooshix-eso` را روی OpenBao/8200 مجاز
می‌کند. نشانی‌ها از Service و EndpointSliceهای API همان کلاستر گرفته شوند، نه
از سرور اول کپی شوند؛ source واقعی callback باید در آزمون Calico تأیید شود.
ترافیک health خود node ممکن است از قواعد معمول NetworkPolicy مستثنا باشد؛
این قاعده به‌تنهایی احراز هویت API یا شاهد جلوگیری از دسترسی host نیست.

برای خروجی کامل candidate عمومی، به دستور `--published-candidate` پایین، برای
هر نشانی IPv4 کشف‌شدهٔ API یک `--api-address <IP>` اضافه کنید. مثال شکل دستور:

```bash
python3 scripts/production/render_eso_candidate.py --chart "$chart_dir/chart.tgz" \
  --published-candidate --api-address "$api_service_ip" --api-address "$api_endpoint_ip" \
  > "$chart_dir/scoped-published-network-candidate.json"
```

دو متغیر بالا باید IP واقعی Service و endpoint API همان نصب باشند؛ مقدار
نامشخص، CIDR، hostname، loopback یا نشانی نامعتبر پذیرفته نمی‌شود. بدون این
گزینه، خروجی تنها منابع ESO را دارد، نه شبکه/مجوز OpenBao. هیچ‌کدام را پیش از
بستهٔ نصب و آزمون ترکیبی روی VPS apply نکنید.

این منابع هنوز مجوز apply روی VPS نیستند. آزمون runner تحویل شش هویت را اجرا
می‌کند، اما آزمون ترکیبی Calico/Ambient/admission و نصب هدف باید تکمیل شود.
برچسب webhook را به namespace یا دو Deployment دیگر تعمیم ندهید؛ cert-controller
همچنان داخل mesh است. rollback نباید STRICT را به PERMISSIVE تبدیل کند.

controller فقط `platform-apps` را پردازش می‌کند؛ cluster stores و push secrets
خاموش‌اند و CRD/مجوز ClusterGenerator صادر نمی‌شود. مجوز ساخت TokenRequest فقط برای شش
`eso-<service>` موجود است. دسترسی Secret مربوط به cert-controller نیز به
همین namespace محدود است؛ مجوزهای cluster فقط metadata گواهی CRD/webhook
و خواندن `EndpointSlice` با `get/list/watch` را پوشش می‌دهند. مورد آخر برای
cache داخلی readiness گواهی‌ساز نسخهٔ مصوب لازم است؛ اجازهٔ تغییر EndpointSlice
یا خواندن Secret سایر namespaceها نمی‌دهد. با پشتیبانی upstream از cache محدود
به namespace، این مجوز metadata حذف شود. webhookها فقط همین namespace را انتخاب می‌کنند، با
`failurePolicy=Fail` و timeout پنج‌ثانیه‌ای. نقش‌های view/edit اضافی حذف می‌شوند.

در checkout همین تغییر، با Helm مصوب و PyYAML موجود، بدون VPS یا credential:

```bash
python3 -m unittest discover -s scripts/production/tests -p test_eso_render.py
chart_dir=$(mktemp -d)
curl --fail --location --silent --show-error --max-time 60 \
  --proto '=https' --proto-redir '=https' \
  https://github.com/external-secrets/external-secrets/releases/download/helm-chart-2.12.0/external-secrets-2.12.0.tgz \
  -o "$chart_dir/chart.tgz"
python3 scripts/production/render_eso_candidate.py --chart "$chart_dir/chart.tgz" \
  > "$chart_dir/scoped-candidate.json"
```

موفقیت با exit code صفر و خروجی `kind=List` مشخص می‌شود؛ checksum نامعتبر یا
خروجی خارج از محدودیت موجب توقف است. job artifact همین render را پس از scan
اجرا و `scoped-candidate.json` را نگه می‌دارد. این فایل را اکنون روی VPS apply
نکنید: CRDها، امضا/admission، شبکه/mTLS و آزمون native تحویل هنوز باید در بستهٔ
نصب تکمیل شوند. render، readiness یا موفقیت runtime را ثابت نمی‌کند. چون این
مرحله فقط فایل عمومی موقت تولید می‌کند، rollback روی سرور لازم نیست.

برای آماده‌سازی نصب با تصویر اصلاح‌شدهٔ منتشرشده، به‌جای تصویر upstream،
دستور render را با گزینهٔ ثابت زیر اجرا کنید:

```bash
python3 scripts/production/render_eso_candidate.py --chart "$chart_dir/chart.tgz" \
  --published-candidate > "$chart_dir/scoped-published-candidate.json"
```

این گزینه تصویر دلخواه نمی‌گیرد؛ فقط `published_security_candidate` در
`eso-image.json` را انتخاب می‌کند. digest انتخاب‌شده به انتشار موفق
[38137708512](https://github.com/hasanjodatshandi/HooshiX/actions/runs/38137708512)
روی revision `fa7fbbe3a20be6ba99459209cfb6087d2fecba24` و recipe ثابت bind است.
تصویر ESO و هر سه workload برابر
`ghcr.io/hasanjodatshandi/hooshix/platform-eso-v2-patched-private@sha256:961e047189afbfcc2a5ce58468f7205fc94a14eab45bf5eacbfa8fa4b839e397`
خواهد بود. بدون این گزینه، render تصویر upstream را برای بررسی پایه حفظ
می‌کند؛ آن خروجی را با candidate اصلاح‌شده اشتباه نگیرید.

این انتخاب هنوز نصب یا promotion نیست. scan تازه، امضا/provenance همان digest،
admission، CRDها، شبکه/mTLS و آزمون native تحویل شش هویت پیش از apply لازم‌اند.
متادیتای اندازه/index upstream به تصویر بازسازی‌شده نسبت داده نمی‌شود؛
checksum chart، مرز Secret namespace و محدودیت منابع حفظ می‌شوند؛ استثنای
خواندن metadataِ EndpointSlice فقط برای گواهی‌ساز طبق توضیح بالا است.
job بررسی ESO هر دو render را جدا در artifact عمومی نگه می‌دارد؛ هیچ‌کدام
بدون تکمیل گیت‌های نصب، desired state فعال روی VPS نیستند.

### آزمون native تحویل ESO در کلاستر موقت

نصب هدف ESO مرحله‌ای مستقل از init/unseal است؛ از هویت‌های محدود موجود استفاده
می‌کند و نباید Root، CA، PVC یا دادهٔ OpenBao را تغییر دهد. بستهٔ نصب تنها
پس از آزمون ترکیبی امضا، Calico، Ambient و تحویل شش هویت قابل استفاده است.

job اجباری `OpenBao Kubernetes foundation` همان chart دارای checksum و
Helm `4.2.4` را برای تصویر اصلاح‌شده استفاده می‌کند. سه CRD مستقل
`SecretStore`، `ExternalSecret` و `GeneratorState` داخلی نصب می‌شوند؛
ClusterSecretStore، ClusterExternalSecret و PushSecret نصب یا فعال نمی‌شوند.
حساب‌ها و issuer کلاستر موقت
به OpenBao ساختگی متصل‌اند و هیچ credential یا Root واقعی وارد CI نمی‌شود.
توکن canary فقط‌خواندنی آزمایشی برای هر فاز کراندار و دقیقاً پیش از مصرف همان فاز ساخته
می‌شود و هرگز بین فازهای طولانی مانند تحویل ESO/PostgreSQL و اثبات restart/PVC
بازاستفاده نمی‌شود. این قاعده فقط به آزمون CI مربوط است و credential، policy یا TTL
اپراتور را تغییر نمی‌دهد.
هر شش هویت باید مقدار مخصوص سرویس خود را دریافت و پس از تغییر آن به‌روز کنند؛
خواندن مسیر سرویس دیگر نباید Secret بسازد. حذف ExternalSecret باید Secret
تحت مالکیت آن را حذف کند. مقدارهای آزمایشی نباید در log اپراتور ظاهر شوند.
پس از حذف کلاستر، فقط receipt عمومی با digest واقعی و نتیجهٔ آزمون حفظ می‌شود.

این آزمون روی kind `1.35.5` اجرا می‌شود؛ آزمون K3s `1.35.6`، admission امضا و
Ambient mTLS روی VPS همچنان مستقل و الزامی‌اند. اجرای CI مجوز نصب مستقیم نیست.
نسخهٔ غیررمزدار اسرار واقعی، خروجی کامل Secret یا فایل custody در artifact قرار نگیرد.

### ساخت candidate امنیتی از سورس رسمی

recipe ثابت `infrastructure/production/release/patched-platform-sources.json`
برای ESO `2.12.0`، OpenBao `2.6.4` و Istio `1.30.5` است. نسخهٔ محصول و chart
حفظ می‌شود؛ compiler برابر Go `1.26.9` با تصویر builder دارای digest ثابت است.
`x/net=v0.60.0` و وابستگی‌های لازم آن (`x/crypto=v0.57.0`، `x/sys=v0.48.0`،
`x/term=v0.46.0` و `x/text=v0.42.0`) ثابت‌اند. archive سورس رسمی هر پروژه به
commit و SHA-256 bind است؛ checksum، sumdb، `go mod verify` و build readonly
خاموش نمی‌شوند. این مسیر ساخت جدید، تصویر رسمی upstream نیست.

فقط فایل‌های Go جایگزین می‌شوند؛ runtime پایهٔ قفل‌شده، کاربران، entrypoint،
گواهی‌ها و فایل‌های iptables حفظ می‌شوند. هر دو `install-cni` و `istio-cni`
بازسازی می‌شوند. OpenBao نسخهٔ `2.6.4` را برای قرارداد recovery حفظ می‌کند؛
UI طبق `openbao-server.json` خاموش می‌ماند. Ztunnelِ Rust و waypoint توسط این
مسیر تغییر نمی‌کنند و بررسی/هماهنگی digest آن‌ها مستقل باقی می‌ماند.

برای بررسی محلی قراردادها، بدون VPS یا credential:

```bash
python3 -m unittest discover -s scripts/production/tests -p test_patched_platform.py
```

ساخت‌های سنگین در workflow `Patched platform source security` انجام می‌شوند:
چهار job با نام‌های `Patched source eso/openbao/istiod/cni`. هر job باید
`PATCHED_PLATFORM=Passed`، scan بدون High/Critical، compiler/dependency صحیح
در تمام فایل‌های اجرایی و رسید candidate تولید کند. artifact عمومی شامل
SBOM، اسکن، recipe تولیدشده، graph ماژول‌ها، hashهای `go.mod/go.sum` و buildinfo
است؛ هیچ image در PR push یا امضا نمی‌شود. موفقیت این چهار job به‌تنهایی
سازگاری runtime، آماده‌بودن Production یا موفقیت سایر گیت‌ها نیست.

انتشار بعدی فقط پس از merge بازبینی‌شده و baseline موفق همان main، از workflow
موجود `production-release.yml` با `release_kind=patched-platform-candidate`
و environment مصوب انجام می‌شود. دستور زیر تنها وقتی اجرا شود که PR بازبینی
و merge شده و baseline روی SHA دقیق همان main موفق باشد؛ موفقیت PR به‌تنهایی کافی نیست.
انتشار صرفاً candidate امضاشده می‌سازد؛ پذیرش digest در admission و نصب هنوز
مرحلهٔ جداگانه و اجرا‌نشده هستند:

```bash
gh workflow run production-release.yml --ref main \
  -f release_kind=patched-platform-candidate
```

انتشار، تمام چهار تصویر را می‌سازد و اسکن می‌کند؛ سپس به packageهای
`platform-eso-v2-patched-private` برای ESO و
`platform-<openbao|istiod|cni>-patched-private` برای بقیه، در GHCR همان حساب می‌فرستد.
با تأیید security-owner، فقط چهار مقصد ثابت ESO، OpenBao، istiod و CNI یعنی
`ghcr.io/hasanjodatshandi/hooshix/platform-eso-v2-patched-private` و
`ghcr.io/hasanjodatshandi/hooshix/platform-openbao-patched-private`،
`ghcr.io/hasanjodatshandi/hooshix/platform-istiod-patched-private` و
`ghcr.io/hasanjodatshandi/hooshix/platform-cni-patched-private` می‌توانند
Public یا Private باشند؛ پسوند نام، اثبات خصوصی‌بودن نیست. visibility واقعی
در `publication.json` ثبت می‌شود. این استثنا شامل تصاویر برنامه یا package دیگر نیست.
عمومی‌بودن این چهار تصویر هیچ گیت digest، اسکن High/Critical، امضا، provenance، SBOM،
admission یا آزمون native را حذف نمی‌کند و دسترسی عمومی سرویس روی VPS نمی‌سازد.
visibility مستقل از مخزن است.
package عمومی `platform-eso-patched-private` مقصد این انتشار نیست؛ آن را برای
استقرار انتخاب نکنید. package عمومی در GitHub قابل تبدیل مجدد به Private نیست؛
از مقصد مصوب بالا استفاده کنید، بدون حذف package یا تغییر visibility مخزن.
پس از push، Syft با احراز هویت موقت CI و TLS مستقیم به registry مراجعه می‌کند؛
manifest digest تنها با تطبیق repository، config digest تصویر ساخته‌شده و
پلتفرم `linux/amd64` پذیرفته می‌شود. `RepoDigests` محلی Docker مرجع انتشار
نیست. سپس SBOM/scan با آدرس immutable همان digest دوباره تولید می‌شود و
config digest نیز مجدداً تطبیق داده می‌شود. قبل از هر push، OpenBao بازسازی‌شده با همان config digest
روی runner موقت آزمون TLS، Shamir، restart، restore، ACL، ممیزی و لغو root
مصنوعی را می‌گذراند؛ failure مانع انتشار همهٔ تصاویر می‌شود. این آزمون هیچ
داده یا root واقعی VPS را تغییر نمی‌دهد. فقط پس از موفقیت همهٔ تصاویر، امضا، provenance از نوع
`patched-upstream-source-build` و CycloneDX امضاشده صادر و signer مثبت/منفی
بررسی می‌شوند. provenance سورس، recipe، compiler، base image، وابستگی‌ها،
hashهای واقعی فایل‌های ماژول و revision مخزن را ثبت می‌کند. خروجی
`publication.json` صرفاً candidate است؛ admission فعلی به‌صورت خودکار آن را
نمی‌پذیرد. آزمون native، update pin/digest و rollout روی VPS مرحلهٔ جداست.

این مرحله فایل یا دادهٔ VPS را تغییر نمی‌دهد؛ Root، اسرار و snapshot موجود
حفظ می‌شوند. هیچ reset/reinit یا rollback سرور برای ساخت candidate لازم نیست.
گیت‌های PR/push/manual اکنون تصویر نهاییِ source candidate را می‌سازند و
اسکن می‌کنند؛ OpenBao در baseline همان آزمون native را هم می‌گذراند و
ztunnel بدون تغییر اسکن می‌شود. گیت scheduled تصاویر upstream قبلی را
همچنان با همان شدت High/Critical پایش می‌کند. baseline جدید می‌تواند اجازهٔ
انتشار candidate را بدهد، نه نصب یا اعلام رفع آسیب‌پذیری روی VPS. تا آزمون و
تصویب digestهای امضاشده، استقرار همچنان مسدود است. روی revision
`1ce7d8ac91f9688996da94ca4ec38ad0f26690b0`، baseline شمارهٔ `38050468774`،
چهار ساخت امنیتی شمارهٔ `38050468775`، staging شمارهٔ `38050468781` و
E2E شمارهٔ `38050468658` همگی `Passed` هستند. baseline شامل آزمون native
OpenBao بازسازی‌شده و انقضای JIT روی runner است؛ این شواهد، نصب ESO یا رفع
آسیب‌پذیری تصویر فعلی VPS را ثابت نمی‌کنند. برای هر تغییر بعدی و merge، نتیجهٔ
CI مربوط به SHA دقیق جدید لازم است؛ پس از merge نیز baseline همان main کنترل شود.

### ارتقای محدود امنیتی mesh موجود

ارتقای `istiod` و `istio-cni-node` با تأیید مالک آماده می‌شود. نسخهٔ Istio
همان `1.30.5` می‌ماند؛ فقط دو تصویر source-patched امضاشده پس از آزمون native
انتخاب می‌شوند. این مسیر نصب اولیه نیست: ztunnel، CA، OpenBao و داده‌های آن،
SSH، ایمیل و storage نباید تغییر کنند. اجرای دوبارهٔ `commission_platform.py`
برای این ارتقا مجاز نیست. روی VPS اول، receipt مقصد با plan بررسی‌شدهٔ main
`351802f1ba55aa11ca5c11740c038e575dc996bf` و digestهای همین pin، نتیجهٔ
`mesh_security_upgrade=Passed` و `openbao_ca_ztunnel_storage=Preserved` دارد.
این شاهد ارتقای همان مقصد است، نه آمادگی Production یا نصب ESO؛ روی سرور
دیگر اجرای مستقل و receipt همان سرور لازم است. سبزشدن baseline به‌تنهایی
شاهد تعویض تصویر VPS نیست.

مرجع انتخاب candidate، `infrastructure/istio/security-upgrade.json` است؛
این فایل pins upstream/chart را عوض نمی‌کند. publication شمارهٔ `38137708512`
به revision دقیق `fa7fbbe3a20be6ba99459209cfb6087d2fecba24` متصل است. سازنده
پیش از تحویل بسته، اجرای موفق workflow انتشار روی main و scan تازهٔ همان
digestها را کنترل می‌کند. هر تصویر، policy مستقل source-build با signer/issuer
دقیق، provenance شامل recipe، سورس، Go، module locks و invocation، و SBOM
امضاشده دارد. policy قدیمی ztunnel و OpenBao حفظ می‌شود.

```bash
python3 -m unittest discover -s scripts/production/tests -p test_mesh_security_upgrade.py
python3 scripts/production/render_mesh_security_upgrade.py --publication /absolute/public-artifact-directory
```

renderer تنها JSON عمومی تولید می‌کند؛ اجرای آن نصب نیست. workflow
`Platform commissioning staging` با `mesh_security_upgrade=true` روی runner
موقت، نسخهٔ نصب‌شدهٔ قبلی را بازسازی می‌کند، OpenBao آزمایشی را initialized
و unsealed می‌کند و داده/هویت آزمایشی می‌سازد؛ سپس مسیر image-only را اجرا
می‌کند. استفاده از predecessor در این fixture، تأیید scan یا مجوز نصب تصویر
آسیب‌پذیر روی سرور جدید نیست. receipt این مسیر scope مستقل
`Disposable Calico/Ambient initialized-store mesh security upgrade` دارد و
بستهٔ bootstrap اولیه نمی‌تواند آن را شاهد نصب اولیه محسوب کند.

روی مقصد، ابتدا native dry-run امضا و security boundary باید موفق شوند؛
پیش‌بررسی تمام فیلدهای renderشده و تعداد/ترتیب عناصر را حفظ می‌کند؛
فقط فیلدهای پیش‌فرض افزوده‌شده توسط API در mapها پذیرفته می‌شوند.
Quantity رشته‌ای API با مقدار عددی همان resource مقایسه می‌شود و
حذف `EnvVar.value` خالی با مقدار خالی معادل است؛ مقدار غیرخالی پذیرفته نمی‌شود.
سپس JSON Patch با شرط resourceVersion و تصویر قبلی، فقط فیلد image دو
workload را تغییر می‌دهد. UID و سایر specها، metadata گواهی، ztunnel و
StatefulSet/PVC/Pod OpenBao قبل/بعد مقایسه می‌شوند. پس از rollout، boundary
فقط digestهای جدید و ztunnel ثابت را می‌پذیرد؛ digest قبلی دو جزء رد می‌شود.
در شکست، داده یا workload حذف و init تکرار نمی‌شود؛ وضعیت جزئی حفظ شود و
با شاهد همان plan بررسی و resume شود. rollback خودکار به تصویر آسیب‌پذیر
وجود ندارد. روی مقصد جدید، تا اجرای بستهٔ مجاز و دریافت receipt مستقل،
ارتقا **Not verified** می‌ماند.

بعد از merge و baseline موفق main، از checkout تمیزِ همان main بسته بسازید؛
شمارهٔ workflow staging موفق همین تغییر را جایگزین `<staging-run>` کنید:

```bash
python3 scripts/production/build_mesh_security_upgrade_bundle.py \
  --staging-run <staging-run> \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-mesh-security-upgrade
```

مسیر output باید تازه و خارج Git باشد. بسته فقط کد/plan عمومی دارد، نه Root،
کلید، token یا share. builder، tree دقیق staging را با main، checks اجباری،
digestها و تازگی شاهد بررسی می‌کند. با VNC قابل ورود و نشست مستقل SSH باز:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-mesh-security-upgrade\run-mesh-security-upgrade.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-mesh-security-upgrade\run-mesh-security-upgrade.ps1' -RescueAndSecondSessionReady
```

فقط رمز sudo در prompt محلی لازم است؛ رمز custody/Root/CA و token جدید GHCR
لازم نیست. رجیستری از Secret موجود استفاده می‌کند. `MESH_SECURITY_UPGRADE=Passed`
و receipt عمومی کنار بسته، شاهد اجرای مقصد هستند. بسته و receipt را نگه دارید؛
خروجی stdout ابزار ارتقا فقط یک شیء JSON عمومی است؛ پیام‌های پیشرفت و خطا
به stderr می‌روند و داخل receipt ذخیره نمی‌شوند. wrapper همان JSON را دقیق
اعتبارسنجی می‌کند؛ متن غیر JSON شاهد موفقیت نیست.
در اجرا‌ی ناموفق، replay همان bundle فقط پس از بررسی وضعیت جزئی مجاز است.
این عملیات کوتاه ممکن است دسترسی mesh را حین rollout ناپایدار کند؛ reboot،
حذف PVC، init، ارتقای ztunnel یا OpenBao بخشی از این فرمان نیست.

### نصب محدود ESO روی OpenBao موجود

این مرحله فقط سه Deployment اپراتور/وب‌هوک/گواهی‌ساز ESO و شش SecretStore
`hooshix-<service>` را در `platform-apps` نصب می‌کند. CRDهای namespace-scoped،
RBAC محدود، NetworkPolicy و مجوز mTLS مربوط نیز جزو همین bootstrap هستند.
وب‌هوک‌ها فقط SecretStore/ExternalSecret همان namespace را اعتبارسنجی می‌کنند؛
rule نوع Cluster از خروجی حذف می‌شود، چون namespaceSelector آن را محدود نمی‌کند.
افزودن namespace به مرزهای پذیرش قبلی فقط با آزمون اتمی UID و کل spec انجام
می‌شود؛ بازسازی شیء یا تغییر تنظیمات بررسی‌شده پذیرفته نمی‌شود.
در بازاجرا، حذف فهرست خالی ingress/egress توسط API فقط وقتی معادل منع کامل
محسوب می‌شود که همان جهت در policyTypes صریحاً باقی مانده باشد.
نبود hostNetwork در پاسخ API معادل false است؛ مقدار true همچنان drift مردود است.
در نصب و آزمون مستقل زیرساخت، پیش از ساخت منابع ESO، وضعیت Established هر CRD از پاسخ کوچک status خوانده
می‌شود؛ مهلت محدود است و فقط مقدار True اجازهٔ ادامه می‌دهد.
Root، intermediate، TLS موجود، داده/PVC و workload OpenBao، mesh، SSH و ایمیل
تغییر نمی‌کنند. OpenBao باید از قبل unsealed و شش هویت scoped آماده باشند؛
نشست VNC قابل ورود و نشست دوم SSH مستقل باز بمانند. هیچ init یا root revocation
در این فرمان وجود ندارد. بسته به نصب اول bind است؛ برای سرور دوم ابتدا pin و
CA/شناسه‌های همان نصب باید در تغییر بازبینی‌شده مستقل آماده شوند.

job `Signed Calico Ambient Kyverno OpenBao staging` همین ابزار نصب را روی
Calico/Ambient واقعی اجرا می‌کند و بازاجرای بدون drift، digest runtime، امضا،
provenance دقیق recipe/module/revision، SBOM و رد signer/payload جعلی را می‌سنجد.
تحویل و چرخش شش مقدار ساختگی، رد خواندن مسیر سرویس دیگر، حذف Secret دارای
مالک و نبود مقادیر در log نیز لازم‌اند. فقط receipt عمومی پس از حذف fixture
باقی می‌ماند. این شاهد جای اجرای K3s مقصد یا capacity کامل را نمی‌گیرد.

پس از merge و baseline موفق، از checkout تمیز main با Helm مصوب `4.2.4`
و chart دارای checksum ثبت‌شده در `eso-image.json` بسته بسازید؛ staging-run
باید اجرای موفق ترکیبی همین tree باشد، نه آزمون foundation یا mesh تنها:

```bash
python3 scripts/production/build_eso_commissioning_bundle.py \
  --staging-run <staging-run> --chart <external-secrets-2.12.0.tgz> \
  --helm <helm-4.2.4> \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-eso-commissioning
```

builder انتشار protected-main، اسکن پنج‌روزه، predicate و digest دقیق و tree
staging را اعتبارسنجی می‌کند. output باید تازه و خارج Git باشد؛ فقط source/plan
عمومی دارد. token یا Root/custody در این پوشه قرار ندهید. روی Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-eso-commissioning\run-eso-commissioning.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-eso-commissioning\run-eso-commissioning.ps1' -RescueAndSecondSessionReady
```

فقط رمز sudo را در همان پنجره وارد کنید؛ GHCR token و رمز custody/Root/CA
لازم نیست. ابزار credential خواندنِ متعلق به نصب را فقط در حافظه به namespace
جدید منتقل می‌کند؛ توکن روی argv، log یا receipt نوشته نمی‌شود. API IP و audience
از همان کلاستر کشف می‌شوند؛ شبکهٔ عمومی باز نمی‌شود. سه Pod و شش SecretStore
باید Ready شوند؛ `ESO_INSTALLATION=Passed` و JSON عمومی کنار bundle شاهد مقصدند.
پیام پیشرفت به stderr و فقط JSON به stdout می‌رود. وضعیت Production و تحویل
اسرار واقعی همچنان `Not verified` است؛ public access همچنان `CLOSED` می‌ماند.

شاهد نصب اول در ۲۰۲۶-۱۰-۱۲:
`hooshix-eso-commissioning-d6ca935be93a41cc915dcdd93267519b.json` از اجرای
bundle بررسی‌شدهٔ `main@5fcaca8540875d742e4194ab215054610d872e9b`،
`ESO_INSTALLATION=Passed` و شش SecretStore Ready را ثبت کرد. plan SHA-256 برابر
`49d2b04c59b2e52f306b1bbf6d6596492a01d475b695b01ed2c7e855df894196`
است. نتیجهٔ سرور دوم باید مستقل تولید شود؛ نصب اول را تکرار نکنید.

نصب در namespace دارای برنامه/ExternalSecret فعال، مالکیت خارجی یا drift متوقف
می‌شود؛ adopt اجباری، force ownership یا حذف منبع وجود ندارد. در شکست، منابع
نصب‌شده حفظ می‌شوند؛ قبل از تکرار، علت و state جزئی را بررسی کنید. داده یا PVC
OpenBao را پاک نکنید. نصب موفق شاهد backup خارج میزبان یا آماده‌بودن JIT نیست.

کاربرد روزمره: manifest هر سرویس به SecretStore خودش `hooshix-<service>` اشاره
می‌کند، `creationPolicy: Owner` دارد و فقط کلید `production/<service>/<name>`
را از mount `hooshix` می‌خواند. مقدار واقعی فقط با هویت عملیاتی مجاز وارد
OpenBao می‌شود؛ نه Git، Helm values یا CI. منابع برنامه از مسیر GitOps مصوب
تحویل می‌شوند، نه فرمان bootstrap. تغییر مقدار طبق refreshInterval همان
ExternalSecret همگام می‌شود؛ reload/restart مصرف‌کننده طبق قرارداد سرویس انجام
می‌شود و ESO به‌تنهایی آن را تضمین نمی‌کند. نام Store/Secret و وضعیت Ready قابل
بررسی‌اند؛ `kubectl get secret -o yaml/json` یا خروجی مقدار را برای گزارش نفرستید.

### مرز نصب PostgreSQL و artifactهای آن

نصب داده به چهار artifact مستقل نیاز دارد: اپراتور CloudNativePG، تصویر minimal
PostgreSQL، اپراتور Barman Cloud و sidecar آن. نسخه‌های مصوب در
`infrastructure/production/data/data-policy.json` مرجع هستند؛ تصویر Docker آزمایشی
تست SQL، جای تصویر سازگار با CNPG را نمی‌گیرد. هر چهار تصویر باید digest ثابت،
SBOM و اسکن تازه داشته باشند؛ سپس از مسیر انتشار مصوب امضا و در محیط مستقل
آزمایش شوند. این آماده‌سازی نصب روی VPS یا تأیید backup خارج میزبان نیست.

pinهای `linux/amd64` و checksum manifestهای رسمی در
`infrastructure/production/data/postgresql-images.json` قرار دارند. این catalog
فعلاً مسدود برای promotion است؛ از tag متغیر یا تصویر fixture برای نصب استفاده
نکنید. چهار جزء عبارت‌اند از `operator`، `postgresql`، `barman-plugin` و
`barman-sidecar`. ورودی رسمی minimal CNPG برای پایش مستقل نگه داشته می‌شود؛ ابزار
backup در sidecar جداست و نباید در تصویر برنامه یا دیتابیس دوباره نصب شود.

نسخه‌های انتخاب‌شده CNPG 1.30.1، PostgreSQL 18.6 و plugin/sidecar Barman 0.15.1
هستند؛ تغییر هم‌زمان نسخه‌ها در profile، data-policy و platform-contracts کنترل
می‌شود. fixture قدیمی 18.4 فقط آزمون SQL است و شاهد سازگاری 18.6 نیست.

candidateهای نهایی operand و sidecar از پایهٔ Alpine 3.24.2 با نسخه‌های ثابت
OpenSSL 3.5.9-r0 و zlib 1.3.2-r1 ساخته می‌شوند. operand همان PostgreSQL 18.6
است؛ sidecar از Python 3.14.8 و Barman 3.20.1 استفاده می‌کند. نسخه و hash تمام
dependencyهای Barman از lock رسمی همان source بررسی می‌شود.
override محدود `certifi==2026.10.19` با SHA-256 ثابت wheel و sdist فقط همان
بخش lock رسمی را جایگزین می‌کند؛ باقی نسخه‌ها و `--require-hashes` حفظ می‌شوند.
ریشهٔ آسیب‌دیدهٔ `emSign ECC Root CA - G3` از Requests، دو bundle همراه pip و
منبع گواهی سیستم حذف می‌شود؛ `update-ca-certificates` bundle و لینک‌های hash
سیستم را بازسازی می‌کند. bundleهای pip دقیقاً از بستهٔ رسمی اصلاح‌شده کپی
می‌شوند، نه با دستکاری شمارهٔ نسخه. ریشه‌های سالم G1/C1/C3 باید باقی بمانند.
گیت inventory نبود نسخهٔ آسیب‌پذیر و آزمون native نبود fingerprint خطرناک در
تمام مسیرهای فوق، botocore و مسیر پیش‌فرض Python/OpenSSL را کنترل می‌کند.
نسخهٔ Python، `pip check` و چهار فرمان backup/WAL/restore Barman نیز آزموده
می‌شوند؛ خاموش‌کردن TLS یا استفاده از تصویر قبلی جایگزین این اصلاح نیست.
اصلاح build به‌تنهایی نصب VPS نیست: انتشار محافظت‌شدهٔ مجموعهٔ ثابت، اسکن
تازه، signature/provenance/SBOM، انتخاب digest جدید و staging همچنان الزامی‌اند.
نصب بدون hash، کاهش سطح گیت یا استفاده از tag متغیر مجاز نیست. اسکن مجموعهٔ candidate و آزمون
مستقل CNPG/WAL/PITR با دادهٔ مصنوعی `Passed` است؛ تأیید انتشار، staging و نصب
روی VPS هنوز `Not verified` است. هیچ digestی صرفاً با تغییر پایه مجوز نصب نمی‌گیرد.

ابزار `build_postgresql_candidates.py` سه جزء Go را از source رسمی دارای commit
و checksum ثابت در `data/postgresql-source-builds.json`، با Go 1.26.9 و x/net
0.60.0 می‌سازد. هر دو manager داخل تصویر CNPG، حتی باینری arm64 همراه تصویر
amd64، جایگزین می‌شوند. plugin استاتیک روی پایهٔ distroless ثابت و با همان UID
65532 و entrypoint ساخته می‌شود. sidecar و operand حساب معتبر UID/GID 26 و
entrypoint صریح دارند؛ runtimeهای Python و PostgreSQL به digest ثابت pin شده‌اند.
پوشش APK، Python و همهٔ باینری‌های Go در SBOM الزامی است. Go و Python از هم
جدا ساخته می‌شوند؛ compiler و headerهای build وارد runtime نمی‌شوند.
operand دارای provenance مجزای `patched-upstream-runtime-build` است، نه ادعای
ساخت دوبارهٔ باینری PostgreSQL؛ سه جزء Go `patched-upstream-source-build` هستند.
امضا، reconcile و WAL/PITR روی مقصد واقعی و promotion هنوز `Not verified` است.

برای هر ساخت جدید، scanner و smoke باید دوباره اجرا شوند. native fixture
operand پنج دیتابیس، RLS اجباری، context تراکنشی، replay غیرمخرب و منع ۴۰ اتصال
بین‌سرویسی را روی PostgreSQL 18.6 با locale صریح `C.UTF-8`، بدون شبکه و با
دادهٔ RAM-only بررسی می‌کند. این آزمون جای reconcile اپراتور، migration واقعی
سرویس‌ها یا بازیابی فیزیکی را نمی‌گیرد. sidecar باید interpreter دقیق، `pip check`
و CLIهای backup/WAL/restore را با filesystem فقط‌خواندنی و بدون شبکه بگذراند.

آزمون عمومی بدون credential:

```bash
python3 -m unittest discover -s scripts/production/tests -p 'test_postgresql*.py'
```

در checkout تمیز بررسی‌شده، برای ساخت/اسکن محلی candidate نهایی، Docker محلی
با Unix socket و ظرفیت build کافی لازم است؛ این دستورات روی VPS اجرا نمی‌شوند
و نیاز به sudo، توکن GHCR، رمز Root یا custody ندارند. Docker remote مجاز نیست:

```bash
scan_root=$(mktemp -d)
scripts/production/install_release_tools.sh "$scan_root/tools"
export PATH="$scan_root/tools:$PATH"
export SYFT_GOLANG_CAPTURE_SYMBOLS=all
export GRYPE_DB_AUTO_UPDATE=false GRYPE_DB_VALIDATE_AGE=true
export GRYPE_DB_MAX_ALLOWED_BUILT_AGE=120h
grype db update
(for component in operator postgresql barman-plugin barman-sidecar; do
   python3 scripts/production/build_postgresql_candidates.py \
     --component "$component" --directory "$scan_root/$component" || exit 1
 done)
```

اسکن هر جزء پوشهٔ تازهٔ مستقل می‌خواهد و SBOM، database و findingهای عمومی را
همان‌جا نگه می‌دارد. `scan: Passed` فقط با تطابق digest/platform/label، SBOM
غیرخالی و database معتبر حداکثر پنج‌روزه و نبود High/Critical تولید می‌شود.
خروجی شکست اجازهٔ انتشار/نصب نیست؛ دورزدن finding یا `--only-fixed` مجاز نیست.
پیکربندی scanner از تنظیمات شخصی جداست: exclude و انتخاب محدود cataloger پذیرفته
نمی‌شود و High/Critical موجود در `ignoredMatches` هم باعث شکست است. inventory
Go، Python و بسته‌های سیستم‌عامل باید با scanner ثابت کامل باقی بماند.
در source-build، config digest محلی و همهٔ executableهای Go نیز کنترل می‌شوند؛
شناسهٔ engine ممکن است OCI index باشد؛ receipt آن را از config digest داخل SBOM
جدا نگه می‌دارد و گزارش Grype باید همان config را اسکن کرده باشد.
اجرای `--help` با کاربر non-root، filesystem فقط‌خواندنی، بدون شبکه و با منابع
محدود، فقط سلامت entrypoint است، نه تست reconcile یا recovery. تصویر محلی را
روی VPS push یا نصب نکنید؛ artifact نهایی هنوز باید از مسیر انتشار مصوب عبور
کند. برای پایش جداگانهٔ ورودی رسمی، `verify_postgresql_artifacts.py --scan` با
هر کدام از چهار component قابل اجراست و findingهای upstream را پنهان نمی‌کند.
workflow `PostgreSQL candidate artifacts` همین چهار گیت نهایی را در PR/push مربوط
یا اجرای دستی، بدون credential و با حداکثر دو job هم‌زمان اجرا می‌کند.

فقط پس از موفقیت اسکن و آزمون‌های native، گزینهٔ `--export-image` آرشیو همان
تصویر محلی را برای آزمون مستقل تولید می‌کند. receipt اندازه و SHA-256 آرشیو را
ثبت می‌کند؛ CI تصویر عمومی بدون secret را کنار evidence نگه می‌دارد، نه در
GHCR یا VPS. انتقال آرشیو مجوز deploy نیست؛ مصرف‌کننده باید SHA-256 آرشیو و
config digest، labelهای recipe و SBOM را دوباره تطبیق دهد. موفقیت CI جای آزمون
مقصد را نمی‌گیرد. آرشیو خالی یا بزرگ‌تر از یک GiB حذف می‌شود و receipt موفق
نمی‌گیرد. هنگام import بین Docker و containerd، شناسهٔ engine ممکن است تغییر کند؛
از روی نام tag یا شناسهٔ engine فرض نکنید config همان است. hash آرشیو و محتوای
config باید با receipt/SBOM تطبیق داشته باشد. برای runtime operand/sidecar،
locale، UID/GID، نسخهٔ Python و dependencyهای Barman باید مشخص و با آزمون
واقعی SQL، CNPG و WAL/PITR بررسی شوند؛ صرف تعویض نام تصویر مجاز نیست.
receipt اسکن، signature/provenance، staging، deployment و promotion را صریحاً
`Not verified` نگه می‌دارد. checksum manifest برای بستهٔ بعدی است؛ این verifier
manifest را روی cluster apply یا برای نصب مجاز نمی‌کند. منبع ثبت‌شدهٔ label
تصویر و commit tag لزوماً یکی نیستند؛ هیچ ادعای build provenance از tag نمی‌شود.

مرز نتیجهٔ فعلی candidateهای بررسی‌شده:

| آزمون | نتیجه | مرز شاهد |
| --- | --- | --- |
| build، SBOM و Grype هر چهار تصویر | `Passed` | run `38353924447`، commit `0931a1359671adc357a1a7db507d1c38d0948023`؛ بدون High/Critical یا ignored High/Critical |
| SQL/RLS روی operand 18.6 | `Passed` | پنج دیتابیس، context تراکنشی و replay غیرمخرب؛ fixture موقت و بدون شبکه |
| reconcile اپراتور و operand | `Passed` | Kubernetes 1.35.5 موقت؛ ده role و پنج Database و منع ۴۰ اتصال احراز هویت‌شدهٔ بین‌سرویسی |
| Barman base backup، WAL و PITR کل cluster | `Passed` | مقصد S3 مصنوعی RAM-only، restore به cluster جدا، حذف تغییر بعد از نقطه و حفظ دیتابیس اصلی |
| باکت واقعی، TLS/lock/retention و RPO/RTO مقصد | `Not verified` | آزمون مصنوعی جای شاهد provider و مقصد واقعی نیست |

برای عملیات نهایی، از manifest رسمی دارای checksum، تصویر منتشرشدهٔ ثابت و
متادیتای major version صریح CNPG استفاده کنید. fixture tagها برای انتقال داخل
محیط موقت هستند و هرگز مقصد production نیستند. sidecar بومی Barman در
`initContainers` با `restartPolicy: Always` دیده می‌شود؛ Ready بودن را از condition
خود Pod و وضعیت sidecar بگیرید، نه فقط شمارش `spec.containers`.
در بازیابی نام‌دار، `bootstrap.recovery.recoveryTarget` باید `backupID` واقعی
همان base backup و `targetName` نقطهٔ ساخته‌شده با `pg_create_restore_point()` را
داشته باشد؛ منبع در `externalClusters` به ObjectStore و `serverName` cluster اصلی
متصل است. restore همیشه روی cluster جدا انجام می‌شود. آزمون باید محتویات هر پنج
دیتابیس، منع اتصال بین‌سرویسی و دست‌نخورده‌ماندن cluster اصلی را بررسی کند.
شاهد فعلی شامل migration واقعی سرویس‌ها، replay حذف داده/erasure و RLS پس از
restore نیست؛ این‌ها در مرحلهٔ پذیرش مقصد جداگانه لازم‌اند. پس از staging، تصویر
را دوباره نسازید؛ همان digest امضاشده و آزموده‌شده باید promote شود.

### انتشار candidateهای PostgreSQL

انتشار چهار candidate یک مجموعهٔ واحد است و فقط از workflow محافظت‌شدهٔ
`Production release evidence` روی `main` انجام می‌شود. ساخت و اسکن PR مجوز نصب
نیست؛ publication، staging و پذیرش مقصد شواهد جداگانه دارند. با تأیید صریح
security-owner، فقط چهار مقصد ثابت همین بخش می‌توانند `Public` یا `Private`
باشند. این استثنا محدود به تصاویر متن‌باز زیرساخت است؛ شامل تصاویر محصول،
اسرار، package دیگر یا بازکردن سرویس روی VPS نیست. امضا، SBOM، اسکن تازه و
گیت‌های پذیرش بدون تغییر می‌مانند. اجرای انتشار به رمز VPS، کلید Root یا فایل
custody نیاز ندارد.

پس از merge تغییر بررسی‌شده و موفقیت `repository-baseline.yml` برای SHA دقیق
`main`، از checkout به‌روز با دسترسی GitHub مالک اجرا کنید:

```bash
gh workflow run production-release.yml --ref main -f release_kind=postgresql-candidate
gh run list --workflow production-release.yml --event workflow_dispatch --limit 5
# RUN_ID را از اجرای همین فرمان انتخاب کنید؛ artifactها عمومی از نظر محتوا هستند، نه دسترسی.
gh run download RUN_ID --name postgresql-publication-RUN_ID-1 --dir /path/outside-repo/postgresql-publication
```

اگر attempt غیر از ۱ بود، نام artifact را مطابق همان attempt انتخاب کنید. منتظر
نتیجهٔ نهایی run بمانید؛ `publication.json` فقط بعد از موفقیت هر چهار جزء تولید
می‌شود. مقدار `publication` و `signature_provenance` باید `Passed` باشد؛ visibility
واقعی هر چهار مقصد در `registry_visibility` ثبت می‌شود و فقط `public` یا `private`
برای مقصدهای مصوب پذیرفته است. مقصدهای ثابت زیر همگی زیر
`ghcr.io/hasanjodatshandi/hooshix/` هستند:

- `platform-postgresql-operator-v2-private`
- `platform-postgresql-postgresql-private`
- `platform-postgresql-barman-plugin-private`
- `platform-postgresql-barman-sidecar-private`

محیط `production-release` تأیید مالک را می‌خواهد. اگر run در وضعیت
`Waiting for review` بود، مالک در صفحهٔ همان run گزینهٔ `Review deployments`
و سپس تأیید محیط را انجام می‌دهد. rule را حذف یا bypass نکنید؛ انتظار تأیید،
خطای build نیست و اجرای تکراری لازم ندارد.

workflow از توکن موقت `GITHUB_TOKEN` و هویت امضاکنندهٔ موجود
`production-release.yml@refs/heads/main` استفاده می‌کند. توکن شخصی، Secret VPS،
کلید Root و credential باکت را وارد نکنید. فایل موقت login در پایان حذف می‌شود؛
آرشیو source در artifact خروجی قرار نمی‌گیرد. visibility package مستقل از مخزن
و پسوند نام است؛ ابزار آن را قبل از push، بعد از push و بعد از امضا بررسی و ثبت
می‌کند. `internal` یا وضعیت نامعتبر پذیرفته نیست؛ 401/403 هرگز
به‌عنوان نبود package پذیرفته نمی‌شود. تغییر visibility خودکار مجاز نیست.

package عمومی قابل برگشت به private نیست؛ این پذیرش محدود اجازهٔ عمومی‌کردن
تصویر محصول، حذف package یا استفاده از مقصد دیگر نیست. مقصد اپراتور فقط
`platform-postgresql-operator-v2-private` است؛ نام قدیمیِ بدون `v2` adopt نمی‌شود.
پیام `POSTGRESQL_PACKAGE_VISIBILITY` نام جزء و وضعیت محدودشده را نشان می‌دهد؛
در صورت رد، run را بی‌تغییر تکرار نکنید. imageها فقط از source رسمی و runtime
مصوب ساخته می‌شوند؛ credential CI خارج build context است و هیچ credential
VPS، دادهٔ کاربر یا source برنامه وارد این چهار تصویر نمی‌شود.

ابزار `publish_postgresql_candidates.py` همهٔ buildها و native smokeهای موجود را
پیش از push اجرا می‌کند، config محلی را با manifest واقعی رجیستری تطبیق می‌دهد و
دوباره روی digest ثابت SBOM کامل و Grype تازه می‌گیرد. پیش از اولین امضا، اسکن
هر چهار digest باید موفق باشد. سپس امضای keyless، SLSA v1 و CycloneDX با هویت
دقیق workflow و محتوای predicate/subject بررسی می‌شوند؛ هویت اشتباه باید رد و
کنترل مثبت دوباره موفق شود. provenance operand فقط patch روی runtime رسمی
است، نه ادعای rebuild باینری PostgreSQL. هر manifest/config/engine digest نقش
جدا دارد؛ برای نصب فقط `images` در receipt نهایی، همراه شواهد پذیرش، معتبر است.

این عملیات نصب، staging، admission مقصد یا promotion نیست و آن چهار نتیجه در
receipt همچنان `Not verified` می‌مانند. موفقیت انتشار جای آزمون native مستقل
همان digest و بازیابی واقعی را نمی‌گیرد. اگر مرحله‌ای شکست خورد، imageهای push
شده و شواهد جزئی برای بررسی حفظ می‌شوند؛ آن‌ها مجموعهٔ مجاز نیستند. حذف، reinit،
تکرار خودکار یا استفاده از receipt قدیمی انجام نمی‌شود. اجرای بعدی فقط بعد از
رفع علت مشخص و با پوشهٔ evidence تازه است. defer باکت پارس‌پک دست‌نخورده است.

مرجع رفتار: [انتشار GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)،
[امضای digest و بررسی هویت Cosign](https://github.com/sigstore/cosign).

### پذیرش مجموعهٔ منتشرشدهٔ PostgreSQL

operand PostgreSQL فقط با `libcurl=8.23.0-r0` روی Alpine 3.24.2
ساخته می‌شود؛ این pin اصلاح `CVE-2026-92392` است.
مجموعهٔ انتخاب‌شدهٔ زیر با این pin منتشر شده است؛ SBOM و اسکن تازه و پذیرش
digestها پیش از استفاده الزامی است؛
این تغییر مجوز ارتقا یا restart دیتابیس VPS نیست.
بدون موفقیت پذیرش و staging، انتشار به‌تنهایی مجوز promotion نیست؛
صرف تغییر pin به معنی رفع تصویر قبلی نیست.
مرجع: [اطلاعیهٔ امنیتی رسمی curl](https://curl.se/docs/CVE-2026-92392.html).

ساخت جدید operand و Barman sidecar باید بستهٔ امضاشدهٔ Alpine
`brotli-libs=1.2.1-r0` را داشته باشد؛ inventory فاقد بسته، قدیمی یا دارای نسخهٔ
آسیب‌پذیر تکراری، حتی بدون finding اسکنر، رد می‌شود. این pin اصلاح
`CVE-2026-111497` است؛ مجموعهٔ انتخاب‌شدهٔ زیر با این اصلاح ساخته و منتشر شده است.
پذیرش digestها و staging همچنان مستقل از انتشار الزامی هستند. انتخاب تصویر تازه
در Git به‌تنهایی PostgreSQL روی VPS را به‌روزرسانی نمی‌کند و مجوز تعویض، reset
یا reinit دیتابیس نیست. نسخهٔ قرنطینه‌شدهٔ قدیمی تا ارتقای بررسی‌شده و آزموده‌شده،
برای داده و ترافیک واقعی Production تأییدشده محسوب نمی‌شود.

مرجع: [انتشار امنیتی Brotli 1.2.1](https://github.com/google/brotli/releases/tag/v1.2.1)،
[بسته و secfix رسمی Alpine 3.24](https://github.com/alpinelinux/aports/blob/3.24-stable/main/brotli/APKBUILD).

اجرای محافظت‌شدهٔ `40333333697`، attempt `1` روی
`main@c16176096b526897a5734ec1e3378cf8ccc3d9d5` انتشار هر چهار تصویر را با
`publication: Passed` و `signature_provenance: Passed` تکمیل کرده است. visibility
واقعی هر چهار package، `public` است و در همان استثنای محدود مصوب قرار دارد.
این نتیجه فقط انتشار مجموعه را ثابت می‌کند؛ نصب مقصد، admission، staging و
promotion هنوز شواهد مستقل می‌خواهند. receipt عمومی و digestها در مرحلهٔ پذیرش
بررسی می‌شوند؛ هیچ رمز VPS، custody، کلید Root یا credential باکت لازم نیست.
مجموعهٔ انتخاب‌شده در `published_security_candidate` فایل
`infrastructure/production/data/postgresql-images.json` ثبت شده است. ورودی‌های
upstream همان فایل همچنان ورودی ساخت هستند، نه جایگزین این چهار digest نهایی.

artifact همان اجرا را بیرون Git دانلود کنید و روی میزبان توسعه با Docker محلی
Unix و ابزارهای integrity-pinned اجرا کنید؛ این فرمان VPS را متصل نمی‌کند:

```bash
gh run download 40333333697 --name postgresql-publication-40333333697-1 --dir /path/outside-repo/postgresql-publication
bash scripts/production/install_release_tools.sh /path/outside-repo/release-tools
PATH=/path/outside-repo/release-tools:$PATH python3 scripts/production/verify_published_postgresql.py \
  --evidence-dir /path/outside-repo/postgresql-publication \
  --output-dir /path/outside-repo/postgresql-acceptance-new
```

مسیرها نمونه‌اند؛ هر بار پوشهٔ خروجی تازه انتخاب کنید. ابزار ابتدا hash receipt و
کل مجموعه را بررسی می‌کند، سپس **همان digestهای منتشرشده** را با Syft/Grype
مصوب و دیتابیس به‌روز دوباره اسکن می‌کند. manifest/config، inventory کامل،
نبود exclusion/suppression برای High/Critical و سقف پنج‌روزهٔ دیتابیس حفظ می‌شود.
scan قدیمیِ artifact جای scan تازه نیست و منقضی‌شدن آن نیاز به rebuild یا امضای
مجدد تصویر ندارد. گزارش تازه زیر `<output>/<component>/registry` می‌ماند و hash
پنج فایل آن در `scan_evidence_sha256` رسید پذیرش ثبت می‌شود؛ artifact انتشار
و SBOM امضاشدهٔ اصلی تغییر نمی‌کنند.
در CI، فقط JSONهای inventory/scan عمومی این digestها و رسید پذیرش در artifact
`platform-image-reacceptance-<run>-<attempt>` نگه داشته می‌شوند؛ log یا فایل fixture،
credential و cache شخصی در آن نیست. artifact محتوای‌زدودهٔ runtime جدا می‌ماند.
برای trust فقط mirror رسمی مصوب `https://sigstore.github.io/root-signing` را با
ریشهٔ embedded خود Cosign و cache تازه زیر پوشهٔ خروجی initialize می‌کند. root
جایگزین، staging trust یا skip verification ندارد؛ تنظیمات trust محیط و cache
شخصی شما وارد آزمون نمی‌شوند. این Sigstore trust با Root آفلاین HooshiX متفاوت است.
سپس امضا و SLSA/CycloneDX را مستقل از رجیستری با signer دقیق CI بررسی می‌کند؛
هویت اشتباه باید رد و کنترل مثبت دوباره موفق شود. هیچ `sign`، `attest`، push،
rebuild، sudo یا خواندن credential Production انجام نمی‌شود.

پس از تأیید هر چهار تصویر، همان digestها pull می‌شوند. engine ID محلی با config
و manifest اشتباه گرفته نمی‌شود. entrypointهای non-root و Python/Barman تحت
read-only، بدون شبکه، capabilities یا پورت میزبان و با منابع محدود آزمایش
می‌شوند. PostgreSQL 18.6 با tmpfs موقت، پنج دیتابیس، ۴۰ منع اتصال بین‌سرویسی و
forced RLS/context اجرا می‌شود؛ container همان آزمون در پایان حذف می‌شود.
تصاویر pullشده برای آزمون بعدی باقی می‌مانند.

خروجی مورد انتظار `POSTGRESQL_PUBLISHED_ACCEPTANCE=Passed` و فایل عمومی
`acceptance.json` در پوشهٔ تازه است. فقط native smoke/SQL را ثابت می‌کند، نه
CNPG reconciliation، admission، staging، physical recovery یا نصب مقصد.
شکست جزئی receipt موفق تولید نمی‌کند؛ inputها و شواهد موجود پاک نمی‌شوند.
اگر بازاسکن جاری شکست خورد، آن را Passed فرض نکنید؛ گزارش تازهٔ همان digest
بررسی شود. هیچ clock backdate، افزایش سن مجاز یا نادیده‌گرفتن High/Critical
وجود ندارد. موفقیت این بخش گیت‌های نصب یا defer پارس‌پک را دور نمی‌زند.

مرجع trust و cache: [Cosign 3.0.6 initialize](https://github.com/sigstore/cosign/blob/v3.0.6/cmd/cosign/cli/initialize/init.go).

اجرای مستقل روی میزبان توسعه در `2026-10-13T11:37:24Z`، بررسی امضا/SLSA/SBOM،
رد signer اشتباه، entrypointهای هر چهار digest و fixture پنج دیتابیس را با
`Passed` ثبت کرده است. SHA-256 رسید عمومی:
`5098b00b5591eafa7a39b98a8b9117d40a65d948a7758e35adcc28fe331f81e6`.
این شاهد native همان digestهاست؛ CNPG/admission/PITR/نصب مقصد را موفق اعلام نمی‌کند.

### تصاویر وابستگی TLS: cert-manager

برای Barman 0.15.1، cert-manager 1.20.3 همان وابستگی baseline پروژه است؛
اضافه‌کردن آن Root آفلاین HooshiX یا CA موجود Istio را جایگزین نمی‌کند.
security-owner انتشار `Public` یا `Private` را فقط برای سه مقصد ثابت زیر، زیر
`ghcr.io/hasanjodatshandi/hooshix/` تأیید کرده است:

- `platform-cert-manager-controller`
- `platform-cert-manager-cainjector`
- `platform-cert-manager-webhook`

این مجوز فقط visibility تصاویر متن‌باز زیرساخت است؛ شامل acmesolver، startup
job، package دیگر، تصاویر محصول، اسرار یا بازکردن سرویس نیست. اسکن کامل تازه،
امضا، provenance، signed SBOM و گیت‌های پذیرش و نصب همچنان الزامی‌اند.
این بخش فقط candidate انتشار می‌سازد، نه نصب TLS یا readiness تولید؛ نتیجهٔ
نصب اجراشدهٔ VPS در بخش commissioning پایین ثبت شده است.

انتشار محافظت‌شدهٔ کل سه تصویر در run `38409447796`، attempt `1`، از
`main@c5dd3c0012139e7969606bc074f207adebd65d26` با **Passed** ثبت شده است.
visibility واقعی فقط همین سه مقصد `public` است و در استثنای محدود بالا قرار دارد.
SHA-256 فایل عمومی `publication.json`:
`b4a7c34a6212ca564ac4ba09cc191b971dae4031da458331355b52f052deb2e8`.
این رسید انتشار، به‌تنهایی مجوز نصب یا شاهد TLS/admission/staging نیست.

سورس رسمی نسخهٔ `1.20.3`، commit و SHA-256 آرشیو، manifest رسمی و digest
runtime هر جزء در `infrastructure/production/data/cert-manager-sources.json`
ثابت‌اند. باینری هر سه جزء از همان سورس، با `Go 1.26.9`، `x/net 0.60.0` و
`gRPC-Go 1.83.2` بازسازی می‌شود؛ نسخهٔ محصول تغییر نمی‌کند. provenance آن
`patched-upstream-source-build` است، نه import بدون تغییر. ماژول ریشه و ماژول
جداگانهٔ هر `cmd` با checksum database و `go mod verify` بررسی می‌شوند؛
`go mod tidy` در همان ماژول جزء، فایل‌های قفل را پیش از build readonly همگام می‌کند؛
buildinfo و hash چهار فایل go.mod/go.sum در تصویر نهایی نگه داشته می‌شود.

برای ساخت و اسکن بدون credential روی میزبان توسعه، از checkout مربوط به
تغییر و ابزارهای checksum-pinned پروژه استفاده کنید. پوشهٔ خروجی باید تازه و
خارج از Git باشد؛ این فرمان هیچ تصویر یا فایل production را منتشر نمی‌کند:

```bash
scripts/production/install_release_tools.sh /tmp/hooshix-cert-manager-tools
export PATH=/tmp/hooshix-cert-manager-tools:$PATH
python3 scripts/production/build_cert_manager_candidates.py \
  --component controller --directory /tmp/hooshix-cert-manager-controller-evidence
```

برای `cainjector` و `webhook` نیز component و پوشهٔ تازهٔ مستقل را جایگزین کنید.
Docker باید endpoint محلی Unix داشته باشد. اجرای محدود `--help` فقط entrypoint
را می‌آزماید؛ صحت Kubernetes، صدور گواهی یا نسخهٔ سرویس را ثابت نمی‌کند.
workflow `Cert-manager candidate artifacts` همین build/scan را برای هر سه جزء
در PR و main، بدون token نوشتن registry یا OIDC اجرا می‌کند.

پس از merge و موفقیت baseline همان SHA روی main، انتشار فقط از workflow
محافظت‌شده انجام می‌شود؛ تأیید واقعی owner در environment `production-release`
همچنان لازم است:

```bash
gh workflow run production-release.yml --ref main -f release_kind=cert-manager-candidate
```

در GitHub Actions، همان run را باز و در صورت درخواست `Review deployments`،
فقط environment `production-release` را بررسی/تأیید کنید؛ dispatch را تکرار
نکنید. این workflow فقط token موقت همان repository و OIDC با signer ثابت
استفاده می‌کند؛ هیچ PAT یا secret مقصد را در فرمان/چت وارد نکنید.
artifact `cert-manager-publication-<run-id>-<attempt>` را پس از موفقیت دریافت
کنید. `publication.json` فقط زمانی موفق نوشته می‌شود که کل سه تصویر ساخته،
اسکن محلی و registry تازه و کامل شده، سپس امضا و SLSA/CycloneDX دقیق هر digest
و signer درست/غلط بررسی شده باشند. receipt شامل manifest/config جداگانه و
visibility واقعی است. خطای جزئی مجوز مصرف تصویر یا replay خودکار نیست.
تصاویر محصول همچنان private-only هستند؛ این مسیر نه VPS را تغییر می‌دهد و
نه گیت پذیرش، staging، recovery یا defer باکت پارس‌پک را موفق فرض می‌کند.
مصرف این digestها در نصب فقط پس از بررسی مستقل امضا/attestation، admission
مثبت و منفی، صدور TLS و بررسی compatibility با Barman مجاز خواهد بود.

#### پذیرش مستقل تصاویر منتشرشده

`infrastructure/production/data/cert-manager-images.json` مجموعهٔ دقیق منتشرشده،
SHA-256 رسید و recipe را ثبت می‌کند؛ فایل source recipe تغییر نمی‌کند تا hash
provenance معتبر بماند. از داخل checkout پروژه، artifact عمومی همان run را در
پوشهٔ تازهٔ خارج از Git دریافت و پذیرش مستقل را اجرا کنید:

```bash
gh run download 38409447796 --name cert-manager-publication-38409447796-1 \
  --dir /tmp/hooshix-cert-manager-published-evidence
scripts/production/install_release_tools.sh /tmp/hooshix-cert-manager-verification-tools
export PATH=/tmp/hooshix-cert-manager-verification-tools:$PATH
python3 scripts/production/verify_published_cert_manager.py \
  --evidence-dir /tmp/hooshix-cert-manager-published-evidence \
  --output-dir /tmp/hooshix-cert-manager-published-acceptance
```

پوشه‌های evidence و acceptance باید تازه باشند. هیچ PAT، رمز sudo، کلید Root
یا رمز custody درخواست نمی‌شود. ابزار ابتدا hash رسید، کل سه تصویر، inventory
کامل و scan تازهٔ registry را بررسی می‌کند؛ سپس امضا، SLSA دقیق و CycloneDX
امضاشده را مستقیماً از registry تأیید می‌کند. signer غلط باید رد و signer درست
دوباره پذیرفته شود. TUF با trust پیش‌فرض داخلی Cosign و mirror رسمی Sigstore
در cache تازهٔ همان output استفاده می‌شود؛ cache یا trust میزبان تغییر نمی‌کند.
بعد از تأیید امضای کل مجموعه، فقط همان سه digest pull و با non-root، شبکهٔ
خاموش، filesystem فقط‌خواندنی و محدودیت منابع `--help` اجرا می‌شوند.
اسکن جاری هر بار از همان سه digest با ابزارهای مصوب و دیتابیس به‌روز تولید
می‌شود؛ scan پنج‌روز گذشتهٔ artifact دوباره به‌عنوان شواهد تازه پذیرفته نمی‌شود.
گزارش‌ها در `<output>/<component>/registry` و hash آن‌ها در
`scan_evidence_sha256` باقی می‌مانند؛ publication و attestation اصلی دست‌نخورده‌اند.

اجرای مستقل روی میزبان توسعه در `2026-10-13T14:36:06Z`، امضا/SLSA/SBOM،
رد signer غلط و smoke محدود هر سه digest را با **Passed** ثبت کرده است.
SHA-256 رسید عمومی `acceptance.json`:
`7855cf84fe2a8bbde40474ab872d91f0d83ed657b2b320f66768599b02dc8c8b`.
این شاهد همان bytes منتشرشده است، نه صدور TLS یا نصب مقصد.

خروجی موفق `CERT_MANAGER_PUBLISHED_ACCEPTANCE=Passed` و `acceptance.json` است؛
نه build، push، sign، نصب یا اتصال به VPS. این smoke، صدور گواهی، API readiness،
admission و staging را ثابت نمی‌کند. خطای جزئی receipt موفق نمی‌سازد؛ پوشهٔ
قبلی و شواهد موجود حذف نمی‌شوند و retry خودکار وجود ندارد. رسید/scan منقضی
یا مجموعهٔ ناقص را Passed فرض نکنید. نصب بعدی باید دقیقاً همین digestها را پس
از شواهد جداگانهٔ TLS/admission/staging مصرف کند، نه تصویر دوباره‌ساخته‌شده.

مرجع وابستگی: [نصب Barman 0.15.1](https://cloudnative-pg.io/plugin-barman-cloud/docs/installation/).

#### محدودهٔ commissioning گواهی داخلی دیتابیس

**مرز اعتماد تأییدشدهٔ مالک در ۲۰۲۶-۱۰-۱۴:** Kubernetes `1.35.6` درخواست تغییر
`MutatingWebhookConfiguration` و `ValidatingWebhookConfiguration` را از
admission webhookها معاف می‌کند. بنابراین policy وب‌هوکی Kyverno نمی‌تواند
تغییر `failurePolicy` این منابع را متوقف کند. محدودکردن RBAC cainjector به
دو config، دسترسی به configهای دیگر را می‌بندد، اما محدودیت فیلدی داخل همان
دو config ایجاد نمی‌کند. مالک این مرز اعتماد نصب استاندارد را پذیرفته است؛
در صورت نفوذ به cainjector، تغییر failurePolicy/service/rules همان دو config
ممکن است. ابزار در نصب و اجرای مجدد، پیکربندی HTTPS/Fail را بررسی می‌کند؛
این بررسی محافظت پیوسته در برابر نفوذ نیست. آزمون واقعی RBAC باید تغییر
configهای دیگر و تغییر همین configها توسط controller/webhook را رد کند.
برای نام دیگر، `kubectl auth can-i` با هویت cainjector باید `no` برگرداند؛
برای config موجود، PATCH آزمایشی با هویت controller/webhook باید Forbidden شود.
بستهٔ هدف همچنان به receipt تازه و موفق تمام آزمون‌های native وابسته است.
مرجع: [رفتار API سرور نسخهٔ دقیق](https://github.com/kubernetes/kubernetes/blob/v1.35.6/staging/src/k8s.io/apiserver/pkg/admission/plugin/webhook/generic/webhook.go).

ابزار cert-manager فقط برای TLS داخلی control plane دیتابیس در
`cnpg-system` آماده می‌شود؛ صدور TLS عمومی `hooshix.com`، ACME، ClusterIssuer
و گواهی برنامه‌ها خارج از این محدوده‌اند. با تأیید مالک، فقط Pod وب‌هوک
cert-manager می‌تواند خارج از Ambient باشد؛ controller و cainjector داخل
Ambient می‌مانند. callback باید HTTPS معتبر، `failurePolicy=Fail` و ingress
محدود به IPهای دقیق کشف‌شدهٔ API سرور داشته باشد. این تأیید، شاهد اجرای نصب
نیست: نصب هر سرور به receipt تازه و موفق native و نتیجهٔ مستقل همان مقصد
نیاز دارد؛ رسید اجراشدهٔ این VPS در انتهای همین بخش ثبت شده است.
Root/CA موجود، OpenBao، ESO و storage نباید در این عملیات تغییر کنند.

`render_cert_manager_candidate.py` فقط manifest رسمی hash-bound نسخهٔ `1.20.3`
و سه digest منتشرشده را مصرف می‌کند. سه CRD namespaced مربوط به Certificate،
CertificateRequest و Issuer آماده می‌شوند؛ ClusterIssuer و ACME/solver فعال
نمی‌شوند. Roleهای certificate در `cnpg-system` باقی می‌مانند. cainjector فقط
metadata وب‌هوک‌ها را در سطح کلاستر می‌خواند و فقط تنظیمات `cert-manager-webhook`
را می‌تواند تغییر دهد؛ گسترش به وب‌هوک Barman بعداً به نام‌های دقیق محدود می‌شود.
این CA داخلی وب‌هوک، Root پروژه یا intermediate مش نیست.

تأیید CertificateRequest طبق API رسمی از طریق منبع مجازی `signers` در سطح
کلاستر بررسی می‌شود. فقط حساب controller مجوز `approve` برای دو Issuer دقیق
`cnpg-system/hooshix-database-selfsigned` و `cnpg-system/hooshix-database-ca` دارد؛
این ClusterRole دسترسی به Secret یا Issuerهای namespace دیگر نمی‌دهد.
callback معتبر و fail-closed شامل `certificaterequests/status` هم هست تا
تغییر وضعیت Approved/Denied از بررسی مجوز عبور کند. Issuerهای پایدار و
گواهی‌های Barman در مرحلهٔ نصب دیتابیس ساخته خواهند شد، نه این نصب ابزار.
namespaceSelector داخل callback فقط `cnpg-system` است. پیکربندی مطلوب service،
rules، selector و Fail در commissioning بررسی می‌شود؛ این محدودیت فیلدی RBAC
یا policy محافظ تغییر webhook configuration نیست.

فقط یک Secret **خالی** و دارای مالکیت مشخص برای مقصد CA وب‌هوک ساخته می‌شود؛
هیچ کلید یا رمز در plan نیست. وب‌هوک خودش کلید را داخل Kubernetes تولید/تعویض
می‌کند و فقط مجوز update/read/watch همان نام را دارد، نه create Secret.
installer در اجرای مجدد فقط metadata مالکیت را می‌خواند و دادهٔ خصوصی موجود
را بازنویسی نمی‌کند. دسترسی گزارش‌گیری Kyverno فقط read-only برای Issuerها
است؛ دسترسی به Secretهای کلاستر اضافه نمی‌شود.

پس از ثبت CRDهای جدید، دو controller موجود admission/reports در Kyverno با
همان digestهای pin‌شده rollout محدود می‌شوند تا کش discovery نوع‌های جدید را
ببیند. UID و Pod spec باید ثابت بمانند؛ policyها حذف یا fail-open نمی‌شوند.
اجرای مجدد با CRDهای موجود این refresh را تکرار نمی‌کند.

بعد از **Passed** شدن آزمون native کل بسته روی commit نهایی `main`، بستهٔ عمومی
خارج از Git ساخته می‌شود. دستور زیر فقط قالب راهنماست؛ شناسهٔ run را از اجرای
موفق همان tree دریافت کنید و تا قبل از وجود آن اجرا نکنید:

```bash
gh workflow run platform-commissioning.yml --ref main -f mesh_security_upgrade=true \
  -f eso_commissioning=true -f cert_manager_commissioning=true
# Wait for the exact reviewed main's successful native receipt before building.
python3 scripts/production/build_cert_manager_commissioning_bundle.py \
  --staging-run <successful-platform-staging-run-id> \
  --manifest <verified-official-cert-manager.yaml> \
  --evidence-dir <verified-public-cert-manager-publication-directory> \
  --output <new-outside-git-public-bundle-directory>
```

builder به `main` تمیز، Git tree یکسان با CI، رسید تازهٔ حداکثر پنج‌روزه،
امضای واقعی سه digest، provenance/SBOM، callback HTTPS، آزمون signer/source غلط،
صدور/تعویض گواهی و hostname غلط، رد issuer خارجی و دسترسی namespace دیگر نیاز دارد.
نبودن شواهد مجوز تولید بسته نیست. کلیدهای آزمون فقط در کلاستر دورریختنی CI ساخته
می‌شوند؛ Root واقعی یا private keyها وارد CI نمی‌شوند. آزمون گواهی داخلی، اجرای
واقعی Barman یا backup پارس‌پک را Passed نمی‌کند.

بستهٔ معتبر را در Downloads ویندوز نگه دارید. ابتدا wrapper همان بسته را با
`-ValidateOnly` اجرا کنید. برای نصب، VNC نجات و نشست دوم SSH باز بمانند، سپس:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-cert-manager-commissioning.ps1' -RescueAndSecondSessionReady
```

فقط رمز sudo را در پنجرهٔ محلی وارد کنید؛ PAT، رمز Root یا custody لازم نیست.
ابزار hash فایل‌ها، audit محافظت‌شده و Metadata-only API audit، encryption
اسرار K3s، مالکیت/عدم drift مقصد و حالت OpenBao/mesh/ESO را بررسی می‌کند.
IPهای API روی خود مقصد کشف می‌شوند؛ address placeholder داخل plan مجوز شبکه نیست.
Namespace محدود، hardening، امضا/attestation و default-deny پیش از Podها اعمال
می‌شوند. هیچ public route، دسترسی دائم جدید، تغییر SSH/ایمیل یا allocation دیتابیس
ایجاد نمی‌شود. رسید عمومی موفق باید `CERT_MANAGER_INSTALLATION=Passed`،
`api_callback_tls=Passed`، `external_issuer_denied=Passed` و حفظ اجزای قبلی را
ثبت کند؛ این هنوز مجوز ترافیک محصول نیست.

در خطای جزئی، state و فایل عمومی حفظ می‌شوند؛ retry، حذف CRD/Secret، rollback
خودکار یا ساخت مجدد کلید انجام نمی‌شود. رسید بدون نتیجه یا نصب جزئی را Passed
فرض نکنید. TLS و تغییر namespace در سرور دوم باید دوباره با مقصد همان سرور
آزموده شوند؛ رسید VPS اول مجوز استفادهٔ کورکورانه روی سرور دیگر نیست.

#### نتیجهٔ نصب فعلی

نصب owner-local روی VPS در ۲۰۲۶-۱۰-۱۴ با `CERT_MANAGER_INSTALLATION=Passed`
ثبت شد. رسید عمومی `hooshix-cert-manager-d938a90a2e0742418392e05a79b7040b.json`
در همان پوشهٔ Downloads بسته باقی می‌ماند؛ SHA-256 آن:
`ac1bebc8be527d9f6f4695f49714ac4e645f6f7aa3e0b95f8c77b2a0c3b3fee7`.
این رسید به plan با hash
`8d45e8d84cfa976b44d3d3bf31594c8872517503cd85da05c133d372343236d1`
و سه digest دقیق بستهٔ `main@f43b46dc3020dc291e274a878e4fe915a7bc5881`
متصل است. HTTPS callback، رد Issuer خارجی و پیکربندی callback، **Passed**؛
Root/mesh/OpenBao/ESO/storage، **Preserved**؛ دسترسی عمومی، **CLOSED** است.
آزمون native همان tree در run `38511372244` شامل صدور/تعویض گواهی، hostname
غلط، RBAC، شبکه و source/signature/SBOM **Passed** است. این با صدور گواهی
کاربردی روی VPS فرق دارد: `certificate_issuance` مقصد، Barman/PITR و readiness
کل تولید همچنان **Not verified** هستند. نصب cert-manager را دوباره اجرا نکنید؛
قدم بعدی آماده‌سازی CNPG/PostgreSQL/Barman طبق پیش‌نیازهای زیر است.

### مرز کنترل‌پلین CNPG پیش از ساخت دیتابیس

مالک فقط برای وب‌هوک `cnpg-system/cnpg-controller-manager` در ۲۰۲۶-۱۰-۱۴
استثنای پورت داخلی `9443` را تأیید کرده است: Pod داخل Ambient می‌ماند و حالت
پیش‌فرض STRICT است؛ فقط mesh-mTLS همین پورت می‌تواند PERMISSIVE باشد تا API
سرورِ خارج مش وصل شود. HTTPS با گواهی/hostname معتبر، `failurePolicy=Fail` و
NetworkPolicy فقط از IPهای دقیق کشف‌شدهٔ API الزامی‌اند. پورت عمومی، خروج
کل Pod از Ambient، DISABLE یا تغییر حالت پورت‌های دیگر مجاز نیست.
این تأیید هنوز شاهد نصب یا آزمون نیست؛ بستهٔ مقصد فقط پس از آزمون native
HTTPS، منبع نامجاز، RBAC، مش، امضا/source/SBOM و حفظ اجزای قبلی صادر می‌شود.
نصب controller به‌تنهایی نصب PostgreSQL/Barman یا تخصیص فضای داده نیست.

ابزار `render_cnpg_control_plane.py` فقط manifest رسمی hash-bound CNPG `1.30.1`
و همان تصویر منتشرشدهٔ امضاشده را مصرف می‌کند؛ نسخه یا تصویر تازه نمی‌سازد.
`WATCH_NAMESPACE=platform-data` و Role داده در همان namespace است؛ دسترسی
Secretهای برنامه، Root یا namespaceهای دیگر داده نمی‌شود. خود controller، CA
مستقل وب‌هوکش را در namespace کنترل‌پلین تولید می‌کند؛ مجوز CREATE Secret در
RBAC محدودیت نام ندارد، اما خواندن/ویرایش Secret موجود به `cnpg-ca-secret`،
`cnpg-webhook-cert` و نام configuration خودش محدود است. دسترسی read-only به
Deployment/Service/EndpointSlice همان namespace فقط برای مالکیت CA و کشف plugin است.
فقط دو تنظیم وب‌هوک CNPG قابل patch هستند. این کنترل‌پلین مورداعتماد است، نه
حساب runtime برنامه. پیش از هر write، state خارجی یا تغییرکرده متوقف می‌شود؛
اجرای مجدد state خود ابزار را نگه می‌دارد و هیچ حذف/rollback خودکاری ندارد.
drift اضافی selector/پورت شبکه، mesh، شرط‌های حذف admission یا privilege حتی با
label مالکیت صحیح پذیرفته نمی‌شود؛ Pod زنده هم با plan محدود تطبیق داده می‌شود.
CA/Secret وب‌هوکِ ازقبل‌موجود فقط با ownerReference همان Deployment خود ابزار
پذیرفته می‌شود؛ این بررسی metadata-only است و کلید موجود خوانده یا adopt نمی‌شود.

namespace خالی `platform-data` با restricted/Ambient، NetworkPolicy پیش‌فرض بسته
و policy رد همهٔ Podها آماده می‌شود. ImageCatalog فقط metadata عمومی digest
پیشنهادی PostgreSQL است. هیچ Cluster، DB، PVC، credential یا اتصال عمومی ساخته
نمی‌شود؛ policy بستهٔ داده تا commissioning کامل storage/secret/data حذف نمی‌شود.

تولید plan عمومی، بدون اجرای مقصد:

```bash
python3 scripts/production/render_cnpg_control_plane.py \
  --manifest <verified-cnpg-1.30.1.yaml> \
  --cert-manager-manifest <verified-cert-manager-1.20.3.yaml> \
  --api-address <exact-api-ip>
```

برای ساخت رسید تازه، workflow همین گام را روی `main` بررسی‌شده اجرا کنید؛
دستور فقط CI دورریختنی است و به VPS وصل نمی‌شود:

```bash
gh workflow run platform-commissioning.yml --ref main \
  -f mesh_security_upgrade=true -f eso_commissioning=true \
  -f cert_manager_commissioning=true -f cnpg_control_plane=true
```

بعد از موفقیت native CI همان tree و merge، از `main` تمیز بستهٔ خارج Git بسازید:

```bash
python3 scripts/production/build_cnpg_control_plane_bundle.py \
  --staging-run <successful-cnpg-platform-run> \
  --manifest <verified-cnpg-1.30.1.yaml> \
  --cert-manager-manifest <verified-cert-manager-1.20.3.yaml> \
  --evidence-dir <verified-postgresql-publication-directory> \
  --output <new-public-bundle-outside-git>
```

این قالب تا وجود رسید موفق قابل استفادهٔ نصب نیست. builder امضا/attestation و
مجموعهٔ کامل تصاویر را مستقل می‌سنجد. wrapper بسته را نخست با `-ValidateOnly`
اعتبارسنجی کنید؛ با VNC نجات و نشست دوم SSH باز، نصب owner-local چنین است:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-cnpg-control-plane.ps1' -RescueAndSecondSessionReady
```

فقط رمز sudo همان‌جا لازم است. رسید موفق `CNPG_CONTROL_PLANE=Passed` شامل
HTTPS callback، پیکربندی پورت و حفظ mesh/OpenBao/ESO/cert-manager/storage است؛
`database=Not run`، `barman_runtime=Not run` و `public_access=CLOSED` می‌مانند.
در این نصب، بستهٔ `main@22054cdcf3aa9681474ebe02fb47633792c3a6c5` با آزمون
native موفق `38548698802` و validation ویندوز ساخته و روی VPS اجرا شده است.
رسید `hooshix-cnpg-bd35b5199b3842999b0432d8980b4855.json` با SHA256
`530c0c57bf246438b217b7d5d070858f6fc5fc962fc978e04368eead1ca1d5d6`
و plan با SHA256
`d5faea9362152ce11cd7905ea947be81a41ffa03266051858d2976eff67eb640`
تطبیق دارد: controller و HTTPS callback و مرز پورت، **Passed**؛ اجزای قبلی،
**Preserved**؛ دسترسی عمومی، **CLOSED**. این نتیجه فقط برای همین مقصد/بسته است؛
روی سرور دوم باید رسید مستقل همان اجرا گرفته شود. دیتابیس، فضای مستقل داده
و Barman هنوز **Not run** هستند؛ مرحلهٔ بعد تعیین اندازه و guard مستقل
storage PostgreSQL است، نه اجرای دوبارهٔ کنترل‌پلین.
بعد از افزودن controller، بستهٔ قدیمی cert-manager را دوباره اجرا نکنید؛ آن
بسته عمداً namespace دارای workload جدید را به‌عنوان scope قدیمی نمی‌پذیرد.

### اطلاعات مقصد پیش از تخصیص storage دیتابیس

برای انتخاب اندازهٔ فضای مستقل PostgreSQL، از دستگاه اپراتور در PowerShell
این فرمان‌های فقط‌خواندنی را اجرا کنید؛ فقط آخری رمز sudo را همان‌جا می‌خواهد:

```powershell
ssh.exe hooshix-server 'free -m'
ssh.exe hooshix-server 'df -BM /'
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl --request-timeout=15s get nodes,pv,pvc,storageclasses --all-namespaces -o wide'
```

خروجی capacity/storage عمومی را برای انتخاب اندازه و binding مقصد نگه دارید؛
این فرمان‌ها allocation، تغییر فایل یا restart ندارند. PV هشت‌گیگابایتی OpenBao
به PostgreSQL تعلق ندارد و نباید reuse یا جابه‌جا شود. فضای خالی اعلام‌شده فقط
مبنای برنامه‌ریزی است، نه شواهد complete-stack headroom، I/O، backup یا DR.
تا اندازه و guard مستقلِ دادهٔ مقصد مشخص نیست، دستور install/apply دیتابیس نزنید.
snapshot فقط‌خواندنی مقصد در ۲۰۲۶-۱۰-۱۳: RAM برابر ۱۵۹۸۶ MiB، available برابر
۱۱۹۴۲ MiB، فضای آزاد `/` برابر ۱۲۰۵۷۵ MiB و node `hooshix-production-1`، Ready
است. تنها PV/PVC موجود مربوط به OpenBao و Bound است؛ storage class دیگری برای
PostgreSQL وجود ندارد. این اعداد انتخاب فضای مستقل را راهنمایی می‌کنند، نه
تأیید ظرفیت بار نهایی محصول یا اندازهٔ ثابت برای سرور دیگر.

### فضای مستقل ۳۲ GiB برای PostgreSQL

مالک اندازهٔ اولیهٔ این مقصد را **۳۲ GiB** انتخاب کرده است. این مقدار ظرفیت
خام فایل backing است؛ ظرفیت ext4 قابل استفاده به‌علت metadata و reserve کمتر
است و PV آینده نباید ظرفیت قابل استفادهٔ اندازه‌گیری‌شده را بیش‌برآورد کند.
این انتخاب، شواهد I/O یا complete-stack capacity نیست. مسیرهای این گام:

| جزء | مسیر/مشخصات |
| --- | --- |
| backing مستقل | `/var/lib/hooshixstorage/postgresql.ext4`، root-only، کاملاً preallocate |
| mount محدود | `/var/lib/hooshixstorage/postgresql`، ext4، `nosuid,nodev,noexec,noatime` |
| دادهٔ آینده | `postgresql/data`، UID/GID ثابت `26:26`، خصوصی؛ هنگام unmount روی root ساخته نمی‌شود |
| guard | `hooshix-postgresql-storage-guard.service`، هویت inode/device/loop/UUID/flags/owner |
| ترتیب K3s | drop-in مستقل `32-hooshix-postgresql-storage.conf`؛ dependency قبلی OpenBao باقی می‌ماند |

بسته فقط پس از native CI همان tree ساخته می‌شود. CI با filesystem دورریختنی
۶۴ MiB و UID 26، ENOSPC، remount/persistence، guard، fault، عدم fallback و
پاک‌سازی همان fixture را می‌آزماید؛ روی VPS fault، reboot یا restart انجام
نمی‌شود. موتور مشترک در namespace جدا برای PostgreSQL بارگذاری می‌شود؛ defaults
OpenBao همچنان ۸ GiB و UID 10001 هستند. فایل guard نصب‌شدهٔ OpenBao بازنویسی
نمی‌شود؛ verifier فقط نسخهٔ قبلی نصب‌شده و نسخهٔ جاری دقیقاً hash-bound را می‌پذیرد.

از `main` تمیز و بررسی‌شده، پس از موفقیت CI:

```bash
python3 scripts/production/build_postgresql_storage_bundle.py \
  --native-run <successful-repository-baseline-run> \
  --output <new-public-bundle-outside-git>
```

در Windows، نخست بسته را بدون اتصال VPS اعتبارسنجی کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-postgresql-storage.ps1' -ValidateOnly
```

با VNC نجات و نشست مستقل دوم SSH باز، همان فرمان را با
`-RescueAndSecondSessionReady` به‌جای `-ValidateOnly` اجرا کنید. فقط رمز sudo
همان‌جا لازم است. بسته به hostname فعلی `mail.hooshix.com` و نسخه‌های reviewed
ابزارهای موجود میزبان bind است؛ روی سرور دوم این binding باید جداگانه در تغییر
بررسی‌شده تنظیم شود. هیچ package جدیدی نصب نمی‌شود.

پیش از allocation، سرویس‌ها/guard و state عمومی OpenBao و namespace خالی داده
بررسی می‌شوند. پس از preallocate باید حداقل ۳۰٪ کل filesystem میزبان آزاد بماند.
فایل/state خارجی یا drift overwrite/format نمی‌شود؛ partial state حفظ می‌شود.
نتیجهٔ موفق `POSTGRESQL_STORAGE=Passed`، `storage_guard=Passed`،
`openbao_host_services=Preserved` و `k3s_restart_performed=false` است.
PV/PVC، PostgreSQL Cluster، password، Barman و backup در این گام **Not run**
می‌مانند. نصب storage روی همین VPS در ۲۰۲۶-۱۰-۱۴ **Passed** است؛ رسید
`hooshix-postgresql-storage-6e009bff12524585bddb9ab5fd27e4fd.json` با SHA256
`9d1c00c1fff319fd7d53906f7a76590348d3385a9ab6d5ff189d448bc7c89d94`
با بستهٔ `main@d35a31e3f80717b70309d4f9597ddeca6eef0d9d` تطبیق دارد.
backing برابر ۳۴۳۵۹۷۳۸۳۶۸ بایت، filesystem برابر ۳۳۵۰۱۷۵۷۴۴۰ بایت و available
برابر ۳۱۷۶۴۸۶۹۱۲۰ بایت است؛ UID برابر 26، guard **Passed** و restart برابر false
است. OpenBao و سرویس‌های میزبان **Preserved** هستند. این بسته را دوباره برای
allocation اجرا نکنید. گام Kubernetes باید ظرفیت امن کمتر از فضای قابل استفاده
را به‌صورت LocalPV با Retain رزرو کند؛ خود دیتابیس و Barman هنوز نصب نشده‌اند.

در خرابی آینده، guard جلوی شروع K3s بدون mount معتبر را می‌گیرد و K3s را متوقف
می‌کند؛ این معادل توقف همهٔ containerهای در حال اجرا نیست. re-arm خودکار ندارد.
با مسیر نجات، علت mount/UUID/backing/permissions را رفع کنید؛ فایل backing، data
یا state را حذف/فرمت نکنید و data را روی root نسازید.
نصاب عادی را به‌جای recovery اجرا نکنید؛ preflight آن K3s فعال می‌خواهد. فقط پس
از رفع علت و تأیید هویت storage، با sudo در پنجرهٔ محلی و مسیر نجات باز:

```bash
sudo systemctl start var-lib-hooshixstorage-postgresql.mount
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/postgresql-storage-guard.py --guard-check
# فقط اگر بررسی بالا exit=0 بود:
sudo systemctl reset-failed hooshix-postgresql-storage-guard.service
sudo systemctl start hooshix-postgresql-storage-guard.service
sudo systemctl start k3s.service
```

خطای guard مجوز bypass یا شروع اجباری K3s نیست. پس از توقف واقعی K3s، unseal
OpenBao عملیات جداگانهٔ تحت نظارت و custody قبلی است. recovery مقصد، reboot
persistence و fault مقصد **Not verified**
می‌مانند؛ مشکل باکت پارس‌پک نیز در این گام بررسی یا Passed تلقی نمی‌شود.

### رزرو LocalPV بدون ساخت دیتابیس

برای همان backing خام ۳۲ GiB، ظرفیت اعلامی LocalPV برابر **۲۹ GiB** است؛ این
ظرفیت از available ثبت‌شده کمتر است و ext4 metadata/reserve را ظرفیت برنامه
حساب نمی‌کند. filesystem دوباره تخصیص/فرمت نمی‌شود. منابع فقط این دو هستند:

- StorageClass غیردیفالت `hooshix-postgresql-local`، بدون provisioner خودکار،
  `WaitForFirstConsumer`، `Retain` و بدون expansion خودکار؛
- PV به نام `hooshix-postgresql-local-29gib` با مسیر data قبلی، nodeAffinity فقط
  `hooshix-production-1`، RWO و claimRef رزروشده برای `platform-data/postgresql-1`.

هیچ PVC، Cluster، Pod، password، backup یا Barman ساخته نمی‌شود. نام رزرو برای
instance اول Cluster آیندهٔ `postgresql` است؛ commissioning کامل داده باید
همین binding را مصرف و مستقل تأیید کند، نه PVC دیگر یا storage پیش‌فرض.
نصب PV هم به‌معنای Bound شدن PVC یا آماده‌شدن دیتابیس نیست.

نمایش manifest عمومی، بدون اتصال سرور:

```bash
python3 scripts/production/install_postgresql_local_volume.py
```

builder فقط از main تمیز و با native evidence تازهٔ همان tree اجرا می‌شود؛
baseline هم guard/filesystem قبلی و هم API واقعی رزرو، conflict، رد claim
نامجاز، mount روی node صحیح، persistence و Retain را در kind دورریختنی می‌آزماید:

```bash
python3 scripts/production/build_postgresql_local_volume_bundle.py \
  --native-run <successful-repository-baseline-run> \
  --output <new-public-bundle-outside-git>
```

در Windows، wrapper را نخست با `-ValidateOnly` بررسی کنید؛ سپس با VNC نجات و
نشست دوم مستقل SSH باز، فقط رمز sudo همان‌جا:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-postgresql-local-volume.ps1' -RescueAndSecondSessionReady
```

ابزار hash دقیق guard نصب‌شده، mount/هویت/UID، فضای قابل‌استفاده، dependency هر
دو guard، audit، node و namespace خالی را پیش از رزرو بررسی می‌کند. هر دو منبع
قبل از اولین write از نظر state خارجی/drift بررسی می‌شوند؛ overwrite، adopt،
force، delete یا retry خودکار وجود ندارد. در شکست جزئی، منابع/data نگه داشته
می‌شوند. نتیجهٔ موفق `POSTGRESQL_LOCAL_VOLUME=Passed` است؛ `pvc`، `database` و
`barman_runtime` هنوز **Not run** هستند. اجرای owner-local روی VPS در ۲۰۲۶-۱۰-۱۵
**Passed** است: بستهٔ `main@507b660ae87904f29a4f8459c6334d4003106be0` از
native baseline موفق `38720511900` ساخته شد. رسید
`hooshix-pg-local-volume-02a76c48b47744bab007cde39b39b4c9.json` با SHA256
`8f891154b75f4635fbe26932f8f7fc2d986f97b61d3ea62b2e11f96bf94bcd85`
به source با hash
`257e978c56896807c0724cf12a8207fe70022854a8445bc6fabdb8849fd34762`
متصل است. ظرفیت ۳۱۱۳۸۵۱۲۸۹۶ بایت و رزرو `platform-data/postgresql-1` تأیید
شد؛ guard **Passed**، سرویس‌های قبلی **Preserved** و دسترسی عمومی **CLOSED** است.
این نتیجه نصب PostgreSQL/Barman یا Bound شدن PVC نیست؛ گام بعدی Barman است.
PV به دیسک/ماشین دیگری قابل انتقال کورکورانه نیست؛ روی سرور
دوم binding و native/target evidence مستقل لازم است. پس از وجود داده، rollback
به معنای حذف PV/PVC یا پاک‌سازی filesystem نیست؛ recovery تحت نظارت انجام شود.

### نصب کنترل‌پلین داخلی Barman

#### اجرای CI مربوط به استقرار

گیت‌های runtime، امضا/provenance/SBOM و بازیابی حذف نمی‌شوند. برای تغییرات
کاملاً محدود و شناخته‌شدهٔ ابزار نصب، فقط کارهای واقعاً نامرتبط می‌توانند
`Not applicable` شوند؛ مسیر ناشناخته، تغییر مشترک/امنیتی/CI یا مقایسهٔ نامعتبر
باید اجرای کامل بخواهد. main و اجرای زمان‌بندی‌شده/دستی همچنان کامل هستند و
اسکن redacted کل tree/history حتی در PR محدود هم الزامی است. اجرای قدیمی همان
PR پس از push جدید می‌تواند لغو شود؛ نتیجهٔ لغوشده شاهد استقرار نیست.
پیش از push، اصلاحات همان واحد کاری را جمع کنید تا کلاستر موقت بی‌جهت
بارها بازسازی نشود. هیچ cache شامل کلیدها، دیتای fixture یا رسید موفق جایگزین
آزمون تازه نیست. ابزار ساخت بسته همچنان native evidence تازهٔ همان tree را می‌خواهد.
انتخاب مسیر در `scripts/ci/select_pr_checks.py` فقط برای allowlist محدود ابزارهای
Barman/CNPG/storage/cert-manager اعمال می‌شود. نام شش check محافظت‌شده حفظ
می‌شود؛ کار نامرتبط با پیام `Not applicable` ثبت می‌شود، نه موفقیت جعلی تست
اجرانشده. خطای انتخاب مسیر، نتیجهٔ ناقص یا skip غیرمنتظره baseline را رد می‌کند.
probeهای مستقل شبکه/TLS در fixture حداکثر دو‌تایی اجرا می‌شوند؛ تغییر policyها
ترتیبی باقی می‌ماند و همهٔ آزمون‌های مثبت/منفی و cleanup حفظ می‌شوند.

#### نصب مقصد

این گام بعد از cert-manager، CNPG و رزرو LocalPV انجام می‌شود؛ نه ساخت دیتابیس
و نه تماس با باکت. تأیید مالک برای تصویر ثابت `Barman 0.15.1` فقط شامل
`list/watch` سراسری ObjectStore و Role/RoleBinding غیرمحرمانه است. API مربوط
به Secretها برای هویت `plugin-barman-cloud` ممنوع است؛ نوشتن فقط در
`platform-data` و فقط Role/RoleBinding به نام `postgresql-barman-cloud` مجاز
است. `bind` و `escalate` اعطا نمی‌شوند. این انتخاب، metadata غیرمحرمانهٔ سایر
namespaceها را برای cache قابل مشاهده می‌کند؛ اجازهٔ خواندن مقدار Secret نیست.
CNPG با هویت جدا فقط `get` روی دو Secret گواهی داخلی Barman دارد. کلیدهای
همان دو گواهی برای خود Barman به صورت volume فقط‌خواندنی mount می‌شوند.

manifest رسمی و hash ثابت از `postgresql-artifacts.json`/baseline دریافت می‌شود؛
هیچ `latest`، build تازه یا Root تازه‌ای استفاده نکنید. از main تمیز:

```bash
gh workflow run platform-commissioning.yml --ref main \
  -f mesh_security_upgrade=true -f eso_commissioning=true \
  -f cert_manager_commissioning=true -f cnpg_control_plane=true \
  -f barman_control_plane=true
python3 scripts/production/build_barman_control_plane_bundle.py \
  --staging-run <successful-exact-tree-platform-run> \
  --manifest <hash-verified-barman-0.15.1.yaml> \
  --cnpg-manifest <hash-verified-cnpg-1.30.1.yaml> \
  --cert-manager-manifest <hash-verified-cert-manager-1.20.3.yaml> \
  --evidence-dir <authenticated-postgresql-publication-directory> \
  --output <new-public-bundle-outside-git>
```

builder امضا، SLSA، SBOM و scan دقیق مجموعهٔ قبلی و رسید native تازهٔ همان
tree را لازم دارد. staging باید علاوه بر rollout و اجرای مجدد، رد image signer/
provenance/SBOM غلط، Secret API و نوشتن namespace دیگر، نام Role غیرمجاز و
اعطای غیرمستقیم دسترسی Secret از طریق Role،
TLS با client certificate و hostname درست و رد client/hostname غلط و اتصال
خارج Ambient را ثابت کند. کلیدهای آزمایشی از fixture خارج نمی‌شوند.

در Windows ابتدا همان wrapper با `-ValidateOnly`؛ سپس با VNC نجات و نشست دوم
مستقل SSH باز، فقط رمز sudo در پنجرهٔ محلی:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-barman-control-plane.ps1' -RescueAndSecondSessionReady
```

گواهی‌های server/client داخلی leaf هستند؛ Root آفلاین و CA موجود تغییر
نمی‌کنند. Service فقط `ClusterIP`، شبکه deny-by-default و تمام پورت‌های Barman
`STRICT` هستند؛ فقط هویت CNPG به 9090 دسترسی دارد. نصب پیش از هر write، مالکیت
و drift منابع و metadata کلیدهای TLS را بررسی می‌کند؛ adopt/force/delete ندارد.
در شکست جزئی state را نگه دارید، receipt را بررسی کنید و کورکورانه تکرار نکنید.
حذف CRD/Secret/volume روش rollback نیست. نتیجهٔ مورد انتظار
`BARMAN_CONTROL_PLANE=Passed` است. اجرای مالک در ۲۰۲۶-۱۰-۱۵ **Passed**:
main برابر `b624b088f316fa8257b9777bb0fc2d84a081b839`، شاهد native
`38731557283` و plan با SHA256
`ffb4478bc5736326ad9752ca534075971bbb1d209e72f2060b815dd1260dfb41`.
receipt عمومی `hooshix-barman-236cb6299fe54850b24260aea63be35e.json`
با SHA256 `6a072732effc3c8bc4f229539aee6168c85726b0eab8995bdbbd92ee52a96b2a`
نصب controller، گواهی‌های داخلی و رد Secret API/نوشتن خارج namespace را تأیید
می‌کند. CA، mesh، OpenBao، ESO، CNPG، storage و سرویس‌های میزبان حفظ شده‌اند؛
دسترسی عمومی بسته و دیتابیس/sidecar/provider backup همچنان **Not run** هستند.

پس از این گام، ساخت دیتابیس/sidecar، credential، WAL و بک‌آپ/restore جداگانه
لازم است. bootstrap مجاز باید delegation دقیق sidecar را از قبل بسازد؛ Barman
بدون Secret API نمی‌تواند خودش حق خواندن Secret جدیدی را به Role اضافه کند.
این وابستگی مجوز اعطای دسترسی بیشتر نیست. مشکل فعلی پارس‌پک همچنان تعویق
ثبت‌شده و **Not verified** است، نه Passed و نه مجوز لغو Root یا صدور JIT ناامن.

### آماده‌سازی مالکیت دیتابیس‌های PostgreSQL

این بخش روش نهایی تولید جزء عمومی مالکیت CNPG است؛ **نصب PostgreSQL روی VPS
هنوز Not run است**. فقط پنج سرویس دارای دادهٔ relational متغیر دیتابیس دارند:
`authorization`، `conversation`، `identity`، `notification` و `web_bff`.
Compromised Password دیتابیس PostgreSQL نمی‌گیرد. یک cluster فیزیکی با یک instance
مطابق ADR-0042 باقی می‌ماند؛ دیتابیس، role و Flyway هر سرویس جداست.

از checkout بررسی‌شده اجرا کنید؛ خروجی عمومی است و credential تولید نمی‌کند:

```bash
python3 scripts/production/render_postgresql_ownership.py --candidate
python3 -m unittest discover -s scripts/production/tests -p 'test_postgresql_ownership.py'
python3 scripts/production/test_postgresql_isolation_native.py --disposable
```

فرمان سوم فقط fixture Docker با تصویر digest ثابت PostgreSQL 18.4، بدون شبکه و
پورت میزبان می‌سازد و پس از آزمون همان container/volume موقت را حذف می‌کند.
پیش از SQL، آمادگی TCP روی loopback داخل container بررسی می‌شود تا فقط سرور
نهایی آماده باشد، نه سرور موقتِ Unix-socket مرحلهٔ init؛ هیچ پورتی منتشر نمی‌شود.
خروجی مورد انتظار `native_database_isolation: Passed` است؛ ۴۰ اتصال سرویس دیگر
رد می‌شوند. نقش runtime به migration تبدیل نمی‌شود، DDL/TEMP ندارد و دادهٔ
tenant دیگر یا context باقی‌مانده پس از commit/rollback را نمی‌بیند. این نتیجه
شاهد reconcile اپراتور، migration واقعی برنامه، PITR یا ظرفیت سرور نیست.

خروجی renderer شامل ده DatabaseRole، پنج Database و پنج ConfigMap عمومی SQL در
`platform-data` برای cluster `postgresql` است. سیاست حذف DB/role صریحاً `retain`
است؛ SQL هیچ داده‌ای حذف نمی‌کند و owner یا role غیرمنتظره را adopt نمی‌کند.
ConfigMap SQL را خودکار اجرا نمی‌کند. دستور apply مستقل روی VPS نزنید؛ بستهٔ
کامل داده باید ابتدا operator/image امضاشده، storage/guard مستقل، TLS و
NetworkPolicy/mesh، budget منابع/connection و backup/restore مصوب را فراهم کند.

پیش‌نیاز اسرار: ده Secret مستقل نوع `kubernetes.io/basic-auth` در `platform-data`
با نام `<service>-db-runtime` و `<service>-db-migration` و کلیدهای username/password
لازم است. username برابر نام role تولیدشده است؛ password فقط در OpenBao و
projection مجاز می‌ماند. Secret برنامه در `platform-apps` projection جداست؛ مرز
namespace به‌خاطر این کار باز نمی‌شود و ESO موجود خودکار مجوز namespace جدید
نمی‌گیرد. مقدار واقعی را در چت، Git، argv، log یا Helm values نگذارید.

در commissioning کامل: نخست readiness نقش‌ها/credential، سپس دیتابیس و owner،
بعد SQL همان دیتابیس و آزمون مستقل اتصال/مجوز مقصد بررسی می‌شود. PUBLIC CONNECT
با ساخت دیتابیس خودبه‌خود حذف نمی‌شود. SQL عمومی/پیش‌فرض‌های ناامن را لغو می‌کند،
runtime را به DML دیتابیس خودش محدود می‌کند و جدول‌ها را به Flyway سرویس می‌سپارد.
CONNECT جدید با revoke بسته می‌شود؛ نشست موجود خودکار قطع نمی‌شود، پس cutover
مقصد جداگانه لازم است. migrationهای اجراشده تغییر نمی‌کنند.
جزئیات فنی و ترتیب dependencyها: [جزء PostgreSQL](../../deploy/postgresql/README.md).

### تحویل ده credential مستقل PostgreSQL از OpenBao

این پیش‌نیاز فقط اسرار را آماده می‌کند؛ نصب دیتابیس/PVC/sidecar، backup ارائه‌دهنده
و لغو root در آن **Not run** است. اجرای مالک روی VPS فعلی در ۲۰۲۶-۱۰-۱۵ **Passed**:
main برابر `916aec1db5e0def6f9b64702d27f3fb205142f78`، native برابر `38752407229`،
و plan با SHA256 `cfcb252397bc62dbbc4c1cb001d55db9d5bf691c371b1614ebddc28f5c1b52b6`.
رسید عمومی `delivery-770b89f18db64821a1116d2afa972714.json` با SHA256
`0503017a66a26a9c8fd82434a98f8c9edd835707f568a5780f67af7c58c9b0c9` تحویل ده
Secret، احراز هویت محدود و رد Secret/TokenRequest خارج محدوده را تأیید می‌کند.
Root، CA، mesh، OpenBao، ESO برنامه‌ها، CNPG، Barman و storage حفظ شده‌اند؛
دسترسی عمومی بسته است. این رسید مخصوص همین مقصد است؛ نصب دیگر رسید مستقل می‌خواهد.

یک controller با همان digest امضاشدهٔ ESO فقط `platform-data` را watch می‌کند.
cache این controller به Secretهای همین namespace دسترسی دارد؛ هیچ namespace دیگر
یا Secret کلاستر را نمی‌خواند. ده حساب `eso-pg-<database>-<runtime|migration>`، هرکدام
فقط leaf خودش در `production/postgresql/<database>/<mode>` را می‌خوانند. توکن
OpenBao حداکثر پنج دقیقه است و فقط lookup/revoke خودش را دارد. TokenRequest
کنترلر فقط برای همین ده حساب مجاز است؛ JWT API خودش projected و ده‌دقیقه‌ای است.
همهٔ Podها Ambient/STRICT هستند. وب‌هوک HTTPS/Fail قبلی، بدون تغییر CA یا Pod،
فقط همین namespace و `platform-apps` را اعتبارسنجی می‌کند؛ وب‌هوک جدید نداریم.
مرز admission داده فقط همین controller امضاشده را می‌پذیرد و Pod دیتابیس بسته می‌ماند.

از checkout تمیز روی **main دقیق فعلی**، پس از موفقیت native همان tree، بستهٔ عمومی بسازید:

```bash
python3 scripts/production/build_postgresql_secrets_bundle.py \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-postgresql-secrets-<date> \
  --staging-run <successful-platform-commissioning-run-id> \
  --chart <verified-external-secrets-2.12.0.tgz> \
  --helm <verified-helm-4.2.4>
python3 scripts/production/bootstrap_postgresql_secrets_operator.py \
  --bundle /mnt/c/Users/Coder/Downloads/HooshiX-postgresql-secrets-<date> --validate-only
```

builder علاوه بر شواهد قبلی، تحویل ده Secret، عدم تغییر در اجرای مجدد، آزمون‌های
رد cross-identity/namespace/audience/write/admin/revoked-token، امضا/provenance/SBOM،
بسته‌ماندن دیتابیس، حفظ کنترلرهای قبلی و عدم نشت در log را الزام می‌کند.
مسیر خروجی باید تازه و بیرون Git باشد؛ chart/tool با hashهای catalog فعلی تطبیق دارند.
اعتبارسنجی محلی به VPS یا custody وصل نمی‌شود.

با VNC قابل ورود و نشست دوم SSH باز، در PowerShell اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_postgresql_secrets_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-postgresql-secrets-<date> --custody /home/coder/.local/share/hooshix-openbao-custody/<existing-custody-id> --rescue-and-second-session-ready
```

فقط `READY`، **رمز موجود custody مربوط به OpenBao** و رمز sudo در ورودی‌های مخفی
لازم‌اند؛ رمز Root/CA یا GHCR لازم نیست. رمز یا فایل خصوصی را نفرستید. رمزها فقط در
RAM مصرف می‌شوند. ده password مستقل تصادفی در OpenBao و projection مجاز ESO می‌مانند؛
هیچ password در bundle/رسید/Git/values/argv ذخیره نمی‌شود. Secretها basic-auth با
کلیدهای `username`/`password` و نام‌های بخش قبل هستند؛ username دقیقاً role همان سرویس است.

خروجی موفق `POSTGRESQL_SECRET_DELIVERY=Passed` و `PUBLIC_RECEIPT` زیر
`~/.local/share/hooshix-postgresql-secrets/` است. فقط رسید عمومی را برای ثبت نتیجه بدهید.
برای مشاهدهٔ وضعیت بدون مقدار Secret:

```powershell
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl -n platform-data get deployments,secretstores,externalsecrets'
```

اجرای مجدد همان bundle بررسی‌شده، credential موجود را نمی‌چرخاند و منابع خارجی را
adopt نمی‌کند. پس از شکست، حالت جزئی حفظ می‌شود؛ ابتدا کد عمومی شکست بررسی شود.
برای ادامه از همان custody و نسخهٔ بررسی‌شده استفاده کنید؛ leaf/Secret/PVC را پاک نکنید،
root جدید نسازید و re-init نزنید. تغییر credential یا نصب دیتابیس مرحله‌ای جداست؛ حذف
manifest، rollback password محسوب نمی‌شود. مشکل deferred پارس‌پک همچنان **Not verified**
است و در این عملیات نه probe می‌شود و نه موفق فرض می‌شود.

### PostgreSQL خالی و قرنطینه‌شده

مالک در ۲۰۲۶-۱۰-۱۶ فقط این حالت را تأیید کرده است؛ مجوز ورود دادهٔ واقعی یا
اتصال برنامه نیست. یک instance از همان تصویر امضاشده روی LocalPV موجود ۲۹ GiB
ساخته می‌شود. CNPG برای bootstrap، دیتابیس خالی `identity` و owner موجود
`identity_migration` را با ارجاع به Secret `identity-db-migration` می‌سازد.
همان `ImageCatalog` موجود `hooshix-postgresql-18-candidate` فقط digest ثابت PostgreSQL
و نسخهٔ major برابر ۱۸ را به CNPG معرفی می‌کند؛ catalog یا تصویر تازه، tag شناور
و registry credential جدید ساخته نمی‌شود. هیچ
جدول، Flyway، پنج دیتابیس برنامه یا نقش اضافی در این گام نصب نمی‌شود. رمز عوض
نمی‌شود و بسته رمز/توکن/کلید CA نمی‌خواهد. دسترسی عمومی و اتصال TCP برنامه‌ها
بسته است؛ `pg_hba` حتی اتصال SQL از loopback داخل Pod را رد می‌کند. فقط مدیریت
داخلی CNPG و مسیر peer محلیِ اپراتور، با احراز هویت مدیریتی موجود، باقی می‌ماند.

طبق تأیید مالک در ۲۰۲۶-۱۰-۱۶، یک Role و RoleBinding مستقل فقط `list/watch` روی
Secretهای `cnpg-system` را به `cnpg-system/cnpg-manager` می‌دهد تا cache داخلی آن
کار کند. این مجوز محتوای Secret و کلیدهای TLS داخلی همین namespace را هم می‌خواند،
نه فقط metadata؛ مجوز نوشتن تازه، دسترسی Secret در namespace دیگر یا ClusterRole
اضافه نمی‌شود. RBAC قبلی دست‌نخورده است. پیش از bootstrap و در اجرای مجدد، مجوز
خواندن محدود و رد خواندن namespace خارجی و نوشتن Secret خارج نام‌های قبلی آزموده می‌شود.
آزمون native فقط با credential مصنوعیِ fixture، نبود رمز خام یا base64 در logهای
initdb و PostgreSQL را هم بررسی می‌کند؛ Secret واقعی مقصد برای این آزمون خوانده نمی‌شود.
فرمان و probeهای instance، TLS داخلی status را طبق نسخهٔ ثابت CNPG حفظ می‌کنند؛
هیچ endpoint مدیریتی برای برنامه‌ها یا اینترنت باز نمی‌شود.
قرارداد Pod اجرای دلخواه از lifecycle/exec-probe و تغییر مسیر termination به فایل
Secret را هم رد می‌کند؛ فقط probeهای HTTPS داخلی ثابت مجازند.
در Job اولیه، `POD_NAME` نام ثابت Job است و تنها ارجاع env اضافی، کلید `username`
از Secret موجود `identity-db-migration` است؛ `password` یا Secret دیگری در env مجاز نیست.
کنترل‌های امضا، provenance و SBOM تصویر اصلی PostgreSQL و تصویر init مربوط به CNPG
را جداگانه و با digest دقیق همان جزء بررسی می‌کنند؛ تصویر جزء دیگر به verifier
آن سیاست فرستاده نمی‌شود. پذیرش هر دو Pod واقعی `initdb` و instance و رد signer،
provenance و SBOM نامعتبر پیش از ساخت بسته الزامی است.
آزمون حریم خصوصی، log اصلی `initdb` را فقط پس از شروع همان container جمع می‌کند؛
وجود Pod یا پایان `bootstrap-controller` به‌تنهایی شاهد شروع آن نیست. تست منفی
امضا/attestation نیز باید نام همان سیاستِ عمداً نامعتبر را در پاسخ ردشدن ببیند.
پیش‌نیاز CI، اسکن تازهٔ همان inventoryهای تأییدشدهٔ Istio/CNI/ESO با Grype ثابت و
دیتابیس معتبر حداکثر پنج‌روزه است. گزارش‌ها و hashها به‌صورت artifact عمومی نگه
داشته می‌شوند؛ digest، SBOM امضاشده، provenance و انتشار اصلی عوض نمی‌شوند.
برای ساخت bundleهای mesh/ESO نیز ابزار Grype ثابت باید در PATH باشد؛ اسکن تازه
مجوز rebuild، push، تغییر تصویر یا عبور از High/Critical نیست.
سقف کل آزمون یکپارچهٔ زیرساخت و قرنطینه در CI چهل دقیقه است؛ deadlineهای محدود
هر عملیات و الزام موفقیت همهٔ کنترل‌ها تغییر نمی‌کنند.

این مسیر در `render_postgresql_quarantine.py` تعریف می‌شود. پس از موفقیت آزمون
واقعی همان درخت Git و تأیید `main`، بستهٔ عمومی بیرون Git ساخته می‌شود:

```bash
python3 scripts/production/build_postgresql_quarantine_bundle.py \
  --staging-run <successful-exact-tree-platform-run> \
  --storage-run <successful-exact-tree-baseline-run> \
  --chart <checksum-verified-eso-2.12.0.tgz> --helm <pinned-helm> \
  --evidence-dir <verified-public-postgresql-publication> \
  --output <new-public-bundle-outside-git>
```

سازنده، امضا و provenance/SBOM و تازگی شواهد چهار تصویر قبلی، آزمون filesystem
و guard، bootstrap واقعی CNPG/Ambient، خالی‌بودن، TLS/رد SQL، رد Pod و امضای
نامعتبر، رد اتصال از Pod با مبدأ غیرمجاز و اجرای دوباره بدون تغییر UIDهای
Cluster/PVC/PV را لازم دارد. native
داخل kind، mount میزبان VPS محسوب نمی‌شود؛ guard روی مقصد جداگانه بررسی می‌شود.
روی سرور دوم، نام node و رزرو storage باید در تغییر بررسی‌شده به همان نصب
متصل شود؛ نام سرور اول را به مقصد دیگر apply نکنید.

در Windows ابتدا بدون اتصال به VPS:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-postgresql-quarantine.ps1' -ValidateOnly
```

با VNC قابل ورود و نشست دوم SSH باز، فقط رمز sudo را همان‌جا وارد کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '<public-bundle>\run-postgresql-quarantine.ps1' -RescueAndSecondSessionReady
```

خروجی مقصد `POSTGRESQL_QUARANTINE=Passed` و `PUBLIC_RECEIPT` است؛ تا دریافت و
تطبیق رسید، نصب مقصد **Not run/Not verified** باقی می‌ماند. `Passed` فقط یعنی
دیتابیس خالی، حجم Retain درست، TLS و مرز بسته و اجزای قبلی حفظ شده‌اند؛ backup،
بازیابی، ظرفیت و محصول Production را تأیید نمی‌کند. برای دیدن وضعیت بدون Secret:

```powershell
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl -n platform-data get clusters,pods,pvc'
```

پس از خطا داده و منابع حفظ می‌شوند. Cluster/PVC/PV، `pgdata` یا Secret را پاک
نکنید؛ re-init، فرمت، downgrade و rollback خودکار نداریم. پس از بررسی کد عمومی
خطا، فقط همان بسته و همان UIDها قابل ادامه‌اند. وجود جدول/دیتابیس غیرمنتظره،
claim خارجی یا تغییر spec باعث توقف می‌شود. خروج از قرنطینه و اتصال برنامه‌ها
یک تغییر مستقل بعد از backup/restore و isolation/migration معتبر است. مشکل
پارس‌پک در این مرحله probe یا موفق فرض نمی‌شود؛ root نیز لغو نمی‌شود.

#### نتیجهٔ نصب مقصد فعلی، ۲۰۲۶-۱۰-۱۷

نصب توسط مالک روی VPS فعلی **Passed** است؛ نصب را تکرار نکنید. بسته از
`main@cfa0fbaf21992211ebcffba00eb18dc2ff5c86cf` و شواهد native همان درخت
`38959679639` و storage baseline برابر `38959679947` ساخته شده است.
رسید عمومی `hooshix-pg-quarantine-eb528210c83d4920b70343dbf9eb95c6.json` در
پوشهٔ `C:\Users\Coder\Downloads\HooshiX-postgresql-quarantine-20261017` نگهداری
می‌شود؛ SHA256 آن:

```text
d8bf1e01f04cb95d85b3e2fe4a67da3fc94a60df2f69675a4a1ee6ef91f9c5d3
```

هش plan با بسته تطبیق دارد:
`d832b8d058dc204763d552f63685abfdd798603710997b52098ad750cdf42be4`.
تصاویر دقیق اپراتور و PostgreSQL نیز با plan یکسان‌اند. هویت منابع این نصب:

| منبع | UID ثبت‌شده |
| --- | --- |
| Cluster | `9e458d94-7677-4cda-935e-6c373be14da2` |
| PVC | `38be3266-dc70-4a96-9e5a-538b167a7b68` |
| PV | `c3c4ed30-a547-4bd1-a1af-468afbfba139` |

یک instance خالی و PVC متصل به حجم Retain موجود ساخته شده‌اند. خالی‌بودن،
TLS/رد SQL، guard، مرز ServiceAccount و cache محدود Secretهای CNPG همگی
**Passed** هستند؛ root، mesh، OpenBao، ESO، CNPG و Barman حفظ شده‌اند.
این UIDها شناسهٔ همین مقصدند؛ روی سرور دوم کپی نمی‌شوند. برای ادامهٔ نصب موجود،
رسید را نگه دارید و از ساخت مجدد Cluster/PVC یا فرمت volume خودداری کنید.

اتصال برنامه‌ها همچنان **CLOSED** است؛ migration، backup ارائه‌دهنده و لغو root
**Not run** و آمادگی Production **Not verified** هستند. این نتیجه نه مجوز خروج
از قرنطینه است و نه موفقیت backup/PITR/restore. هیچ probe یا upload دوباره‌ای
برای اختلال deferred پارس‌پک در این گام انجام نشده است.

### آماده‌سازی Redis امن، بدون نصب مقصد

این جزء متعلق به Platform است و همان Redis **8.2.8** مصوب را نگه می‌دارد؛
هیچ Redis، volume، Secret یا پورت جدیدی روی VPS ایجاد نمی‌کند. ورودی رسمی
Alpine با digest ثابت، runtime پایهٔ Alpine **3.24.2** و بسته‌های ثابت OpenSSL،
zlib و GCC runtime در `infrastructure/production/redis/candidate.json` و
`Dockerfile` ثبت شده‌اند. فقط binaryهای همان Redis منتقل می‌شوند؛ سورس Redis
دوباره کامپایل نمی‌شود. نوع provenance برابر `patched-upstream-runtime-build`
است، نه unchanged import یا گواهی build رسمی upstream. آرشیو سورس رسمیِ
checksum-bound و متن مجوز همراه تصویر باقی می‌مانند.

از checkout بررسی‌شده، با Docker محلی Unix، کاربر غیر root، OpenSSL و ابزارهای
checksum-bound موجود Syft **1.51.0** و Grype **0.117.0**:

```bash
python3 -m unittest discover -s scripts/production/tests -p 'test_redis*.py'
PATH=/path/outside-repo/release-tools:$PATH python3 scripts/production/build_redis_candidate.py \
  --directory /path/outside-repo/new-redis-candidate
```

پوشهٔ خروجی باید تازه و بیرون Git باشد. builder فقط ورودی‌های ثابت را می‌سازد؛
inventory کامل و بدون exclusion، CycloneDX و scan تازه با دیتابیس معتبر حداکثر
پنج‌روزه لازم‌اند. هر High/Critical، حتی suppressed، عملیات را رد می‌کند و
رسید موفق ساخته نمی‌شود. credential رجیستری، sudo، Root یا custody لازم نیست.
workflow مستقل `Redis security candidate` همین مسیر را بدون مجوز انتشار اجرا
می‌کند و فقط JSON عمومی و Dockerfile را نگه می‌دارد، نه fixture یا رمزها را.

پیکربندی نهاییِ قابل استفاده در مرحلهٔ نصب از `render_redis_security.py` می‌آید:

- فقط TLS روی ۶۳۷۹؛ `port 0` مسیر plaintext را می‌بندد. رمز مستقل ACL لازم است؛
  TLS client-certificate در این لایه الزامی نیست و جایگزین Ambient/STRICT آینده نیست.
- default user غیرفعال است. `authorization` فقط `authorization:admin-quota:v1:*`،
  `identity` فقط `identity:quota:v1:*` و `webbff` فقط `web-bff:*` را دارند.
  دستورهای لازم quota/session صریح‌اند؛ admin، enumeration و دسترسی cross-owner
  مجاز نیست. حساب health فقط PING و monitor فقط INFOهای محدود دارد و هیچ کلیدی
  را نمی‌خواند. password hash نیز Secret است و نباید در Git، argv یا خروجی باشد.
- `noeviction`، AOF با `appendfsync everysec` و توقف روی AOF ناقص حفظ می‌شوند.
  سقف حافظه ورودی اجباری است، نه مقدار تأییدشدهٔ VPS؛ منابع و headroom نصب واقعی
  باید جداگانه اندازه‌گذاری و آزموده شوند.

native روی همان local image ساخته‌شده، بدون شبکه یا پورت منتشرشده، با filesystem
فقط‌خواندنی، کاربر غیر root و سقف CPU/RAM اجرا می‌شود. کلیدها، گواهی یک‌روزه و
داده‌ها فقط fixture مصنوعی در `/dev/shm` هستند و پاک می‌شوند. TLS مثبت، رد
plaintext/CA نامعتبر، دسترسی خودی و رد GET/SET/Lua بین سرویس‌ها، محدودیت Lua
روی کلید پویا، رد admin/enumeration، OOM بدون eviction، ماندن داده پس از restart
AOF و عدم نشت رمز/hash در log باید **Passed** باشند. این آزمون بازیابی پس از
خرابی دیسک/host یا آزمون کامل Lettuce و برنامه‌ها نیست.

فقط پس از همهٔ کنترل‌ها، `candidate.json` با `scan=Passed` و نتیجهٔ native
ساخته می‌شود. local image ID، configuration digest، hash دستور ساخت و hash
شواهد جداگانه ثبت می‌شوند؛ این شناسه‌ها با digest رجیستری یکی فرض نمی‌شوند.
این آزمون محلی به‌تنهایی امضا/انتشار، admission و Ambient، storage پایدار، تحویل
OpenBao/ESO، گواهی معتبر مقصد، سازگاری TLS برنامه‌ها، ظرفیت یا نصب Redis روی VPS
را تأیید نمی‌کند. فایل config یا تصویر candidate را مستقیم روی VPS اجرا نکنید؛ هیچ gate
Production یا تعویق پارس‌پک با این نتیجه عبور نکرده است.

مراجع رسمی: [Redis TLS](https://redis.io/docs/latest/operate/oss_and_stack/management/security/encryption/)
و [Redis ACL](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/).

### انتشار candidate امضاشدهٔ Redis

مالک انتشار public یا private را فقط برای همین تصویر زیرساخت Redis تأیید کرده
است؛ این استثنا شامل کد، اسرار یا تصاویر محصول HooshiX نیست.

انتشار Redis گام بعد از آزمون محلی است. ابزار
`scripts/production/publish_redis_candidate.py` فقط در dispatch حفاظت‌شدهٔ
`production-release.yml` روی `main` و با baseline موفق همان SHA اجرا می‌شود.
مقصد ثابت `ghcr.io/hasanjodatshandi/hooshix/platform-security-redis-v2-private` است؛
فقط `private` یا `public` برای همین مقصد مصوب پذیرفته‌اند؛ `internal` یا وضعیت
نامعتبر عملیات را رد می‌کند. نام یا پسوند package گواه visibility نیست.
ابزار visibility را تغییر
نمی‌دهد. فقط 404 پیش از اولین push می‌تواند نبود package باشد؛ 401/403 خطاست.
مقصدهای دیگر، از جمله نام بدون `v2`، adopt نمی‌شوند. package عمومی در GitHub
قابل برگشت به private نیست؛ آن را حذف نکنید. این پذیرش محدود، مجوز تغییر
visibility هیچ package دیگر یا مخزن محصول نیست.
پیام `REDIS_PACKAGE_VISIBILITY` فقط وضعیت محدود `internal` یا
`invalid` را برای تشخیص رد کنترل نشان می‌دهد، نه token یا پاسخ خام. پس از رد،
همان run را بدون اصلاح علت تکرار نکنید؛ هیچ امضای موفقی از این نتیجه استنباط نمی‌شود.

از checkout آنلاین با GitHub CLI احرازشده، پس از merge و موفقیت baseline همان main:

```bash
gh workflow run production-release.yml --ref main -f release_kind=redis-candidate
gh run list --workflow production-release.yml --event workflow_dispatch --limit 5
```

run همین dispatch را با SHA و زمان آن تطبیق دهید و تا پایان بررسی کنید؛ اجرای
CLI ناشر روی لپ‌تاپ جایگزین workflow نیست. هیچ PAT، sudo، custody یا رمز CA در
ورودی این مرحله لازم نیست: CI فقط token موقت package و OIDC موجود خود را دارد
و احراز هویت موقت رجیستری در پایان پاک می‌شود. منطق امضا همان helper موجود
انتشار PostgreSQL است، نه signer یا کلید تازه.

workflow یک بار candidate را می‌سازد و native TLS/ACL/Lua/noeviction/AOF را روی
همان local image تکرار می‌کند، سپس همان تصویر را push می‌کند. configuration
digest رجیستری باید با تصویر آزموده‌شده یکسان باشد؛ manifest رجیستری جداگانه
resolve و با inventory کامل و scan تازه بررسی می‌شود. tag متغیر و local image ID
جای digest رجیستری نیستند. پس از این کنترل‌ها، همان `@sha256` امضا می‌شود و
SLSA v1 با نوع صادقانهٔ `patched-upstream-runtime-build` و CycloneDX attestation
می‌گیرد. signer دقیق workflow روی main، OIDC issuer، payload و subject digest
بررسی می‌شوند؛ رد signer نادرست و کنترل مثبتِ پس از آن الزامی‌اند.

فقط پایان موفق همهٔ مراحل و تأیید دوبارهٔ visibility مصوب، `publication.json` را
در artifact عمومی `redis-publication-<run_id>-<attempt>` می‌سازد. رسید شامل SHA
مخزن، invocation، visibility واقعی، digest رجیستری و config، scan، native و نتیجهٔ امضاست؛ آرشیو
سورس و fixtureهای رمزی در artifact نیستند. شکست جزئی ممکن است تصویر/attestation
در رجیستری باقی بگذارد، اما بدون رسید موفق مجوز استفاده نیست و خودکار تکرار
نمی‌شود. ابتدا run و artifact همان attempt بررسی شوند؛ چیزی روی VPS پاک یا
جایگزین نمی‌شود. رسید و شواهد عمومی را بیرون Git نگه دارید.

این مرحله نصب VPS، ساخت Secret، staging یا بازکردن اتصال برنامه‌ها نیست؛
`deployment`، `runtime_admission` و `staging` در رسید **Not run** باقی می‌مانند.
نام package یا موفقیت CI به‌تنهایی اثبات انتشار نیست؛ run موفق و رسید نهایی لازم‌اند.
هیچ تصویری پس از تأیید staging دوباره ساخته نمی‌شود و Production تا شواهد
مقصد، بازیابی و ظرفیت **Not verified** می‌ماند. تعویق پارس‌پک تغییری نمی‌کند.

مرجع: [Cosign signing and verification](https://github.com/sigstore/cosign).
تنظیم visibility مستقل package از مخزن:
[GitHub package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).

### فضای مستقل Redis

فضای AOF باید از OpenBao و PostgreSQL جدا و دارای guard فعال باشد؛ موفقیت انتشار
تصویر به معنی وجود volume، Secret یا Redis روی مقصد نیست. این مرحله هیچ برنامه‌ای
را به Redis متصل نمی‌کند و اندازهٔ دیسک جای آزمون ظرفیت حافظه را نمی‌گیرد.

تصویر منتشرشدهٔ این نصب در run `39109666003`، attempt `1`، از revision
`13613b929846726699580cf51c58ffa441637489` تولید و با امضا، provenance و SBOM
تأیید شده است؛ برای ادامه آن را دوباره نسازید:

```text
ghcr.io/hasanjodatshandi/hooshix/platform-security-redis-v2-private@sha256:b7013f0a95c584029fcfe89810f366060302a5d5bf130d02a5292a0fe6326c14
```

configuration digest آن
`sha256:cdc587b3dc307b153d4dc16c9631c88e26e8f363de150392696c8711c6bfd6e5`
و SHA-256 رسید `publication.json` برابر
`6da911a1043a42ffd64b9592017a0b614cf3f373baea3c55a675285736db27be` است؛
این دو شناسه جای manifest digest بالا نیستند. شواهد در
`redis-publication-39109666003-1` قرار دارند. بستهٔ storage تصویر را اجرا یا تغییر
نمی‌دهد و هنوز مجوز اجرای workload نیست.

بستهٔ storage از `provision_redis_storage.py` و موتور موجود
`provision_openbao_storage.py` استفاده می‌کند؛ globals و مسیرها مستقل‌اند.
پیش‌نیاز این مرحله وجود و سلامت **هر دو** فضای OpenBao و PostgreSQL، فعال‌بودن
SSH/WireGuard/auditd/K3s و هر دو guard، و باقی‌ماندن حداقل ۳۰٪ فضای root پس از
تخصیص است. PostgreSQL می‌تواند در حال کار باشد؛ namespace داده نباید خالی شود.
ابزار Secret یا محتوای دیتابیس موجود را نمی‌خواند.

پس از تأیید مالک برای اندازهٔ ثابت **۸ GiB** و موفقیت baseline دقیق درخت کد،
از checkout تمیز `main` و GitHub CLI احرازشده، بسته را بیرون پروژه بسازید:

```bash
python3 scripts/production/build_redis_storage_bundle.py \
  --native-run <successful-exact-tree-baseline-run> \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-redis-storage-YYYYMMDD
```

artifact `redis-storage-<run>-<attempt>` باید متعلق به baseline موفق همین مخزن،
همین درخت، حداکثر پنج روزه و شامل تمام نتایج native guard باشد؛ فایل ادعایی یا
رسید متعلق به profile دیگر پذیرفته نیست. بسته شامل کد عمومی، hash و رسید CI است،
نه رمز یا snapshot. در PowerShell ابتدا فقط صحت فایل‌ها را بررسی کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-storage-YYYYMMDD\run-redis-storage.ps1' -ValidateOnly
```

انتظار: `REDIS_STORAGE_BUNDLE=Validated; no remote execution`.
با VNC قابل ورود و نشست دوم private SSH باز، فقط پس از تأیید اندازه اجرا کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-storage-YYYYMMDD\run-redis-storage.ps1' -RescueAndSecondSessionReady
```

`READY` را با صفحه‌کلید انگلیسی وارد کنید و رمز sudo را فقط در همان پنجره بدهید؛
token، رمز custody یا CA لازم نیست. `-ValidateOnly` هرگز sudo/SSH اجرا نمی‌کند.
بسته برای alias فعلی `hooshix-server` و hostname `mail.hooshix.com` بسته شده؛
برای سرور دوم، bindingهای میزبان، alias و نسخهٔ ابزارهای OS را در تغییر مستقل
بررسی کنید، نه با دورزدن کنترل مقصد یا کپی دادهٔ سرور اول.

عملیات فقط فایل **جدید** `/var/lib/hooshixstorage/redis.ext4` را کامل رزرو و ext4
می‌کند، آن را روی `/var/lib/hooshixstorage/redis` با
`nosuid,nodev,noexec,noatime,errors=remount-ro` سوار و data را به UID/GID `10001`
می‌دهد. هیچ دیسک/block device یا فایل موجودی فرمت نمی‌شود. guard مستقل
`hooshix-redis-storage-guard.service` ابتدا سالم فعال می‌شود و سپس drop-in
`34-hooshix-redis-storage.conf` به وابستگی K3s اضافه می‌شود؛ فایل‌های دو guard
قدیمی تغییر نمی‌کنند. سرویس، PID و هویت فایل‌های storage قبلی قبل/بعد مقایسه
می‌شوند؛ نصب package، restart، reboot، PV/PVC یا Redis انجام نمی‌شود.

انتظار پس از اجرای موفق مقصد: `REDIS_STORAGE=Passed` و مسیر `PUBLIC_RECEIPT`؛
رسید باید `runtime_uid=10001`، `backing_bytes=8589934592`،
`storage_guard=Passed`، `existing_storage_host_services=Preserved` و
`k3s_restart_performed=false` داشته باشد. این نتیجه فقط storage است:
`redis`، `persistent_volume` و `secret_delivery` هنوز `Not run` و دسترسی `CLOSED` است.

شکست جزئی چیزی را خودکار حذف/فرمت/تکرار نمی‌کند؛ رسید عمومی را نگه دارید و علت
را اصلاح کنید. اگر دادهٔ Redis از قبل حاضر باشد، ابزار foundation ادامه نمی‌دهد.
در خرابی storage، guard شروع K3s را رد یا سرویس K3s را متوقف می‌کند؛ **همهٔ
containerهای موجود الزاماً متوقف نمی‌شوند** و bind موجود می‌تواند فایل‌سیستم
قدیمی را نگه دارد. recovery/re-arm باید صریح و پس از بررسی هویت mount باشد؛
guard/drop-in را برای بالا آمدن سرویس حذف نکنید. native CI سناریوهای ENOSPC،
remount، UUID غلط، readonly، backing loss، unmount و ممنوعیت fallback به root را
روی fixture دورریختنی ۶۴ MiB اجرا می‌کند، نه روی VPS.
آزمون reboot/fault مقصد، TLS/ACL/ESO و نصب Redis، سازگاری برنامه، ظرفیت هم‌زمان
و recovery مستقل باقی‌اند؛ Stage10/Production هنوز `Not verified` است.

مرجع رفتار وابستگی: [systemd unit dependencies](https://www.freedesktop.org/software/systemd/man/259/systemd.unit.html).

### بازیابی صریح guard Redis و K3s

اگر guard Redis در حالت `failed` و K3s متوقف است، ابزار foundation یا تحویل
گواهی را برای recovery دوباره اجرا نکنید. با VNC قابل ورود و نشست دوم SSH باز،
ابتدا وضعیت mount و علت شکست guard را بررسی کنید. در نصب فعلی که هر چهار
storage موجودند، فقط اگر بررسی مستقل هویت هر چهار storage موفق باشد، re-arm
صریح زیر مجاز است. این عملیات داده، UUID، backing، permission یا unit را تغییر
نمی‌دهد؛ تنها guard Redis و K3s متوقف‌شده را راه‌اندازی می‌کند.

در shell لینوکسِ همان VPS، رمز sudo را فقط در ورودی محلی وارد کنید:

```bash
set -e
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/openbao-storage-guard.py --guard-check
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/postgresql-storage-guard.py --guard-check
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/redis-storage-guard.py --guard-check
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/kafka-storage-guard.py --guard-check
sudo systemctl reset-failed hooshix-redis-storage-guard.service
sudo systemctl start hooshix-redis-storage-guard.service
sudo systemctl is-active hooshix-openbao-storage-guard.service hooshix-postgresql-storage-guard.service hooshix-redis-storage-guard.service hooshix-kafka-storage-guard.service
sudo systemctl start k3s.service
sudo systemctl is-active k3s.service
```

هر بررسی هویت باید exit صفر و هر سرویس در پایان `active` داشته باشد. در خطا
متوقف شوید؛ هیچ guard یا وابستگی را حذف، غیرفعال یا ضعیف نکنید. موفقیت یک بررسی
لحظه‌ای علت تاریخی شکست را ثابت نمی‌کند؛ شکست دوباره نیاز به بررسی علت دارد،
نه تکرار خودکار re-arm. پس از فعال‌شدن K3s، وضعیت OpenBao و ESO را بررسی کنید؛
اگر OpenBao sealed بود، unseal فقط با همان custody و عملیات جداگانهٔ تحت نظارت
انجام می‌شود، بدون init یا recovery initialization. نتیجهٔ VPS هنوز تا اجرای
موفق و readback **Not verified** است.

### بازیابی enrollment کنترلرهای ESO پس از توقف K3s

پیش‌شرط: K3s و هر چهار guard فعال، OpenBao موجود unsealed و مسیر نجات باز
باشد. اگر SecretStoreها خطای اتصال دارند، فقط annotation
`ambient.istio.io/redirection=enabled` را نشانهٔ سلامت ندانید؛ listenerهای
`15001`، `15006` و `15008` باید در network namespace همان Pod وجود داشته باشند.
بررسی فقط‌خواندنی باید هویت Pod/ServiceAccount و sandbox جاری را تطبیق دهد؛
نبود listener در هر دو کنترلر با وجود listener سالم OpenBao، مجوز تغییر CA،
auth role، credential یا خاموش‌کردن STRICT نیست.

فقط در همین وضعیتِ تأییدشده، Podهای دو کنترلر stateless زیر را بازسازی کنید؛
Deployment، image، ServiceAccount، template و سیاست شبکه تغییر نمی‌کنند.
webhook، cert-controller، OpenBao و workloadهای داده حذف یا restart نمی‌شوند:

```bash
set -e
sudo /usr/local/bin/k3s kubectl -n platform-apps delete pod -l app.kubernetes.io/instance=hooshix-eso,app.kubernetes.io/name=external-secrets --wait=true --timeout=90s
sudo /usr/local/bin/k3s kubectl -n platform-data delete pod -l app.kubernetes.io/instance=hooshix-postgresql-eso,app.kubernetes.io/name=external-secrets --wait=true --timeout=90s
sudo /usr/local/bin/k3s kubectl -n platform-apps rollout status deployment/hooshix-eso --timeout=180s
sudo /usr/local/bin/k3s kubectl -n platform-data rollout status deployment/hooshix-postgresql-eso --timeout=180s
sudo /usr/local/bin/k3s kubectl -n platform-data wait --for=condition=Ready externalsecrets --all --timeout=180s
sudo /usr/local/bin/k3s kubectl -n platform-apps wait --for=condition=Ready secretstores --all --timeout=180s
```

انتظار، rollout موفق هر دو کنترلر و Ready شدن منابع موجود است؛ Secret یا
ExternalSecret را حذف، اسرار را بازتولید یا نصب پایه را تکرار نکنید. اگر recovery
شکست خورد، همان خروجی را نگه دارید و علت جدید را بررسی کنید؛ تکرار خودکار یا
انتظار طولانیِ بی‌تغییر مجاز نیست. موفقیت این گام فقط بازیابی همگام‌سازی ESO است،
نه تمدید خودکار گواهی، رفع علت تاریخی توقف guard یا تأیید Production. پس از آن
تحویل گواهی client Kafka با همان bundle و custody تأییدشده عملیات جداگانه است.

### بازیابی Pod موجود Kafka در وضعیت Unknown

پس از بازیابی K3s، اگر StatefulSet موجود `kafka` همچنان یک replica می‌خواهد اما
Pod قدیمی `kafka-0` در وضعیت `Unknown` مانده است، ساخت broker را از ابتدا اجرا
نکنید. پیش‌شرط، سلامت چهار guard و filesystemهای قبلی، همان StatefulSet/PVC و
node همین نصب است؛ منبع یا claim بیگانه نباید adopt شود. فقط پس از Ready بودن
node، حذف عادی همان Pod و انتظار روی StatefulSet موجود مجاز است:

```bash
set -e
sudo /usr/local/bin/k3s kubectl wait --for=condition=Ready node/hooshix-production-1 --timeout=30s
sudo /usr/local/bin/k3s kubectl -n platform-data delete pod/kafka-0 --wait=true --timeout=90s
sudo /usr/local/bin/k3s kubectl -n platform-data wait --for=jsonpath={.status.readyReplicas}=1 statefulset/kafka --timeout=420s
```

نام node مختص نصب جاری است؛ در نصب دوم باید نام node همان نصب باشد. این فرمان
هیچ StatefulSet، PVC/PV، cluster ID، فایل KRaft، backing یا گواهی را حذف یا
تغییر نمی‌دهد و از `--force` یا `--grace-period=0` استفاده نمی‌کند. اگر node
Ready نیست، حذف عادی تمام نشد یا انتظار Ready شکست خورد، متوقف شوید؛ برای دورزدن
مشکل، Pod را force-delete، replica را زیاد یا data را format/reset نکنید.
راهبرد StatefulSet موجود `OnDelete` است؛ آن را تغییر ندهید. اگر حذف عادی قبلاً
موفق شده است، فقط فرمان انتظار را اجرا کنید و حذف Pod را تکرار نکنید.
انتظار روی StatefulSet از خطای زودهنگام NotFound هنگام ساخته‌نشدن Pod جایگزین
جلوگیری می‌کند. Ready شدن broker فقط بازیابی runtime موجود است؛ هویت storage،
node TLS و مرز بستهٔ قبلی پیش از تحویل client دوباره توسط ابزار بررسی می‌شوند.
هیچ topic تجاری، ACL برنامه یا دسترسی عمومی از این recovery ایجاد نمی‌شود.

### رزرو فضای آماده برای Redis

رزرو LocalPV از فایل‌سیستم سالم و guardدار موجود استفاده می‌کند؛ هیچ تخصیص، فرمت،
حذف داده یا ساخت Redis/PVC/Secret انجام نمی‌شود. فضای OpenBao و PostgreSQL و
قواعد admission فعلی باید حفظ شوند. این رزرو به‌تنهایی مجوز اجرای Redis نیست.

فضای ۸ GiB و guard این نصب روی مقصد `Passed` شده‌اند. حجم رزرو Kubernetes
**۷ GiB** از همان فایل‌سیستم است تا سربار ext4 و فضای رزرو filesystem لحاظ شود،
نه تخصیص دیسک جدید. ابزار `install_redis_local_volume.py` فقط این دو منبع را
ایجاد می‌کند و هر دو را پیش از اولین نوشتن از نظر تضاد بررسی می‌کند:

- StorageClass: `hooshix-redis-local`، بدون provisioner پویا، غیر default،
  `Retain`، `WaitForFirstConsumer` و بدون expansion.
- PV: `hooshix-redis-local-7gib`، مسیر `/var/lib/hooshixstorage/redis/data`،
  UID/GID `10001` و nodeAffinity دقیق `hooshix-production-1`، رزروشده فقط برای
  claim آیندهٔ `platform-data/data-security-redis-0`؛ `Prune=confirm`.

guard نصب‌شده باید با منبع بررسی‌شده منطبق و سالم باشد؛ هر سه guard باید به
K3s متصل بمانند، audit فعال و بدون lost record باشد و data مخصوص Redis هنوز
خالی باشد. وجود PostgreSQL/ESO در namespace طبیعی است؛ namespace خالی یا حذف
دادهٔ PostgreSQL درخواست نمی‌شود. claimهای Bound قبلی OpenBao و PostgreSQL و
قانون fail-closed فعلی داده قبل/بعد یکسان می‌مانند؛ admission برای Redis باز
نمی‌شود. منبع خارجی، تغییرکرده، Bound یا Released adopt/overwrite نمی‌شود.

پس از baseline موفق همان درخت کد، از checkout تمیز main بستهٔ عمومی را بسازید:

```bash
python3 scripts/production/build_redis_local_volume_bundle.py \
  --native-run <successful-exact-tree-baseline-run> \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-redis-local-volume-YYYYMMDD
```

builder هم‌زمان `redis-storage` و `openbao-kubernetes` همان attempt را از GitHub
می‌گیرد و hash منبع، UID `10001`، freshness و native API/mount/Retain را تطبیق
می‌دهد؛ رسید نوشته‌شده با دست جایگزین آن نیست. fixture، رزرو، رد claim اشتباه،
پایداری روی همان PVC، nodeAffinity و نگه‌داشتن داده بعد از حذف claim را فقط در
کلاستر دورریختنی می‌آزماید. هیچ فایل‌سیستم مقصدی برای این آزمون پاک نمی‌شود.

در PowerShell با VNC و نشست دوم SSH آماده، فقط رمز sudo همان‌جا لازم است:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-local-volume-YYYYMMDD\run-redis-local-volume.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-local-volume-YYYYMMDD\run-redis-local-volume.ps1' -RescueAndSecondSessionReady
```

نتیجهٔ مورد انتظار: `REDIS_LOCAL_VOLUME=Passed` و `PUBLIC_RECEIPT` با
`capacity_bytes=7516192768`، `existing_storage_host_services=Preserved`،
`admission=Unchanged` و `redis/pvc/secret_delivery=Not run`.
خطا یا تضاد، حالت جزئی را نگه می‌دارد و هیچ delete/patch/force/فرمت خودکاری ندارد؛
ابتدا همان receipt عمومی بررسی شود. حذف PV برای رفع خطا یا تغییر claimRef مجاز
نیست. مسیر سرور دوم باید از منبع بررسی‌شدهٔ همان نصب ساخته شود.
گام بعد TLS/ACL و تحویل از OpenBao/ESO و سپس workload بستهٔ Redis است؛
نبود Redis/PVC در این مرحله طبیعی است و Stage10 هنوز `Not verified` است.

برای VPS فعلی، رسید محلی ۲۰۲۶-۱۰-۱۸ رزرو `hooshix-redis-local-7gib` را با
`REDIS_LOCAL_VOLUME=Passed` و حفظ سرویس‌ها تأیید کرده است؛ این نتیجه به معنی
اجرای Redis یا تأیید فضای سرور دوم نیست.

مرجع: [Kubernetes PV reservation](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#reserving-a-persistentvolume).

### تحویل ACL و رمزهای Redis از OpenBao

این مرحله از controller موجود و محدود به `platform-data` استفاده می‌کند؛ controller،
دسترسی شبکه یا workload تازه‌ای نمی‌سازد. پنج رمز مستقل برای authorization، identity،
webbff، health و monitor در OpenBao نگهداری می‌شوند؛ ACL فقط از hash همان رمزها ساخته
می‌شود و مانند رمز، محرمانه است. هیچ مقدار یا hash در plan عمومی یا Git قرار نمی‌گیرد.
اجرای Redis، TLS مقصد و اتصال برنامه‌ها تا commissioning جداگانه بسته می‌مانند.

پیش‌نیازها: OpenBao موجود unsealed، custody قبلی قابل بازیابی، controller امضاشدهٔ
`hooshix-postgresql-eso` و validation webhook سالم، namespace `platform-data` با
Ambient/STRICT و restricted PSA، admission بستهٔ فعلی و فضای Redis/LocalPV قبلی.
PostgreSQL قرنطینه‌شده و هویت‌های موجود نباید تغییر کنند. Root فقط برای همین
bootstrap نظارت‌شده استفاده می‌شود و لغو یا reset نمی‌شود.
حالت Bound بودن volume PostgreSQL با UID دقیق claim و مالکیت Cluster موجود
اعتبارسنجی می‌شود؛ این volume به حالت Available برگردانده یا دوباره رزرو نمی‌شود.

شش حساب `eso-redis-<user>` و `eso-redis-acl` فقط leaf دقیق خود و عملیات self-only
توکن را می‌خوانند؛ عمر توکن حداکثر پنج دقیقه است. Role جداگانه فقط TokenRequest
همین شش نام را به controller موجود می‌دهد؛ RBAC قبلی PostgreSQL دست‌نخورده است.
اسرار `security-redis-authorization`، `security-redis-identity`، `security-redis-webbff`،
`security-redis-health` و `security-redis-monitor` نوع basic-auth دارند و
`security-redis-acl` فقط `users.acl` محرمانه را دارد. برنامه‌ها در این مرحله
Secret یا اتصال Redis دریافت نمی‌کنند. مسیر سه credential برنامه زیر مسیر
OpenBao همان مالک است؛ health/monitor/ACL از هیچ هویت برنامه‌ای قابل خواندن نیستند.

بستهٔ عمومی از checkout تمیز همان main و staging موفقِ همان tree ساخته شود:

```bash
python3 scripts/production/build_redis_secrets_bundle.py \
  --staging-run <successful-platform-staging-run> \
  --chart /path/outside-repo/eso-2.12.0.tgz --helm /path/to/helm \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-redis-secrets-YYYYMMDD
```

builder شواهد native تحویل، binding رمز/ACL، هویت، denial، reentry و حفظ حالت را
از artifact احراز‌شده می‌خواهد؛ receipt دست‌ساز جایگزین نمی‌شود. plan، source hash
و bundle هیچ رمز یا hash رمز ندارند. ابتدا فقط بررسی محلی بسته:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_redis_secrets_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-redis-secrets-YYYYMMDD --validate-only
```

با VNC قابل ورود و نشست دوم SSH باز، فقط مالک فرمان زیر را اجرا کند؛ رمز موجود
custody مربوط به OpenBao و رمز sudo فقط در دو ورودی مخفی همان پنجره وارد شوند.
رمز CA/Root، توکن GHCR یا رمز تازه لازم نیست:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_redis_secrets_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-redis-secrets-YYYYMMDD --custody /home/coder/.local/share/hooshix-openbao-custody/<existing-custody-id> --rescue-and-second-session-ready
```

نتیجهٔ مورد انتظار `REDIS_SECRET_DELIVERY=Passed`، پنج credential مستقل، شش
SecretStore/ExternalSecret، ACL متناظر و `public_access=CLOSED` است؛ receipt عمومی
زیر `~/.local/share/hooshix-redis-secrets/` ذخیره می‌شود. این **انتظار اجرایی** است،
نه ادعای اجرا روی مقصد. TLS و runtime هنوز `Not run` و Production `Not verified` است.

در تکرار، credential/ACL موجود فقط با مالکیت و محتوای دقیق پذیرفته می‌شوند؛
هیچ rotation، overwrite، adopt، delete یا patch خودکار وجود ندارد. تضاد یا سابقهٔ
حذف‌شده عملیات را متوقف و حالت جزئی را حفظ می‌کند. برای رفع خطا Secret یا مسیر
OpenBao را حذف نکنید؛ receipt بدون اسرار بررسی و فقط پس از اصلاح علت دوباره اجرا شود.
در استفادهٔ روزمره controller هر دقیقه sync می‌کند؛ مقدارها، ACL، hashها و خروجی
`kubectl get secret -o yaml/json` را در چت یا گزارش عمومی نفرستید. rotation و TLS
و انتقال Secret به namespace برنامه‌ها فقط در تغییر بازبینی‌شدهٔ مربوط انجام می‌شود.

مرجع: [ESO Vault Kubernetes auth](https://external-secrets.io/latest/provider/hashicorp-vault/).

### تحویل گواهی اولیهٔ Redis

پس از `REDIS_SECRET_DELIVERY=Passed`، بستهٔ TLS فقط گواهی server و کلید همان Redis
را از intermediate موجود صادر و از OpenBao به ESO تحویل می‌دهد؛ Redis/PVC، شبکه،
admission و دسترسی برنامه‌ها را تغییر نمی‌دهد. Root جدید ساخته نمی‌شود و رمزهای
قبلی یا ACL تعویض نمی‌شوند. cert-manager همچنان فقط در scope قبلی خود کار می‌کند.

پیش‌نیاز: همان checkout تمیز main، custody قابل بازیابی، ESO و شش تحویل قبلی سالم،
PostgreSQL قرنطینه‌شده با volume دقیق موجود، فضای Redis هنوز رزروشده و بدون PVC،
audit سالم، VNC و نشست دوم SSH. intermediate رمزدار موجود در scope قبلی
`istio-system` می‌ماند؛ فقط مالک رمز آن را در ورودی مخفی وارد می‌کند. ابزار روی
مقصد OpenSSL موجودِ baseline را می‌خواهد؛ نصب یا ارتقای package انجام نمی‌دهد.
پردازش plaintext کلید leaf فقط در tmpfs خصوصی با **swap غیرفعال مقصد** انجام
می‌شود؛ ابزار در غیر این صورت متوقف می‌شود، نه اینکه خودش swap یا سرویس‌ها را تغییر دهد.

بستهٔ public و فاقد کلید از main و staging موفق همان tree ساخته شود:

```bash
python3 scripts/production/build_redis_secrets_bundle.py --tls \
  --staging-run <successful-platform-staging-run> \
  --chart /path/outside-repo/eso-2.12.0.tgz --helm /path/to/helm \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-redis-tls-YYYYMMDD
```

پیش از اتصال، فقط یک بار اعتبارسنجی محلی بسته:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_redis_secrets_operator.py --tls --bundle /mnt/c/Users/Coder/Downloads/HooshiX-redis-tls-YYYYMMDD --validate-only
```

مالک با VNC قابل ورود و نشست دوم SSH باز اجرا کند:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_redis_secrets_operator.py --tls --bundle /mnt/c/Users/Coder/Downloads/HooshiX-redis-tls-YYYYMMDD --custody /home/coder/.local/share/hooshix-openbao-custody/<existing-custody-id> --rescue-and-second-session-ready
```

ورودی‌ها به‌ترتیب: `READY`، رمز **موجود custody مربوط به OpenBao**، رمز **موجود
کلید intermediate**، سپس رمز sudo. هیچ رمز تازه، رمز Root یا token GHCR لازم نیست؛
هیچ‌کدام را در چت یا argv ننویسید. خروجی مورد انتظار `REDIS_TLS_DELIVERY=Passed`
و receipt فاقد اسرار زیر `~/.local/share/hooshix-redis-secrets/tls-*.json` است؛ این
خروجی مورد انتظار به‌معنی اجرای تأییدشده روی سرور دوم یا فعلی نیست.

Secret نوع TLS به نام `platform-data/security-redis-tls` فقط `tls.crt`، `tls.key`
و `ca.crt` دارد. هویت `eso-redis-tls` فقط leaf دقیق `production/security-redis/tls`
را می‌خواند و عمر توکن حداکثر پنج دقیقه است؛ Role جداگانه فقط TokenRequest همین
نام را اضافه می‌کند. شش هویت قبلی و برنامه‌ها کلید leaf جدید را نمی‌خوانند.
گواهی RSA-3072 و server-only با عمر ۳۰ روز فقط برای `127.0.0.1` و این دو نام است:
`security-redis.platform-data.svc` و `security-redis.platform-data.svc.cluster.local`.
کلید intermediate و Root در leaf OpenBao یا Secret جدید ذخیره نمی‌شوند.

در استفادهٔ روزمره ESO هر دقیقه همین leaf را sync می‌کند؛ اجرای مجدد ابزار آن را
تمدید نمی‌کند. ابزار حداقل هفت روز اعتبار باقی‌مانده می‌خواهد؛ تضاد، سابقهٔ حذف،
گواهی نامعتبر یا نزدیک انقضا باعث توقف و حفظ حالت می‌شود. برای رفع خطا KV/Secret
یا CA را حذف نکنید و فرمان را کورکورانه تکرار نکنید. بخش renewal/reload و توقف
ایمن هنگام انقضا باید در commissioning runtime تکمیل شود؛ تا آن زمان برنامه‌ها
و Production بسته‌اند. تحویل TLS به‌تنهایی اجرای Redis، backup یا ظرفیت نیست.

مراجع: [OpenSSL 3.5 x509](https://docs.openssl.org/3.5/man1/openssl-x509/)،
[Redis TLS](https://redis.io/docs/latest/operate/oss_and_stack/management/security/encryption/)
و [ESO Vault](https://external-secrets.io/latest/provider/hashicorp-vault/).

### اجرای داخلی و بستهٔ Redis

پس از تحویل credential و TLS، فقط همان تصویر امضاشده و LocalPV رزروشده مصرف
می‌شوند. ایجاد workload به معنی بازشدن دسترسی برنامه‌ها یا تأیید Production نیست.
فضای OpenBao/PostgreSQL و هویت‌های موجود حفظ می‌شوند؛ حذف PVC، فرمت مجدد و
ساخت CA یا رمز تازه جزو این مرحله نیست. `render_redis_runtime.py` مرجع desired
state است. builder فقط از main تمیز و evidence معتبر همان Git tree با عمر حداکثر
پنج روز، پس از بررسی انتشار محافظت‌شدهٔ موجود و اسکن تازه، بسته می‌سازد؛ تصویر
دوباره build یا publish نمی‌شود. ابزارهای integrity-pinned release، از جمله Grype،
باید در `PATH` باشند؛ همان chart/Helm معتبر مراحل قبل استفاده می‌شود:

```bash
python3 scripts/production/build_redis_runtime_bundle.py \
  --staging-run <successful-platform-staging-run> \
  --chart <verified-eso-2.12.0-chart.tgz> --helm <verified-helm-4.2.4> \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-redis-runtime-YYYYMMDD
```

اعتبارسنجی محلی، بدون تماس با VPS یا خواندن custody:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-runtime-YYYYMMDD\run-redis-runtime.ps1' -ValidateOnly
```

با VNC قابل ورود و نشست دوم SSH باز، اجرای مالک فقط رمز sudo می‌خواهد؛ هیچ token
یا رمز custody/intermediate لازم نیست:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-redis-runtime-YYYYMMDD\run-redis-runtime.ps1' -RescueAndSecondSessionReady
```

اجرای مالک در 2026-10-20 با `REDIS_RUNTIME=Passed` پایان یافت؛ receipt عمومی
کنار بستهٔ `HooshiX-redis-runtime-20261020` ذخیره شد. این فقط اجرای داخلی را
تأیید می‌کند، نه آمادگی Production. یک StatefulSet تک‌نمونه
`platform-data/security-redis` و PVC مستقل
`data-security-redis-0` فقط PV موجود `hooshix-redis-local-7gib` را مصرف می‌کنند.
Service از نوع ClusterIP/headless است؛ NetworkPolicy ورودی ندارد و AuthorizationPolicy
هیچ هویتی را مجاز نمی‌کند. فقط egress کنترل mesh روی 15012 لازم است. برنامه‌ها
Secret projection یا اجازهٔ اتصال نمی‌گیرند. Pod بدون token خودکار، non-root 10001،
read-only root، drop ALL، RuntimeDefault و منابع محدود است.

برای commissioning بسته، سقف dataset برابر ۱۲۸ MiB و سقف حافظهٔ container برابر
۵۱۲ MiB است؛ این انتخاب محافظه‌کارانه جایگزین ظرفیت‌سنجی نهایی و headroom سی‌درصدی
نیست. TLS فقط روی 6379، plaintext غیرفعال، `noeviction` و AOF/everysec برقرارند.
probe محلی فقط credential هویت PING-only را از فایل می‌خواند؛ رمز وارد argv یا log
نمی‌شود. liveness با مهلت طولانی‌تر و startup مستقل تنظیم شده‌اند؛ SIGTERM فرصت
۶۰ ثانیه‌ای برای shutdown دارد. تست native روی دادهٔ مصنوعی، graceful restart/AOF،
انکار plaintext/ACL/admin، admission امضا/provenance/SBOM و دسترسی شبکهٔ غیرمجاز
را بررسی می‌کند. guard فایل‌سیستم مقصد جداگانه بررسی می‌شود؛ path موقت CI شاهد
mount/fault/reboot مقصد نیست.

اجرای مجدد فقط UID/spec/claim موجود و credential/TLS بدون تغییر را می‌پذیرد؛
داده یا manifest ناشناخته با حفظ state متوقف می‌شود. در شکست، PVC/PV/AOF را حذف
نکنید و guard را غیرفعال نکنید. rollback در حالت بسته توقف همان StatefulSet
در maintenance تأییدشده است؛ volume و Secretها Retain می‌مانند. تغییر پیکربندی
با `OnDelete` خودکار process را restart نمی‌کند و نیازمند تغییر بازبینی‌شده است.
renewal/reload گواهی ۳۰روزه، اتصال برنامه‌ها، monitoring کامل، capacity و Production
هنوز **Not verified** هستند؛ قبل از نزدیک‌شدن انقضا یا بازکردن ترافیک باید جداگانه
تکمیل شوند. ورود موفق و PING به معنی آماده‌بودن محصول روی دامنه نیست.

### کاندید منتشرشدهٔ Kafka؛ runtime هنوز نصب نشده

مرجع کاندید تک‌سرور Kafka `4.2.2` و تصویر upstream ثابت
`apache/kafka@sha256:daa39dc85acd37b148fad74d6b22b60fa5f7fa05048a5c8aa3b654140c63dd58`
است. تصویر security-overlay نسخه‌های دقیق بسته‌های Alpine و checksum تمام
overlayهای Jackson `2.21.7` و LZ4 Java `1.11.4` را در
`infrastructure/production/kafka/candidate.json` ثبت می‌کند؛ Kafka clientهای
سرویس‌ها جدا هستند و با این تغییر ارتقا نمی‌یابند.

ساخت محلی فقط از ریشهٔ checkout تمیز و با Docker محلی و ابزارهای integrity-pinned
Syft/Grype اجرا می‌شود. مسیر evidence باید تازه و خالی باشد:

```bash
python3 scripts/production/build_kafka_candidate.py \
  --directory /tmp/hooshix-kafka-candidate-<unique>/evidence
```

خروجی موفق باید `scan=Passed`، صفر High/Critical و `native_smoke.kraft` و
`native_smoke.local_produce_consume` برابر `Passed` داشته باشد. تست محلی فقط
broker دورریختنی با شبکهٔ قطع‌شده و دادهٔ مصنوعی را بررسی می‌کند؛ TLS/ACL، staging،
backup/restore و اجرای VPS را ثابت نمی‌کند.

پس از ادغام تغییر به `main` و موفقیت baseline همان SHA، مالک می‌تواند از GitHub
Actions، workflow **Production release evidence** را با ورودی
`kafka-candidate` اجرا کند. مقصد ثابت ابزار برای انتشار خصوصی فقط
`ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private` است. نام قدیمی
`platform-kafka-private` بازنشسته و حذف شده و نباید دوباره استفاده شود؛ GitHub اجازهٔ تبدیل
package عمومی به خصوصی را نمی‌دهد. پسوند `private` به‌تنهایی مدرک خصوصی‌بودن نیست.
در ایجاد اولیهٔ package از workflow با `GITHUB_TOKEN`، GitHub به‌طور پیش‌فرض مدل
visibility و permissions مخزن را به package منتقل می‌کند؛ با مخزن Public نباید
برای ایجاد یک مقصد خصوصی به این مسیر تکیه کرد. visibility یک package موجود را
می‌توان با دسترسی granular مستقل از مخزن تنظیم کرد.

package باید از پیش به‌صورت خصوصی آماده شده باشد؛ ایجاد مقصد جدید در این workflow
مجاز نیست. آماده‌سازی مقصد جدید **Passed** است: metadata خصوصی و رد خواندن ناشناس
باید مستقل بررسی شوند؛ این نتیجه به معنی انتشار candidate یا نصب Kafka نیست.
اعتبار مدیریت registry باید
یک PAT **classic** موقت و جدا از کلید خواندنی VPS باشد: ساخت مقصد به
`read:packages` و `write:packages` و حذف مصوب package قبلی به `delete:packages`
نیاز دارد. این اعتبار فقط در فایل خصوصی خارج Git روی دستگاه مالک نگه‌داری شود؛
مجوز کلید خواندنی موجود VPS افزایش پیدا نکند و اعتبار موقت پس از اتمام کار لغو شود.
برای registry تازه، فقط یک‌بار و خارج Actions، با PAT مالک یک تصویر خالی و بدون
فایل، executable، کلید یا سورس محصول منتشر کنید تا namespace خصوصی ایجاد شود.
این تصویر فقط رزرو registry است؛ Kafka نیست، امضا/پذیرش استقرار ندارد و هرگز نباید
deploy شود. اگر package خصوصی از قبل موجود است، این مرحله را تکرار نکنید.
فرمان‌های زیر در Bash محلی اجرا می‌شوند؛ tracing/ضبط ترمینال خاموش باشد:

```bash
(
set -euo pipefail
registry_config=$(mktemp -d)
chmod 700 "$registry_config"
export DOCKER_CONFIG="$registry_config"
trap 'unset registry_token; rm -f -- "$registry_config/config.json"; rmdir -- "$registry_config"; unset DOCKER_CONFIG' EXIT
read -r -s -p 'Temporary GHCR classic token: ' registry_token
printf '\n'
printf '%s\n' "$registry_token" | docker --host unix:///var/run/docker.sock login ghcr.io --username hasanjodatshandi --password-stdin
unset registry_token
chmod 600 "$registry_config/config.json"
tar -cf - --files-from /dev/null | docker --host unix:///var/run/docker.sock image import \
  --change 'LABEL org.opencontainers.image.source=https://github.com/hasanjodatshandi/HooshiX' \
  --change 'LABEL org.opencontainers.image.description="Empty registry provisioning only; never deploy"' \
  --change 'USER 65532:65532' - \
  ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private:registry-bootstrap
docker --host unix:///var/run/docker.sock push ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private:registry-bootstrap
docker --host unix:///var/run/docker.sock logout ghcr.io
)
```

اگر هر فرمان ناموفق شد، ادامه ندهید. metadata مقصد باید `private` باشد و درخواست
ناشناس token/manifest نباید خواندن موفق بدهد. تصویر خالی جایگزین build و scan و
امضای candidate نیست. label اتصال repository به‌تنهایی اثبات دسترسی Actions نیست.
در تنظیمات همان package،
visibility باید Private بماند و در **Manage Actions access** فقط دسترسی موردنیاز
انتشار برای مخزن `hasanjodatshandi/HooshiX` با نقش **Write** تنظیم شود. احراز هویت
مالک با احراز هویت workflow یکسان نیست؛ خود `GITHUB_TOKEN` نیز باید در preflight
بتواند metadata مقصد را بخواند. پیش از dispatch، بررسی
زیر باید دقیقاً `private` برگرداند؛ خطای 404 می‌تواند ناشی از نبود package یا نداشتن
دسترسی باشد و مجوز ساخت خودکار آن نیست:

```bash
gh api users/hasanjodatshandi/packages/container/hooshix%2Fplatform-kafka-v2-private --jq .visibility
```

Publisher پیش از build و دوباره پیش از push، visibility مقصد موجود را می‌سنجد.
پاسخ‌های 401/403/404، timeout و مقدار `public`، `internal` یا نامعتبر همگی عملیات
را پیش از push متوقف می‌کنند. پس از push نیز باید visibility برابر `private` باشد
تا scan/sign و receipt موفق صادر شود. این workflow هرگز نصب یا deploy نمی‌کند. receipt باید
`registry_visibility=private` و `publication=Passed` و در عین حال
`staging=Not run`، `deployment=Not run` و `application_ingress=CLOSED` نشان دهد؛
تا تکمیل TLS/ACL، آزمون staging، backup/recovery و ظرفیت، candidate را روی VPS
نصب نکنید و نسخهٔ منتشرشده را Production-ready تلقی نکنید.

مرجع محدودیت بازگشت visibility:
[GitHub Packages visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
مرجع رفتار پیش‌فرض ایجاد از workflow و دسترسی نوشتن:
[GitHub Actions package permissions](https://docs.github.com/en/packages/managing-github-packages-using-github-actions-workflows/publishing-and-installing-a-package-with-github-actions#default-permissions-and-access-settings-for-packages-modified-through-workflows).

### فضای مستقل Kafka روی VPS

نصب Kafka از فضای مستقل ۱۶GiB با guard هویت mount آغاز می‌شود؛ این فضا از
OpenBao، PostgreSQL و Redis جداست و محدودیت filesystem واقعی دارد. تخصیص فضا،
ساخت Kafka یا تأیید ظرفیت Production نیست. تا تحویل TLS از OpenBao/ESO و
نصب workload داخلیِ بررسی‌شده، وضعیت خود Kafka **Not run** باقی می‌ماند.
اسرار و داده‌های موجود نباید کپی، فرمت مجدد یا جایگزین شوند.

پیش‌نیاز مقصد، همان نسخه‌های ابزار میزبان در جدول سازگاری و فعال‌بودن guard هر
سه datastore موجود است. پس از رزرو، حداقل ۳۰٪ دیسک اصلی باید آزاد بماند؛ این
شرط، تضمین ظرفیت نهایی یا سرعت دیسک نیست. فایل backing برابر
`/var/lib/hooshixstorage/kafka.ext4` و mount برابر
`/var/lib/hooshixstorage/kafka` است. قفل، state، mount unit، guard و drop-in
K3s فقط نام‌های Kafka را دارند. مسیر unmounted برای کاربر غیر-root قابل‌نوشتن نیست.

از checkout تمیز main با CI موفق، bundle عمومی را خارج Git بسازید؛
`MAIN_BASELINE_RUN_ID` شناسهٔ اجرای موفق `Repository baseline` با tree دقیق همان
main است، نه هر اجرای قدیمی:

```bash
python3 scripts/production/build_kafka_storage_bundle.py \
  --native-run MAIN_BASELINE_RUN_ID \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-storage-20261021
```

اگر پوشه از قبل وجود دارد، نام تازه انتخاب کنید؛ روی آن overwrite نکنید.
bundle به receipt عمومیِ native با عمر حداکثر پنج روز و hash دقیق دو منبع وابسته
است. با کنسول نجات VNC قابل ورود و نشست دوم SSH خصوصی باز، در PowerShell اجرا کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-storage-20261021\run-kafka-storage.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-storage-20261021\run-kafka-storage.ps1' -RescueAndSecondSessionReady
```

فقط `READY` دقیق با صفحه‌کلید انگلیسی و رمز sudo در همان پنجره لازم است؛ رمز
OpenBao/Root/CA یا توکن GHCR در این گام استفاده نمی‌شود. خروجی مورد انتظار
`KAFKA_STORAGE=Passed` و مسیر receipt است؛ فایل JSON باید
`kafka_storage=Passed`، `storage_guard=Passed`، `backing_bytes=17179869184`،
`runtime_uid=10001`، `existing_storage_host_services=Preserved` و
`k3s_restart_performed=false` داشته باشد. این عملیات reboot، restart یا نصب پکیج ندارد.
هنوز `kafka`، `persistent_volume` و `secret_delivery` برابر `Not run` و
`production_readiness` و `reboot_persistence` برابر `Not verified` هستند.

در خرابی یا state ناشناخته تکرار کور، فرمت، حذف backing/state/قفل و restart
خودکار ممنوع است. guard هویت mount/backing را بررسی می‌کند؛ خرابی آن K3s را
متوقف می‌کند، نه الزاماً تمام containerهای موجود را. بازیابی و re-arm فقط پس از
بررسی هویت filesystem و با همان مسیر نجات انجام می‌شود. receipt موفقِ مقصد
پیش‌نیاز رزرو LocalPV و تحویل TLS است، نه مجوز بازکردن ترافیک.

### رزرو volume برای Kafka

پس از موفقیت فضای مستقل، گام بعد رزرو LocalPV با سیاست `Retain` و claim ثابت
Kafka است؛ رزرو، گواهی یا broker ایجاد نمی‌کند. پیش از نصب، آزمون واقعی binding،
محدودیت claim، UID، استفادهٔ مجدد و حفظ داده پس از حذف claim باید موفق باشد.
سه PVC موجود OpenBao، PostgreSQL و Redis و قواعد admission دست‌نخورده می‌مانند.

برای filesystem مستقل ۱۶GiB، فقط **۱۴GiB** به volume
`hooshix-kafka-local-14gib` از کلاس `hooshix-kafka-local` اختصاص می‌یابد؛ باقی
برای سربار filesystem می‌ماند. مسیر دقیق `/var/lib/hooshixstorage/kafka/data`
با UID/GID `10001` و claim رزروشدهٔ `platform-data/data-kafka-0` است.
فقط node مصوب `hooshix-production-1` می‌تواند این PV محلی را mount کند؛ این
تنظیم failover نیست. برای سرور دوم نام node و اتصال هدف باید در تغییر بررسی‌شده
تطبیق داده و native proof دوباره اجرا شود؛ این bundle را روی مقصد دیگری اجرا نکنید.

از main تمیز با `Repository baseline` موفق و tree یکسان:

```bash
python3 scripts/production/build_kafka_local_volume_bundle.py \
  --native-run MAIN_BASELINE_RUN_ID \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-local-volume-20261021
```

builder به دو artifact تازه از همان اجرای موفق baseline با عمر حداکثر پنج روز
نیاز دارد: `kafka-storage` برای mount/ENOSPC/guard و `kafka-local-volume` برای
Kubernetes binding/claim/UID/reuse/Retain. fixture دوم کلاستر موقت مستقل دارد؛
برای تغییر صرفاً volume، mesh یا datastoreهای دیگر را دوباره commissioning نمی‌کند.
آزمون‌های کامل mesh برای تغییرات مربوط به همان بخش همچنان اجباری‌اند. مورد دوم از تصویر ثابت
PostgreSQL فقط به‌عنوان فرایند نوشتن/خواندن فایل با UID `10001` استفاده می‌کند؛
این شاهد تست broker، TLS یا ACL خود Kafka نیست. فایل‌های عمومی bundle خارج Git
می‌مانند. اگر نام پوشه موجود است، نام تازه بدهید و overwrite نکنید.

با VNC قابل ورود و نشست دوم SSH خصوصی باز، در PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-local-volume-20261021\run-kafka-local-volume.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-local-volume-20261021\run-kafka-local-volume.ps1' -RescueAndSecondSessionReady
```

فقط `READY` انگلیسی و رمز sudo همان‌جا لازم است؛ رمز custody/CA یا توکن لازم
نیست. خروجی `KAFKA_LOCAL_VOLUME=Passed` و JSON عمومی باید ظرفیت
`15032385536`، guard موفق، claim/node فوق و `existing_storage_host_services=Preserved`
داشته باشد. `pvc`، `kafka` و `secret_delivery` هنوز `Not run` هستند.
این گام نه فرمت/تخصیص مجدد دارد، نه restart و نه تغییر admission.

reentry پیش از ساخت claim فقط همان دو شیء سالم و تحت مالکیت نصب را می‌پذیرد؛
هیچ apply اجباری، حذف، adoption یا تغییر داده‌ای انجام نمی‌شود. وجود claim یا
دادهٔ ناشناخته موجب توقف با حفظ state است؛ پس از راه‌اندازی broker این ابزار
رزرو را دوباره اجرا نکنید. volume از نوع `Retain` است؛ حذف claim راه پاک‌کردن
داده یا مجوز استفادهٔ claim دیگری نیست. گام بعد تحویل TLS موجود از OpenBao/ESO
و workload بسته با native/admission proof مستقل است، نه بازکردن ترافیک.

### مرز نصب اولیهٔ بستهٔ Kafka

محدوده فقط یک broker/controller خالی روی LocalPV رزروشدهٔ `14Gi` است؛ بدون
topic تجاری، client برنامه/کاربر یا مسیر عمومی. تصویر باید digest ثابت و
signature/provenance/SBOM معتبر داشته باشد؛ TLS از leaf موجود Kafka استفاده
می‌کند، و STRICT Ambient، NetworkPolicy بدون ingress و admission fail-closed
حفظ می‌شوند. فرمت فقط وقتی مجاز است که پوشهٔ داده خالی و guard سالم باشد؛ دادهٔ
ناشناخته یا نیمه‌کاره حفظ و باعث توقف می‌شود. راهنمای اجرای مقصد پس از آزمون
بومی، staging روی `main` دقیق و بستهٔ مالکِ همان revision در بخش
«اجرای broker بسته» آمده است. این استقرار به‌تنهایی recovery، ظرفیت یا آمادگی
Production را ثابت نمی‌کند.

پیش‌پرواز runtime، Root pin‌شده، زنجیرهٔ دقیق دوگواهی PEM، تطابق کلید و
issuer و محدودیت‌های گواهی leaf را دوباره اعتبارسنجی می‌کند؛ هر ناسازگاری
پیش از تغییر منابع کلاستر موجب توقف با حفظ state می‌شود.
مهلت readiness نیز دست‌کم یک دقیقه از حداکثر پنجرهٔ startupProbe بیشتر است تا
شروع سرد broker پیش از پایان مهلت rollout قطع نشود.

### تحویل نخستین گواهی node به Kafka

این گام پس از `KAFKA_LOCAL_VOLUME=Passed` و پیش از ایجاد claim/broker است.
PostgreSQL و Redis بسته باید سالم باشند؛ namespace داده `restricted` و
`ambient`، mesh روی `STRICT` و ESO موجود آماده بمانند. فقط یک گواهی
`CN=kafka-node-1` با RSA-3072 و عمر ۳۰ روز از intermediate رمزدار موجود صادر
می‌شود. کاربردهای `serverAuth,clientAuth` برای ارتباط داخلی node لازم‌اند؛
این گواهی مجوز برنامه یا کاربر نیست. نام‌ها دقیقاً این چهار مورد و loopback
`127.0.0.1` هستند؛ wildcard و قابلیت CA مجاز نیست:

- `kafka.platform-data.svc`
- `kafka.platform-data.svc.cluster.local`
- `kafka-0.kafka.platform-data.svc`
- `kafka-0.kafka.platform-data.svc.cluster.local`

کلید در tmpfs خصوصی و بدون swap ساخته می‌شود. فقط leaf key/chain و Root عمومی
در مسیر `hooshix/production/kafka/node-tls` از KV-v2 و Secret نوع
`kubernetes.io/tls` به نام `platform-data/kafka-node-tls` نگهداری می‌شوند.
هویت `eso-kafka-tls` فقط همین leaf را می‌خواند؛ عمر توکن حداکثر پنج دقیقه است.
یک Role مستقل فقط TokenRequest همین نام را به ESO موجود می‌دهد؛ controller،
پورت، مجوز Secret عمومی یا تغییر CA جدیدی ایجاد نمی‌شود.

تحویل leaf، دیتابیس یا کنترلرهای موجود را ارتقا نمی‌دهد. مقایسهٔ فقط‌خواندنیِ
مرز بسته، علاوه بر مجموعهٔ فعلی، فقط دو digest دقیق PostgreSQL/operator نصب‌شده
در قرنطینهٔ موجود را می‌شناسد؛ این سازگاری مجوز نصب/rollback آن تصاویر نیست.
هر مرز ناشناخته رد می‌شود و spec/UID دیتابیس، Redis و کنترلرها قبل و بعد یکسان
می‌مانند. انتشار جدید مجموعهٔ داده به‌تنهایی وضعیت نصب VPS را تغییر نمی‌دهد.

روی checkout تمیز همان `main` بررسی‌شده، ابتدا bundle عمومی را خارج Git بسازید.
`STAGING_RUN` اجرای موفق `Platform commissioning staging` برای همین tree است؛
باید شاهد `kafka_tls_delivery` همراه با حفظ PostgreSQL و Redis فعال داشته باشد.
مقادیر `ESO_CHART` و `HELM` همان ابزارها/بستهٔ ثابت بخش ESO هستند:

```bash
python3 scripts/production/build_kafka_tls_bundle.py \
  --staging-run "$STAGING_RUN" --chart "$ESO_CHART" --helm "$HELM" \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-tls
python3 scripts/production/bootstrap_kafka_tls_operator.py \
  --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-tls --validate-only
```

VNC قابل ورود و نشست دوم SSH خصوصی را باز نگه دارید. فرمان زیر در PowerShell
از WSL استفاده می‌کند؛ `CUSTODY_DIRECTORY` را با مسیر اصلی custody موجود در WSL
جایگزین کنید، نه فایل/رمز Root آفلاین:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_kafka_tls_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-tls --custody CUSTODY_DIRECTORY --rescue-and-second-session-ready
```

به‌ترتیب رمز **موجود** custody مربوط به OpenBao، رمز **موجود** intermediate و
sudo فقط در ورودی مخفی محلی وارد می‌شوند؛ توکن GHCR یا رمز Root لازم نیست.
خروجی مورد انتظار `KAFKA_TLS_DELIVERY=Passed` و receipt عمومی در
`~/.local/share/hooshix-kafka-tls/` است. این خروجی هنوز `kafka=Not run` دارد:
این ابزار broker/PVC/ACL برنامه نمی‌سازد و Root را revoke نمی‌کند.

ورود مجدد پیش از نصب broker گواهی را اعتبارسنجی می‌کند و کلید را تعویض یا
دوباره صادر نمی‌کند. conflict مالکیت، سابقهٔ حذف leaf، کلید نامعتبر یا اعتبار
کمتر از هفت روز موجب توقف با حفظ state است؛ Secret/KV/volume را برای رفع آن
حذف نکنید. بعد از ساخت claim این ابزار bootstrap را دوباره اجرا نکنید.
تمدید/بارگذاری مجدد، بازیابی، ظرفیت و بازکردن ترافیک هنوز `Not verified` هستند.

### آزمون بومی TLS و ACL روی candidate منتشرشدهٔ Kafka

مرجع digest انتخاب‌شده `infrastructure/production/kafka/published-candidate.json`
است؛ نسخهٔ `4.2.2` از [اجرای محافظت‌شدهٔ انتشار](https://github.com/hasanjodatshandi/HooshiX/actions/runs/39468920860)
با scan صفر High/Critical و signature/provenance/SBOM معتبر پذیرفته شده است.
این فایل مجوز promotion نیست؛ شرط‌های staging و recovery همچنان برقرارند.

روی دستگاه Linux محلی با Docker Unix، OpenSSL و `/dev/shm` از نوع tmpfs اجرا کنید؛
اجراکننده root نباشد. ابتدا با توکن **read-only** و ورودی مخفی، در Docker با
`--password-stdin` وارد GHCR شوید؛ پیکربندی احراز هویت در پوشهٔ خصوصی موقت و خارج
Git باشد، سپس پس از pull، logout و حذف همان پیکربندی انجام شود. توکن در فرمان،
history یا خروجی قرار نگیرد. تصویر دقیق را بکشید؛ tag قابل‌تغییر جایگزین digest نیست:

```bash
docker --host unix:///var/run/docker.sock pull --platform linux/amd64 \
  ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private@sha256:9fe8fcb340ffe144e3ffa502df1b0694743a027df56595dd91e62b6f9e716556
python3 scripts/production/test_kafka_tls_acl_native.py --disposable
```

اجرای محلی این آزمون روی digest ثبت‌شده **Passed** است؛ اجرای VPS و staging هنوز
**Not run** است.

ابزار فقط تصویر از قبل موجود با digest ثابت را اجرا می‌کند. شبکهٔ container برابر
`none` است؛ هیچ پورت host منتشر نمی‌شود. کلیدها و CA آزمایشی یک‌روزه در RAM ساخته
می‌شوند و هیچ ارتباطی با Root/Intermediate/OpenBao واقعی ندارند. container غیر-root
با root filesystem فقط‌خواندنی، منابع محدود و دادهٔ دورریختنی اجرا و در پایان همراه
volumeهای ناشناس خودش حذف می‌شود. کلیدهای آزمایشی و raw log را نگه ندارید.

خروجی موفق باید `native_tls`، `mutual_client_auth`، `hostname_validation`،
`topic_role_group_acl`، `default_deny`، `acl_admin_denial`، `writer_revocation` و
`acks_all_idempotent_produce_consume` را `Passed` نشان دهد. خطای transport یا timeout
جایگزین رد مشخص TLS/ACL نیست. این آزمایش، custody مستقل کلیدهای سرویس‌ها، چرخش و
انقضا، mesh/NetworkPolicy، quotas، ظرفیت، staging، recovery یا نصب VPS را ثابت
نمی‌کند؛ `deployment=Not run` و `production_readiness=Not verified` باقی می‌مانند.

مرجع تنظیم‌های نسخهٔ 4.2:
[Kafka TLS](https://kafka.apache.org/42/security/encryption-and-authentication-using-ssl/)،
[Kafka ACL](https://kafka.apache.org/42/security/authorization-and-acls/).

### اجرای broker بسته پس از ادغام و staging دقیق `main`

ابتدا PR این تغییر باید review و merge شده باشد. سپس روی `main` تازه و clean،
یک native staging کامل با همهٔ dependencyها اجرا کنید تا receipt نهایی برای
همان SHA شامل `kafka_closed_runtime` باشد. اجرای دستی workflow باید شش ورودی
زیر را فعال کند؛ workflow را تا نتیجهٔ نهایی موفق دنبال کنید:

```bash
gh workflow run platform-commissioning.yml --ref main \
  -f mesh_security_upgrade=true -f eso_commissioning=true \
  -f cert_manager_commissioning=true -f cnpg_control_plane=true \
  -f barman_control_plane=true -f postgresql_quarantine=true
gh run list --workflow platform-commissioning.yml --event workflow_dispatch --limit 5
```

در staging، مهلت readiness باید از بودجهٔ startup probe بیشتر باشد. اگر broker در
مهلت به Ready نرسد، خروجی تشخیصی فقط replicaهای مطلوب/مشاهده‌شده، شرط محدودشدهٔ
ReplicaFailure، وضعیت Pod، شمار/دلیل‌های allowlist‌شدهٔ Warning و دسته‌بندی ثابت
رخداد `FailedCreate` را گزارش می‌کند؛ نام policy فقط از فهرست ثابت policyهای Kafka
و مرز داده و شناسهٔ رد فقط از پیام‌های ثابت شناخته‌شده نمایش داده می‌شوند. متن
رخداد، log و محتوای Secret نگهداری یا چاپ نمی‌شود. این گزارش جایگزین موفقیت staging نیست.
بودجهٔ job برابر ۵۵ دقیقه است تا rehearsal کامل، مهلت ۷ دقیقه‌ای broker و پاک‌سازی
fixture بدون لغو زودهنگام جا شوند؛ هیچ آزمون یا گیت امنیتی از این زمان‌بندی حذف نمی‌شود.

installer ابتدا StatefulSet را با `replicas=0` می‌سازد تا controller پیش از آماده‌شدن
مرز admission نتواند Pod بسازد. سپس فقط ValidatingPolicy موجود
`hooshix-platform-data-closed` را با `failurePolicy=Fail` و `validationActions=Deny`
نگه می‌دارد. فقط Pod دارای ServiceAccount `kafka` از قواعد bootstrap قبلی عبور
می‌کند؛ validation مستقل همان policy هر Pod با این حساب را به نام/برچسب، ownerReference
و UID دقیق همان StatefulSet و قرارداد کامل Pod می‌بندد. پیش از افزایش replica، همان
Pod با `kubectl create --dry-run=server` در حالت تازه یا درخواست PATCH دارای
`--dry-run=server` روی Pod موجود از admission واقعی API سرور عبور داده می‌شود؛
برای حالت موجود ابتدا مشخصات و مالکیت
Pod باید با قرارداد تطبیق کند. درخواست منفی با UID مالک دست‌کاری‌شده باید دقیقاً توسط
قرارداد Kafka رد شود. همگرایی مثبت حداکثر ۱۲۰ ثانیه انتظار دارد؛ مهلت هر درخواست
dry-run مثبت، کمینهٔ ۶۰ ثانیه و زمان باقی‌ماندهٔ همین بودجه است. آزمون منفی یک
درخواست با مهلت ۶۰ ثانیه است و همچنان باید پیام دقیق رد قرارداد را دریافت کند.
این مهلت‌های کلاینت برای تکمیل زنجیرهٔ بررسی admission هستند و timeout یا
`failurePolicy` وب‌هوک، بررسی امضا، provenance و SBOM را تغییر نمی‌دهند. هر خطای مثبت/منفی
توقف fail-closed می‌دهد و replica افزایش نمی‌یابد. dry-run هیچ Pod یا تغییر پایدار
ایجاد نمی‌کند. فقط پس از موفقیت هر دو آزمون، تعداد replica به ۱ می‌رسد. قاعدهٔ پایه برای
سایر Podها حفظ می‌شود و قرارداد کامل Pod نیز جداگانه در همان policy اعمال می‌ماند.
حالت موقت صفر replica با hash دقیق spec قابل‌بازیابی است؛ هر drift موجب توقف
fail-closed می‌شود. پیش‌بررسی و تطبیق مجدد policy با spec پایهٔ ثبت‌شده در bundle
انجام می‌شود؛ policy یا scope مفقود/متغیر موجب توقف fail-closed می‌شود. bundle فقط پس از ثبت
`data_admission_boundary=Passed` در receipt staging ساخته می‌شود.
آزمون منفی مالکیت در staging، علاوه بر UID نادرست، یک ownerReference دوم با UID متمایز
و بدون نقش controller می‌فرستد؛ هر دو باید با پیام دقیق قرارداد Kafka رد شوند، نه صرفاً
با خطای اعتبارسنجی عمومی Kubernetes. این dry-run مالکیت Pod موجود را تغییر نمی‌دهد.

سه `ImageValidatingPolicy` مربوط به امضای تصویر، provenance و SBOM ارجاع صریح
`spec.credentials.secrets: [hooshix-ghcr-read]` دارند؛ Secret از قبل در namespace
`kyverno` ایجاد می‌شود. Kafka Pod نیز فقط همین نام را در `imagePullSecrets` دارد؛
نصب Kafka پس از تأیید مالکیت، آن را از Secret موجود در `hooshix-secrets` به
namespace-local `platform-data` کپی می‌کند تا kubelet تصویر private را pull کند.
اجرای مجدد همین مرحله پس از به‌روزرسانی Secret مبدأ، مقدار namespace-local را نیز
به‌صورت مشروط و با `resourceVersion` تازه می‌کند؛ Secret بیگانه جایگزین نمی‌شود و
تغییر Secret مقصد immutable موجب توقف fail-closed می‌شود.
bundle فقط نام Secret را نگه می‌دارد و مقدار credential وارد bundle یا Git نمی‌شود.
با root filesystem فقط‌خواندنی، مسیرهای موقت Kafka در `/opt/kafka/logs` با سقف
`16Mi` و `/tmp` با سقف `32Mi` روی `emptyDir` حافظه‌ای قرار دارند؛ دادهٔ پایدار فقط
روی LocalPV مستقل باقی می‌ماند.
در این broker قرنطینه‌شده، `advertised.listeners` نیز روی `127.0.0.1:9092` است؛
ابزارهای محلی سلامت و quorum به DNS سرویس یا مسیر شبکهٔ برنامه وابسته نیستند.
probe با TLS و هویت موجود، فهرست topic را با heap محدود و deadline ده‌ثانیه‌ای
می‌خواند. پیش از اتصال برنامه‌ها، endpoint قابل‌دسترسی، ACL و قواعد شبکه باید در
تغییر جداگانهٔ بازکردن دسترسی برنامه‌ها تنظیم و آزموده شوند.
آزمون native همان `server.properties`، `client.properties`، اسکریپت شروع و probe
تولیدشده برای این نصب را با داده و گواهی موقت اجرا می‌کند.
فرمان‌های تأیید storage/quorum/topic نیز heap مستقل حداکثر `128Mi` دارند؛
برای JVMهای کوتاه‌عمر probe و این ابزارها، `UseSerialGC` و `TieredStopAtLevel=1`
اعمال می‌شود؛ تنظیمات JVM خود broker تغییر نمی‌کند. probe با `exec` جای shell را
می‌گیرد تا پایان مهلت، همان JVM بررسی را متوقف کند و فرزند فعال باقی نماند.
مهلت درخواست کلاینت پنج ثانیه و مهلت کل API ده ثانیه است. آزمون native قطع واقعی
probe روی پورت بسته و نبودن JVM فعال باقی‌مانده را بررسی می‌کند؛ سپس این فرمان‌ها
را سه بار هم‌زمان با دو probe، زیر همان سقف یک CPU و `2Gi` اجرا می‌کند.
در شکست exec، گزارش staging فقط شمارنده‌های ثابت OOM و تعداد بایت مصرف/سقف
cgroup را نمایش می‌دهد؛ خروجی خام پردازش یا دادهٔ خصوصی چاپ نمی‌شود.
تطبیق Service، مقدار حذف‌شدهٔ `publishNotReadyAddresses` در پاسخ API را همان
`false` می‌داند؛ مقدار `true` یا تغییر سایر فیلدها همچنان رد می‌شود. در صورت drift،
فقط نوع و نام منبع عمومی و مسیر فیلد گزارش می‌شود؛ مقدار فیلد چاپ نمی‌شود.

runner موقت GitHub پیش از fixture swap را خاموش و نبودن آن را تأیید می‌کند؛ این
شرط برای اعتبارسنجی کلیدهای موقت TLS در `tmpfs` است و تنظیم swap سرور مقصد را
تغییر نمی‌دهد.

`STAGING_RUN` را از اجرای موفقی انتخاب کنید که `head_sha` آن دقیقاً با خروجی
`git rev-parse HEAD` روی `main` برابر است. با همان chart تأییدشدهٔ ESO 2.12.0 و
Helm 4.2.4، bundle عمومی را بیرون Git بسازید؛ پوشه باید تازه و خالی از قبل
باشد:

```bash
python3 scripts/production/build_kafka_runtime_bundle.py \
  --staging-run "$STAGING_RUN" --chart "$ESO_CHART" --helm "$HELM" \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-runtime
```

برای **VPS فعلی** که همان PostgreSQL خالی و قرنطینه‌شدهٔ اولیه را نگه داشته است،
گزینهٔ `--retained-database-quarantine` را به فرمان ساخت bundle اضافه کنید.
این گزینه فقط دو digest ثابتِ ثبت‌شده در `install_kafka_tls.RETAINED_DATABASE_IMAGES`
را برای تطبیق دقیق مرز قبلی حفظ می‌کند؛ image دلخواه نمی‌پذیرد و PostgreSQL را
ایجاد، ارتقا، rollback یا reset نمی‌کند. روی سرور تازه با مجموعهٔ جاری PostgreSQL
این گزینه را ندهید. در هر دو حالت installer باید کل spec مرز قبلی را دقیقاً تطبیق
دهد؛ drift مجاز نمی‌شود و فقط استثنای دقیق Pod جدید Kafka به همان مرز اضافه می‌شود.
این سازگاریِ قرنطینه، اصلاح امنیتی/بازیابی/ظرفیت دیتابیس یا مجوز Production نیست.

در PowerShell، ابتدا فقط صحت فایل‌های public bundle را بررسی کنید، سپس VNC
قابل ورود و یک نشست دوم SSH خصوصی را باز نگه دارید و runner را با همان پوشه
اجرا کنید:

آزمون بسته، همهٔ ماژول‌های تحویلی را به ترتیب runner در یک Python تازه با `-I`
و بیرون checkout بارگذاری می‌کند؛ سپس `--help` نصاب را بدون اتصال VPS اجرا می‌کند.
نصاب روی VPS به rendererهای checkout یا `PYTHONPATH` میزبان وابسته نیست.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-runtime\run-kafka-runtime.ps1' -ValidateOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Coder\Downloads\HooshiX-kafka-runtime\run-kafka-runtime.ps1' -RescueAndSecondSessionReady
```

در prompt فقط `READY` انگلیسی و رمز sudo را وارد کنید؛ توکن GHCR یا رمز
OpenBao/Root/CA لازم نیست. موفقیت باید `KAFKA_RUNTIME=Passed`، یک broker
داخلی روی همان Retain volume، `business_topics=None`، `application_ingress=CLOSED`
و `public_access=CLOSED` نشان دهد؛ receipt عمومی کنار پوشهٔ bundle ذخیره می‌شود.
فرمت اولیه فقط یک‌بار و فقط روی پوشهٔ خالیِ تأییدشده انجام می‌شود؛ دادهٔ موجود
هرگز reset/reformat نمی‌شود. TLS/Root تغییر نمی‌کند، host service restart یا
سرور reboot نمی‌شود. خطای اجرا state را حفظ می‌کند؛ ابزار رزرو volume یا تحویل
TLS را پس از ایجاد claim تکرار نکنید.

پس از نصب نیز recovery/rebuild واقعی از GitOps، replay/dedup evidence، capacity،
certificate renewal، topic/client credentials/ACL، observability و complete-stack
readiness همچنان `Not verified` هستند. ترافیک برنامه تا عبور این gateها بسته
می‌ماند.

### تحویل گواهی مستقل کلاینت‌های Kafka

پنج مصرف‌کنندهٔ فعلی Kafka عبارت‌اند از `authorization-service`،
`conversation-service`، `identity-service`، `notification-service` و `web-bff`.
هر سرویس فقط گواهی clientAuth و کلید مستقل خودش را از مسیر اختصاصی OpenBao
و SecretStore موجود همان سرویس در `platform-apps` دریافت می‌کند. گواهی node،
Root و intermediate موجود جایگزین نمی‌شوند. تحویل credential به معنی ایجاد
topic، اعطای ACL، بازکردن شبکه یا استقرار برنامه نیست؛ broker همچنان بسته است.
کلیدهای خصوصی فقط در حافظه/tmpfs بدون swap، OpenBao و Secret مدیریت‌شدهٔ ESO
قرار می‌گیرند؛ Git، bundle عمومی، خروجی ترمینال و فایل محلی محل نگهداری آن‌ها نیستند.
اجرای دوباره نباید کلید موجود را بچرخاند یا تاریخچهٔ حذف‌شده و منابع بیگانه را تصاحب کند.
| سرویس | Secret در `platform-apps` |
| --- | --- |
| `authorization-service` | `authorization-kafka` |
| `conversation-service` | `conversation-kafka` |
| `identity-service` | `identity-kafka` |
| `notification-service` | `notification-kafka` |
| `web-bff` | `web-bff-kafka` |

مسیر هر سرویس `hooshix/data/production/<service>/kafka-tls` و subject آن
`CN=hooshix-<service>-kafka` است؛ principal ادمین/node به برنامه داده نمی‌شود.
هر leaf از نوع RSA 3072/SHA-256، غیر CA، فقط clientAuth و دارای اعتبار حداکثر
۳۰ روز است. ابزار حداقل هفت روز اعتبار باقی‌مانده می‌خواهد؛ تمدید خودکار هنوز
**Not run** است و اجرای مجدد جایگزین عملیات تمدید نیست.

Secret از نوع Opaque و با کلیدهای configtree استاندارد Spring Boot ساخته می‌شود:
`spring.kafka.security.protocol=SSL`، دو store از نوع `PEM`، کلید PKCS#8،
زنجیرهٔ leaf+intermediate، Root عمومی و اعتبارسنجی hostname برابر `HTTPS`.
اسرار مستقل هر سرویس توسط SecretStore موجود `hooshix-<service>` خوانده می‌شوند؛
ServiceAccount، Role، policy و دسترسی شبکهٔ تازه ایجاد نمی‌شود. مالک SecretStore
موجود باید دقیقاً `hooshix-eso-commissioning`، مطابق نصاب ESO، باقی بماند؛
برچسب مالکیت آن تغییر داده نمی‌شود. فایل configtree
این مقادیر را در mount موجود هر سرویس مصرف خواهد کرد؛ آزمون واقعی configtree با
Spring Boot 4.1.0 نگاشت PEM و hostname validation را برای producer، consumer و
admin بررسی می‌کند. اجرای واقعی برنامه هنوز
جزو این مرحله نیست. منابع بیگانه، drift، Secret بدون مالک ESO معتبر و تاریخچهٔ
حذف‌شدهٔ OpenBao موجب توقف می‌شوند؛ دادهٔ موجود هرگز reset یا خودکار rotate نمی‌شود.

قبل از ساخت bundle، آزمون یک‌بارمصرف با تصویر دقیق منتشرشده اجرا می‌شود:

```bash
python3 scripts/production/test_kafka_client_tls_native.py --disposable
```

این آزمون فقط Docker محلی، Root/issuer مصنوعی و tmpfs را مصرف می‌کند؛ هر پنج
client باید TLS معتبر داشته باشند، درخواست مدیریت ACL آن‌ها با
`ClusterAuthorizationException` رد شود و فایل خصوصی موقتی باقی نماند.
rehearsal کامل Calico/Ambient/OpenBao/ESO نیز تحویل پنج Secret، رد خواندن متقابل،
رد نوشتن، انقضای محدود token، revoke، تکرار بدون صدور/تغییر نسخه و عدم نشت در
لاگ‌ها را اثبات می‌کند. مرحلهٔ client همان plan تأییدشدهٔ مرحلهٔ broker را، با
defaultهای API همان محیط، دریافت می‌کند؛ مقایسهٔ دقیق قرارداد حذف یا سست نمی‌شود.
این شواهد جایگزین نتیجهٔ VPS نیستند.

فقط روی checkout تمیز `main` و با `STAGING_RUN` موفقِ همان SHA شامل
`kafka_client_tls_delivery`، bundle عمومی را بیرون Git بسازید؛ Helm و chart همان
نسخه‌ها و مسیرهای تأییدشدهٔ بخش قبل هستند:

```bash
python3 scripts/production/build_kafka_client_tls_bundle.py \
  --staging-run "$STAGING_RUN" --chart "$ESO_CHART" --helm "$HELM" \
  --retained-database-quarantine \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-client-tls
python3 scripts/production/bootstrap_kafka_client_tls_operator.py \
  --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-client-tls --validate-only
```

گزینهٔ `--retained-database-quarantine` فقط برای VPS فعلی با همان PostgreSQL
خالی اولیه است؛ برای نصب جدید با baseline جاری حذفش کنید. این گزینه image یا
workload موجود را تغییر نمی‌دهد. صدور فقط پس از تأیید کل مجموعهٔ منابع و کلیدهای
موجود شروع می‌شود؛ CAS صفر مانع overwrite است و state جزئی برای ادامه حفظ می‌شود.

با VNC قابل ورود و نشست دوم private SSH باز، روی Windows اجرا کنید. مقدار
`CUSTODY_DIRECTORY` را مسیر **همان custody موجود همان نصب** قرار دهید؛ Root یا
custody جدید نسازید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_kafka_client_tls_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-client-tls --custody CUSTODY_DIRECTORY --rescue-and-second-session-ready
```

به‌ترتیب `READY` انگلیسی، رمز موجود custody، رمز موجود intermediate و sudo را
فقط در ورودی‌های مخفی وارد کنید؛ توکن GHCR لازم نیست. نتیجه باید
`KAFKA_CLIENT_TLS_DELIVERY=Passed` و پنج credential مستقل با دسترسی برنامه و
عمومی `CLOSED` باشد. رسید عمومی در `~/.local/share/hooshix-kafka-client-tls/`
ثبت می‌شود و کلید خصوصی در رسید نیست. نصب فقط پس از این خروجی روی مقصد
تأیید می‌شود؛ تا آن زمان **Not verified** است. برای بررسی وضعیت عمومی بدون
چاپ Secret می‌توانید از `kubectl -n platform-apps get externalsecrets` استفاده کنید.
در خطا، state را حفظ کنید؛ broker، PVC، Root، node certificate و مقادیر موجود
را حذف یا بازتولید نکنید. topic/ACL، دسترسی برنامه، تمدید، recovery و ظرفیت
همچنان دروازه‌های مستقل بعدی هستند.

منابع قرارداد configtree/PEM:
[Spring Boot Kafka properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)،
[Kafka 4.2 PEM client configuration](https://kafka.apache.org/42/configuration/admin-configs/).

### قرارداد topic و ACL Kafka

سلامت Kafka باید هنگام اجرای ابزارهای مدیریتی محدود نیز پایدار بماند. خروجی
موفق ابزار بدون کنترل restart کانتینر و حفظ هویت broker/PVC، شاهد تکمیل عملیات
نیست. کمبود ظرفیت با حذف probe، غیرفعال‌کردن TLS یا بازکردن دسترسی برنامه حل نمی‌شود.

در قرارداد فعلی، liveness بدون اجرای JVM، اتصال محلی TLS را با گواهی موجود node،
CA معتبر و تطبیق IP بررسی می‌کند. startup و readiness همچنان API واقعی Kafka را
با احراز هویت بررسی می‌کنند. محدودیت CPU/RAM، مهلت‌ها و آستانه‌های خطا تغییر نمی‌کنند.
بستهٔ topics/ACL در صورت مشاهدهٔ دقیق قرارداد قدیمی، فقط liveness را با یک توقف
کنترل‌شدهٔ broker مهاجرت می‌دهد. تصویر، TLS، cluster identity، UIDِ StatefulSet و PVC،
topicها و ACLهای موجود حفظ می‌شوند. journal تغییرناپذیر
`platform-data/hooshix-kafka-health-v1`، وضعیت پیش از مهاجرت و hash قرارداد را نگه
می‌دارد؛ هنگام توقف جزئی آن را حذف نکنید. ادامه فقط با همان بسته و journal معتبر
مجاز است؛ دادهٔ غیرخالی فرمت یا ریست نمی‌شود. پیش از فعال‌سازی دوباره، آزمون پذیرش
مثبت/منفی و پس از آن آمادگی و هویت داده بررسی می‌شوند. این قابلیت تا دریافت رسید
موفق مقصد، نصب‌شده یا تأییدشده روی VPS محسوب نمی‌شود.
نصاب patch کامل سیاست را با compare-and-swap از stdin تحویل می‌دهد؛ سیاست
بزرگ یا مقدار Secret نباید به آرگومان فرمان یا خروجی عیب‌یابی منتقل شود.
رسید CI باید از اجرای کامل و موفق همان منبع به دست آید؛ اجرای لغوشده یا خروجی
جزئی، حتی پس از موفقیت بعضی آزمون‌ها، مجوز ساخت بسته یا نصب روی VPS نیست.
بودجهٔ کل job آزمون کامل زیرساخت ۷۵ دقیقه است؛ این سقف، مهلت داخلی سرویس‌ها،
آستانهٔ probeها یا گیت‌های پذیرش و امنیت را افزایش یا غیرفعال نمی‌کند.

گام بعد فقط topicهای versioned موجود در قرارداد برنامه و ACLهای literal پنج
هویت تحویل‌شده است. این گام مجوز wildcard، مدیریت broker، تغییر credential یا
بازکردن مسیر برنامه/اینترنت نیست. اجرای مقصد و آزمون دسترسی هنوز **Not run** است.

منبع اجرایی `scripts/production/kafka_topic_acl.py` است؛ نام topic و consumer
group با تنظیمات فعلی هر پنج سرویس آزمون تطبیق دارد. سه topic اصلی
`hooshix.identity.erasure.command.v1`، `hooshix.identity.erasure.receipt.v1` و
`hooshix.identity.tenant.lifecycle.v1` با پسوند `.DLT` برای هرکدام ساخته می‌شوند.
هر شش topic یک partition، RF1/minISR1، cleanup از نوع delete و unclean election
خاموش دارند. retention اصلی ۳۵ روز و DLT چهارده روز است؛ این retention جایگزین
شاهد replay/dedup پایدار در دیتابیس، ظرفیت دیسک یا backup نیست.

| هویت | خواندن | نوشتن |
| --- | --- | --- |
| identity | command و receipt؛ groupهای مستقل خودش | command، receipt، lifecycle؛ DLTهای command و receipt |
| conversation | command و lifecycle؛ groupهای مستقل خودش | receipt؛ DLTهای command و lifecycle |
| authorization، notification، web-bff | command؛ group مستقل هر سرویس | receipt و DLT مربوط به command |

فقط `Read`/`Write`/`Describe` لازم روی topic literal و `Read` روی هفت group literal
اعطا می‌شود. هیچ `Create`، `Delete`، `Alter`، `All`، ACL مدیریتی یا resource
wildcard/prefix به برنامه‌ها داده نمی‌شود. به Compromised Password نیز مجوزی داده
نمی‌شود. هویت `kafka-node-1` مسیر محلیِ مدیریتی فعلی است و به برنامه‌ها تحویل نمی‌شود.

نصاب کل inventory topic و ACL را قبل از اولین تغییر بررسی می‌کند. در اجرای اول،
broker باید بدون topic/ACL قبلی باشد؛ منبع هم‌نام حتی با تنظیم ظاهراً درست adopt
نمی‌شود. سپس ConfigMap تغییرناپذیر `platform-data/hooshix-kafka-topic-acl-v1`
با hash قرارداد، cluster identity، UIDِ StatefulSet و PVC ثبت می‌شود. ادامهٔ اجرای
جزئی فقط با همین journal و subset دقیق قرارداد ممکن است. اختلاف تنظیم، مجوز اضافی،
journal بیگانه یا cluster جدید متوقف می‌شود؛ حذف topic، کاهش retention، overwrite
یا rollback خودکار انجام نمی‌شود. journal را برای عبور از خطا حذف نکنید.

ابتدا آزمون پنج هویت واقعیِ مصنوعی روی همان digest و staging امضاشده باید موفق
باشد. ارسال/دریافت مثبت، رد topic/group بیگانه، رد مدیریت ACL و اجرای مجدد بدون
تغییر، شرط ساخت bundle هستند؛ پیام مصنوعی وارد topicهای VPS نمی‌شود.

از checkout تمیزِ دقیق `main` و رسید staging همان revision، با chart/Helm تأییدشده:

```bash
python3 scripts/production/build_kafka_topic_acl_bundle.py \
  --staging-run "$STAGING_RUN" --chart "$ESO_CHART" --helm "$HELM" \
  --retained-database-quarantine \
  --output /mnt/c/Users/Coder/Downloads/HooshiX-kafka-topic-acl
python3 scripts/production/bootstrap_kafka_topic_acl_operator.py \
  --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-topic-acl --validate-only
```

گزینهٔ retained فقط برای PostgreSQL قرنطینهٔ قبلی همین VPS است؛ در نصب جدید با
baseline فعلی حذفش کنید. هر چهار guard، K3s، OpenBao unsealed، ESO و broker باید
سالم باشند و هر پنج ExternalSecretِ client، Ready و متعلق به نصب باشند. کلیدها
فقط در حافظه برای تطبیق بررسی می‌شوند؛ صادر، تعویض یا چاپ نمی‌شوند.

با VNC قابل ورود و نشست دوم SSH باز، در PowerShell اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_kafka_topic_acl_operator.py --bundle /mnt/c/Users/Coder/Downloads/HooshiX-kafka-topic-acl --rescue-and-second-session-ready
```

فقط `READY` انگلیسی و sudo در پنجرهٔ محلی لازم است؛ رمز custody/intermediate/Root
یا GHCR نمی‌خواهد. نتیجهٔ مورد انتظار `KAFKA_TOPIC_ACL=Passed` است. رسید عمومی
در `~/.local/share/hooshix-kafka-topics/` ذخیره می‌شود. نصاب پیش از دریافت با
Windows SCP، فایل مقصد خالی را با ایجاد انحصاری، مالکیت همان کاربر و مجوز `0600`
داخل پوشهٔ `0700` می‌سازد؛ SCP در این مسیر فقط محتوای همان فایل را می‌نویسد.
پس از دریافت، کنترل فایل معمولی، مالک، مجوز، تعداد لینک و اندازه همچنان الزامی
است؛ hash برنامه، revision و تمام نتیجه نیز بررسی می‌شوند. انتقال موفق فایل
به‌تنهایی شاهد نصب نیست؛ تا رسید معتبر مقصد، عملیات **Not verified** است.
در قرارداد قدیمی فقط مهاجرت liveness بالا یک restart محدود دارد؛ ایجاد topic/ACL
به‌تنهایی restart نمی‌کند. image، PVC، cluster ID، گواهی‌ها، advertise loopback
و مرزهای admission/شبکه حفظ می‌شوند.

رسید مقصد سرور اول در ۲۰۲۶-۱۰-۲۸ از `main@ac00a244d3290ac534a86c3f3afb9efa7ee0cf81`
اعتبارسنجی شد: مهاجرت health، شش topic، ۴۷ ACL، پنج هویت و هفت group، همگی
`Passed`؛ state موجود `Preserved` و دسترسی برنامه/عمومی `CLOSED` است. رسید عمومی:
`~/.local/share/hooshix-kafka-topics/receipt-7a7e383f418e44d9a26c5740b7a4b46e.json`.
این رسید شاهد ظرفیت، quota، recovery یا آمادگی Production نیست و برای سرور دوم
اعتبار اجرایی ایجاد نمی‌کند.

پس از این گام هنوز quota، ظرفیت/بازیابی، endpoint داخلی قابل‌دسترسی و مجوز شبکهٔ
هر برنامه دروازه‌های مستقل هستند؛ برنامه/اینترنت همچنان `CLOSED` می‌ماند و هیچ
ترافیک واقعی یا ادعای Production مجاز نیست.

منابع نسخهٔ 4.2:
[Kafka authorization/ACL](https://kafka.apache.org/42/security/authorization-and-acls/)،
[topic configuration](https://kafka.apache.org/42/configuration/topic-configs/).

پس از آن ادامهٔ مشترک:

1. ESO نصب‌شده؛ تحویل اسرار واقعی مستقل هر سرویس و آزمون مصرف‌کننده باقی است.
2. دسترسی عملیاتی محدود، backup ساعتی، recovery و audit امن خارج میزبان؛ root
   اولیه فقط پس از جایگزین معتبر و آزمون recovery لغو شود.
3. JIT واقعی: expiry/revoke/disconnect، سپس حذف دسترسی دائمی با مسیر نجات کارا.
4. PostgreSQL/CNPG/Barman، Redis و Kafka به backup/restore و ظرفیت نیاز دارند؛
   broker بستهٔ Kafka ترافیک برنامه را مجاز نمی‌کند.
5. GitOps/Argo CD، انتشار هفت artifact، شش سرویس و frontend، migration و اسرار.
6. edge/TLS/WAF، مسیر frontend/BFF و دامنهٔ همان نصب؛ origin bypass ممنوع.
7. observability/alert و monitor مستقل خاموشی host.
8. browser/MFA/tenant/Conversation/provider، complete-stack capacity و cold DR؛
   فقط پس از دروازه‌ها و تأیید مالکان ترافیک Production باز شود.

این بخش‌ها هنوز دستور اجرایی تکمیل‌شده ندارند و **Not verified** هستند. با هر
تغییر Stage10 همین بخش با روش نهایی، commands مقصد، ورودی‌ها، خروجی مورد انتظار،
کاربرد روزمره و recovery لازم تکمیل می‌شود؛ مرحلهٔ اجرا‌نشده موفق معرفی نمی‌شود.

در انتظار طولانی CI/workflow، دستیار طبق بخش «Autonomous GitHub wait» در
`AGENTS.md` با فاصلهٔ معمول حدود پنج دقیقه نتیجه را بررسی می‌کند، گزارش‌های
تکراریِ پیشرفت نمی‌دهد و پس از پایان، نتیجه را اعلام و کار مجاز بعدی را ادامه
می‌دهد. فقط تأیید واقعاً لازم مالک یا نبود اختیار، نیازمند توقف است؛ شرط
موفقیت CI و بازبینی پیش از merge همچنان برقرار است.

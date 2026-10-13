# راهنمای زندهٔ استقرار Production — Stage 10

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

## وضعیت پوشش فعلی

پایهٔ بررسی‌شده: `main@5fcaca8540875d742e4194ab215054610d872e9b`، ۲۰۲۶-۱۰-۱۲.
این SHA مرجع این ویرایش است، نه الزام استفادهٔ دائمی از نسخهٔ قدیمی.
سرور اول OpenBao نصب‌شده، initialized و unsealed دارد؛ recovery ایزولهٔ محلی
با سهم‌های اصلی `Passed` است. نصب ESO و Ready بودن شش SecretStore روی مقصد
`Passed` است. تأیید نسخه/retention مقصد backup، scheduler ساعتی، تحویل اسرار واقعی،
JIT کامل، داده‌ها و برنامه‌های Production هنوز کامل نشده‌اند.
Stage10 و Production readiness همچنان `Not verified` هستند.

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
dependencyهای Barman از lock رسمی همان source بررسی می‌شود؛ نصب بدون hash،
کاهش سطح گیت یا استفاده از tag متغیر مجاز نیست. اسکن مجموعهٔ candidate و آزمون
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

### انتشار خصوصی candidateهای PostgreSQL

انتشار چهار candidate یک مجموعهٔ واحد است و فقط از workflow محافظت‌شدهٔ
`Production release evidence` روی `main` انجام می‌شود. ساخت و اسکن PR مجوز نصب
نیست؛ publication، staging و پذیرش مقصد شواهد جداگانه دارند. انتشار جدید این
چهار package باید خصوصی بماند و استثنای عمومی‌بودن packageهای قدیمی را به ارث
نمی‌برد. اجرای انتشار به رمز VPS، کلید Root یا فایل custody نیاز ندارد.

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

پس از آن ادامهٔ مشترک:

1. ESO نصب‌شده؛ تحویل اسرار واقعی مستقل هر سرویس و آزمون مصرف‌کننده باقی است.
2. دسترسی عملیاتی محدود، backup ساعتی، recovery و audit امن خارج میزبان؛ root
   اولیه فقط پس از جایگزین معتبر و آزمون recovery لغو شود.
3. JIT واقعی: expiry/revoke/disconnect، سپس حذف دسترسی دائمی با مسیر نجات کارا.
4. PostgreSQL/CNPG/Barman، Redis و Kafka با backup/ACL/TLS/restore مصوب.
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

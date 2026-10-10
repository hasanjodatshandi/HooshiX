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

پایهٔ بررسی‌شده: `main@467acaed9a2c56ad6e5f14d08659ebb3c3362ed3`، ۲۰۲۶-۱۰-۱۰.
این SHA مرجع این ویرایش است، نه الزام استفادهٔ دائمی از نسخهٔ قدیمی.
سرور اول OpenBao نصب‌شده، initialized و unsealed دارد؛ recovery ایزولهٔ محلی
با سهم‌های اصلی `Passed` است. تأیید نسخه/retention مقصد backup، scheduler ساعتی،
ESO، JIT کامل، داده‌ها و برنامه‌های Production هنوز کامل نشده‌اند.
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
| ESO/JIT/داده/GitOps/برنامه/edge | کامل نشده | مراحل بعد این راهنما |

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

## ۱۳. رسیدن به نقطهٔ فعلی و مراحل بعد

### احراز هویت محدود OpenBao؛ مرحلهٔ اجراشده

[راهنمای اجرای شش هویت](production-openbao-auth-fa.md) شامل دستور owner-local،
ورودی‌ها، expected result، سطح ACL و recovery است. ابزار از store/custody موجود
استفاده می‌کند؛ init/restart/root revocation تکرار نمی‌شود. هر `eso-<service>`
فقط خواندن مسیر `hooshix/data/production/<service>/*` را با token حداکثر پنج
دقیقه دارد. TokenReview واقعی با JWT تازه و audience دقیق، بدون reviewer
دائمی انجام می‌شود. SecretStore فقط قرارداد آماده‌شده است؛ ESO یا secret
واقعی هنوز نصب/همگام نشده است. اجرای مالک روی VPS اول `OPENBAO_SCOPED_AUTH=Passed`
گزارش کرده است؛ رسید عمومی `auth-481ef67e63ce4f7d97e6a6a3c60e562a.json`
خارج Git نگهداری می‌شود. این نتیجه فقط scoped auth را اثبات می‌کند، نه ESO،
تحویل اسرار، audit خارج میزبان یا آمادگی Production.
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

تا انتشار امضاشده، آزمون native تحویل شش هویت و بستهٔ نصب بازبینی‌شده آماده
نشده‌اند، دستور نصب ESO روی VPS وجود ندارد. نصب بعدی باید namespace-scoped
باشد، cluster stores/push secrets و TokenRequest عمومی را غیرفعال کند؛ فقط
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

`scripts/production/render_eso_candidate.py` chart دارای checksum مصوب را با
Helm `4.2.4` برای Kubernetes `1.35.6` render می‌کند. خروجی عمومی فاقد credential
است و سه Deployment تک‌نمونهٔ controller، webhook و cert-controller دارد؛
تصویر هر سه به digest مصوب تبدیل می‌شود. هر container غیر-root است، همهٔ
capabilityها حذف می‌شوند، filesystem فقط‌خواندنی و seccomp از نوع
`RuntimeDefault` است. منابع هر container: request برابر `50m/64Mi` و limit
برابر `500m/256Mi`، با فضای موقت `16Mi/64Mi`؛ liveness/readiness الزامی است.

controller فقط `platform-apps` را پردازش می‌کند؛ cluster stores و push secrets
خاموش‌اند و CRD/مجوز ClusterGenerator صادر نمی‌شود. مجوز ساخت TokenRequest فقط برای شش
`eso-<service>` موجود است. دسترسی Secret مربوط به cert-controller نیز به
همین namespace محدود است؛ مجوزهای cluster فقط metadata گواهی CRD/webhook
را پوشش می‌دهند. webhookها فقط همین namespace را انتخاب می‌کنند، با
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
و environment مصوب انجام می‌شود. مثال زیر **فعلاً اجرا نشود**؛ گیت‌های
baseline باید روی revision جدید موفق شوند و PR با بازبینی merge شود.
انتشار صرفاً candidate امضاشده می‌سازد؛ پذیرش digest در admission و نصب هنوز
مرحلهٔ جداگانه و اجرا‌نشده هستند:

```bash
gh workflow run production-release.yml --ref main \
  -f release_kind=patched-platform-candidate
```

انتشار، تمام چهار تصویر را می‌سازد و اسکن می‌کند؛ سپس به packageهای خصوصی
`platform-<eso|openbao|istiod|cni>-patched-private` در GHCR همان حساب می‌فرستد.
config digest پس از push کنترل می‌شود؛ SBOM/scan برای digest واقعی registry
دوباره بررسی می‌شود. قبل از هر push، OpenBao بازسازی‌شده با همان config digest
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
تصویب digestهای امضاشده، استقرار همچنان مسدود است. اجرای دوبارهٔ CI جدید را
در PR194 بررسی کنید؛ موفقیت قبلی چهار build به‌تنهایی این wiring جدید را
تأیید نمی‌کند.

پس از آن ادامهٔ مشترک به ترتیب وابستگی‌ها:

1. ESO؛ استفاده از شش هویت محدود موجود و تحویل اسرار مستقل هر سرویس.
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

در انتظار طولانی CI/workflow، دستیار لینک و وضعیت را می‌دهد و turn را پایان
می‌دهد؛ مالک پایان را اعلام می‌کند و دستیار سپس یک بار نتیجه را بررسی می‌کند.
این روش انتظار، شرط CI/بازبینی قبل از merge را تغییر نمی‌دهد.

# فضای محدود OpenBao روی VPS

بستهٔ ادامهٔ این بنیاد، محافظ هویت mount پیش از K3s و در زمان اجرا و کاندیدای
local PV است؛ نصب محافظ و اتصال واقعی Kubernetes از وجود کد یا نتیجهٔ CI
استنتاج نمی‌شود.

مالک ساخت فضای حداکثر ۸GiB روی VPS فعلی را تأیید کرده است. این تغییر فقط
بنیاد storage است؛ OpenBao، Secret، PV یا workload نصب نمی‌کند و SSH، پورت
MCP ‏۲۲۲۲، ایمیل و پارتیشن‌های موجود را تغییر نمی‌دهد.

فایل‌سیستم ext4 مستقل داخل یک فایل از پیش رزروشده روی دیسک VPS قرار می‌گیرد.
سقف فایل ۸GiB است؛ فضای قابل استفاده به علت metadata و reserve کمتر خواهد بود.
هیچ فایل حجیمی روی Windows/WSL ساخته نمی‌شود. آزمون پرشدن فقط با نمونهٔ کوچک
در runner موقت CI اجرا می‌شود. اجرای واقعی به رمز sudo در پنجرهٔ محلی نیاز دارد.

## اجرای واقعی پس از موفقیت CI و merge

ابزار عمومی `provision_openbao_storage.py` فقط چهار package فعلی VPS را قبول
می‌کند: util-linux ‏`2.41.3-3ubuntu2.2`، e2fsprogs ‏`1.47.2-3ubuntu4`،
systemd ‏`259.5-0ubuntu3.4` و python3 ‏`3.14.3-0ubuntu2`.
اینها در بررسی فقط‌خواندنی دیده شدند؛ ابزار package نصب یا downgrade نمی‌کند.
اگر به‌روزرسانی شده‌اند، version review لازم است، نه خاموش‌کردن gate.
hostname هدف فعلی نیز `mail.hooshix.com` است؛ اجرای اشتباهی روی developer host
یا میزبان دیگر رد می‌شود. مسیر تازه و root-owned ‏`/var/lib/hooshixstorage`
عمداً جداست؛ مسیر موجود `/var/lib/hooshix` متعلق به حساب مدیر است و برای
storage قابل اعتماد بازنویسی یا تغییر مالکیت نمی‌شود.

در Windows PowerShell، این **دو فایل عمومی از revision merge‌شده و CI موفق**
را در پوشهٔ تازهٔ محلی کپی کنید؛ فایل credential لازم نیست:

```powershell
$src = '\\wsl.localhost\Ubuntu\home\coder\workspace\Hooshix-stage10-root\scripts\production'
$dst = Join-Path $env:TEMP ('HooshiX-storage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dst | Out-Null
Copy-Item -LiteralPath (Join-Path $src 'provision_openbao_storage.py'), (Join-Path $src 'run-openbao-storage.ps1') -Destination $dst
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$dst\run-openbao-storage.ps1" -InstallApproved8GiB
```

`Bypass` فقط برای همان process و فایل محلی بازبینی‌شده است؛ policy سراسری عوض
نمی‌شود. اگر سیاست سازمان مانع است، با مسئول سیستم حل کنید. alias فعلی
`hooshix-server` و اتصال خصوصی قبلی باید برقرار باشد. رمز sudo را فقط همان
پنجره وارد کنید. خروجی عمومی باید `storage_foundation: Passed` و
`backing_bytes: 8589934592` بدهد؛ `PUBLIC_RECEIPT` مسیر رسید کوچک است.
این launcher فقط سورس عمومی را می‌فرستد، hash همان bytes را پیش از اجرای root
بررسی می‌کند و هیچ رمز، کلید یا kubeconfig نمی‌خواند/کپی نمی‌کند.
Python در حالت isolated (`-I`) اجرا می‌شود تا module یا PYTHONPATH حساب کاربر
وارد اجرای root نشود؛ خواندن سورس ارسالی نیز حداکثر ۳۲KiB است.

برای plan/بررسی فقط‌خواندنی، همان دستور را **بدون** `-InstallApproved8GiB`
اجرا کنید. قبل از نصب، reserve حداقل ۳۰٪ بررسی می‌شود؛ پس از نصب، mount،
backing، اندازه، flags، data owner و enable/active واحد بررسی می‌شوند.

## مسیرها و استفادهٔ بعدی

| مورد | مسیر |
| --- | --- |
| فایل رزرو ۸GiB، root/0600 | `/var/lib/hooshixstorage/openbao.ext4` |
| رسید هویت filesystem، root/0600 | `/var/lib/hooshixstorage/openbao-storage.json` |
| mount مستقل | `/var/lib/hooshixstorage/openbao` |
| دادهٔ UID/GID 10001، mode 0700 | `/var/lib/hooshixstorage/openbao/data` |
| واحد boot | `var-lib-hooshixstorage-openbao.mount` |

اجرای مجدد موفق همان filesystem را بررسی می‌کند و داده را فرمت نمی‌کند.
تا تکمیل guard واقعی mount-loss/قبل از K3s، local PV/StorageClass با Retain و
node affinity از مسیر GitOps و سایر gateها، workload به این مسیر وصل نکنید.
این mount به‌تنهایی guard زمان اجرا نیست. Kubelet با fsGroup=10001 ممکن است
مجوز پوشهٔ داده را از 0700 به 2770 (گروه اختصاصی و setgid) تغییر دهد؛ این دو
و حالت انتقالی 0770 فقط برای همان UID/GID مجازند؛ مجوز سایر کاربران ممنوع است.

## نصب محافظ پس از CI موفق

در ۲۰۲۶-۱۰-۰۵ ساخت واقعی این filesystem با رسید عمومی `Passed` و
`backing_bytes=8589934592` تأیید شد؛ reboot و اتصال Kubernetes هنوز
`Not verified` است. برای نصب محافظ دوباره فایل ۸GiB ساخته نمی‌شود.

از همان دستور کپی دو فایل عمومیِ **main با CI موفق** در بخش بالا استفاده کنید؛
سپس به‌جای گزینهٔ ساخت فضا این دستور را اجرا کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$dst\run-openbao-storage.ps1" -InstallApprovedGuard
```

این اقدام باید با اطلاع مالک انجام شود: اگر هویت storage از دست برود، K3s
متوقف می‌شود و مدیریت کلاستر در دسترس نخواهد بود. نصب، restart/reboot نمی‌کند؛
SSH، MCP روی ۲۲۲۲، ایمیل، پارتیشن و داده‌های فعلی عوض نمی‌شوند. رمز sudo
فقط همان پنجره وارد می‌شود. source پس از بررسی hash همان bytes در مسیر root-only
`/var/lib/hooshixstorage/openbao-storage-guard.py` نصب می‌شود؛ فایل متعارض
جایگزین نمی‌شود. ابتدا guard سالم شروع می‌شود، سپس dependency زیر اضافه می‌شود:

```text
mount → hooshix-openbao-storage-guard.service → k3s.service
```

فایل dependency: `/etc/systemd/system/k3s.service.d/30-hooshix-openbao-storage.conf`.
شروع K3s بعدی منتظر `READY=1` محافظ می‌ماند. هر پنج ثانیه یک بررسی بدون retry
با deadline کل ۱۲ ثانیه و deadline دوثانیه‌ای command انجام می‌شود. mount،
UUID، inode/device و geometry فایل backing، flags، اندازه و owner بررسی می‌شوند؛
timeout/خطا موفق نیست. watchdog سی‌ثانیه‌ای هنگ والد را هم fail-closed می‌کند.
مصرف guard به ۵٪ یک CPU، ۶۴MiB حافظه و هشت task محدود است. failure یا unmount
به توقف dependent می‌انجامد؛ بازگشت mount به‌تنهایی K3s را دوباره شروع نمی‌کند.

خروجی نصب باید `storage_guard: Passed` بدهد؛
`target_startup_and_fault_test: Not verified` عمدی است. آزمون startup/reboot واقعی
نیاز به maintenance و کنسول نجات دارد و خودکار اجرا نمی‌شود. بررسی فقط‌خواندنی:

```bash
sudo systemctl is-active hooshix-openbao-storage-guard.service
sudo systemctl show k3s.service -p BindsTo -p After
sudo /usr/bin/python3 -I /var/lib/hooshixstorage/openbao-storage-guard.py --guard-check
```

دستور آخر در موفقیت exit صفر و بدون خروجی است. **توقف K3s به معنی توقف همهٔ
containerهای قبلی نیست.** bind mount موجود filesystem قبلی را نگه می‌دارد؛
بدون mount، پوشهٔ root-only خالی است و `/data` وجود ندارد؛ local PV نباید به
دایرکتوری دیگری یا provisioner دینامیک fallback کند.

### بازیابی و rollback محافظ

در رخداد fault، public traffic بسته بماند. filesystem/backing و رسید UUID را
بازیابی و دستور `--guard-check` را موفق کنید؛ برای حل خطا پوشهٔ `/data` روی
دیسک اصلی نسازید، filesystem را فرمت نکنید و guard را دور نزنید. با پنجرهٔ
نگهداری مصوب، سپس `systemctl reset-failed hooshix-openbao-storage-guard.service`
و `systemctl start k3s.service` را اجرا کنید. unseal/health OpenBao و سایر
وابستگی‌های امنیتی جداگانه تأیید شوند؛ راه‌اندازی K3s مجوز traffic نیست.

پیش از وجود PV/workload، rollback کد با حفظ داده و فقط در maintenance مصوب:
فایل drop-in اختصاصی بالا را به نامی بدون پسوند `.conf` منتقل کنید، daemon-reload
کنید و guard را متوقف کنید. بعد از اتصال workload، این کار guard را حذف می‌کند
و بدون طرح recovery/traffic-closed مصوب مجاز نیست. فایل backing/state/unit mount
حذف یا فرمت نمی‌شوند. نسخهٔ متفاوت source به‌جای overwrite نیازمند تغییر
بازبینی‌شده و maintenance است؛ installer فعلی fail-closed آن را رد می‌کند.

## کاندیدای local PV؛ هنوز نصب نکنید

```bash
python3 scripts/production/render_openbao_local_storage.py --candidate
python3 scripts/production/render_openbao_candidate.py --candidate --storage-class hooshix-openbao-local
```

اولی فقط StorageClass بدون provisioner با WaitForFirstConsumer و PV ثابت با
Retain، مسیر دقیق `/var/lib/hooshixstorage/openbao/data`، node affinity دقیق
`hooshix-production-1` و رزرو برای PVC ‏`hooshix-secrets/data-openbao-0` می‌سازد.
دومی workload بررسی‌پذیر همان class را می‌سازد. هیچ apply یا نوشتن فایل انجام
نمی‌شود؛ خروجی را مستقیم apply نکنید. فعال‌سازی فقط از promotion بازبینی‌شدهٔ
GitOps پس از guard/storage هدف، mesh/admission/TLS، staging و recovery معتبر است.
۸Gi ظرفیت اعلامی backing است، نه تضمین هشت GiB فضای قابل استفاده بعد از ext4.
تغییر claimRef، node یا مسیر خودکار نیست؛ از reuse دستی PV داده‌دار خودداری کنید.

## خطا، کنترل فضا و rollback بدون حذف داده

- اگر `Failed` یا partial دیده شد، رسید را نگه دارید؛ فایل موجود، unit متعارض
  یا تخصیص نیمه‌کاره خودکار حذف/فرمت نمی‌شود. خطای دقیق و state بررسی شود؛
  installer را کورکورانه تکرار نکنید.
- دستور زیر فقط وضعیت را می‌خواند؛ برای اجرا رمز sudo محلی لازم است:

```bash
sudo systemctl is-active var-lib-hooshixstorage-openbao.mount
sudo findmnt --mountpoint /var/lib/hooshixstorage/openbao
sudo df -B1 /var/lib/hooshixstorage/openbao
```

- reserve پنج‌درصد ext4 برای UID غیرroot قابل مصرف نیست؛ با نزدیک‌شدن فضای
  آزاد/ inode به حد تعیین‌شده قبل از grant جدید باید fail-closed شود.
  alert/audit exporter و عملکرد واقعی OpenBao هنگام ENOSPC هنوز commissioning
  جداگانه‌اند؛ CI فقط حد filesystem را ثابت می‌کند.
- قبل از استفادهٔ داده‌ای، rollback این بنیاد با توقف و disable **همین واحد**
  ممکن است؛ فایل backing و receipt را حفظ کنید. پس از استفادهٔ OpenBao فقط با
  maintenance و backup/recovery مصوب عمل کنید؛ unmount زنده یا حذف فایل مجاز نیست.
  هیچ `rm`، resize یا format مجدد در راهنمای rollback وجود ندارد.
- این واحد فقط WantedBy دارد و خرابی آن به‌تنهایی نباید boot کل VPS/ایمیل را
  متوقف کند. قبل از فعال‌کردن PV، guard وابستگی fail-closed لازم است.
- برای آزمون reboot، reboot مستقل با هماهنگی downtime و کنسول نجات لازم است؛
  اجرای installer reboot نمی‌کند. فعلاً `reboot_persistence: Not verified` است.

CI موجود `OpenBao Kubernetes foundation` نمونهٔ ۶۴MiB را می‌سازد، با UID10001
تا ENOSPC می‌نویسد، unmount/remount و marker/hash و cleanup را بررسی می‌کند؛
محافظ واقعی systemd با یک dependent بی‌ضرر را برای UUID نامعتبر در startup،
read-only، گم‌شدن backing، unmount ناگهانی، عدم re-arm خودکار، fsGroup و رد
نوشتن غیرroot روی مسیر unmounted آزمایش می‌کند. اتصال dependency به سرویس
از قبل روشن بدون restart، حفظ bind موجود و رد bind جدید با source گم‌شده
نیز بررسی می‌شوند. API موقت Kubernetes schema
کاندیدای local PV را نیز بررسی می‌کند؛ این schema شاهد bind واقعی PV هدف نیست.
artifact فقط رسید JSON است، نه image/filesystem یا secret. روی Windows/VPS
این rehearsal را اجرا نکنید.

وضعیت ساخت واقعی، reboot، PV، OpenBao، backup و ظرفیت کامل stack از نتیجهٔ
CI استنتاج نمی‌شود. Stage 10 و آمادگی Production همچنان `Not verified` هستند.

## گزارش بازبینی این بسته

Architecture review mode: full-read
Architecture document version/commit: main@bd74e6401539aae22c8c72f91d2a3e7fc7528e02
Architecture sections reviewed: storage, platform, runtime, security, capacity, recovery, testing, delivery
ADRs reviewed or changed: ADR-0011 clarified guard/fsGroup; ADR-0002/0030/0042..0045 reviewed
Changed bounded context/module: OpenBao host storage guard and review-only local PV
Contracts changed: explicit guard installation switch; fixed public PV candidate; no business API
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: check 12s total/2s native; poll 5s; startup 15s; watchdog 30s; stop 5s
Retry/cancellation/concurrency behavior: one check worker, no retry/automatic re-arm; service cgroup cleanup
Kafka/event and idempotency behavior: Not applicable
Security impact: reject wrong backing inode/device/UUID/options; root-only source; hash same bytes; no SSH/MCP change
Istio identity and authorization impact: None; no active workload/policy promotion
Logging and PII impact: finite public diagnostics; worker output discarded; no credentials
Observability added or changed: systemd health/watchdog and bounded public CI receipt; no production alert claim
Build/CI/architecture enforcement changed: existing required fixture expanded, no gate removal
Tests executed: 228 local production tests and repository/contract/context/diff checks Passed; protected final-head/main and native fixture results tracked in PR #171
Architecture deviations: None
Rollback considerations: preserve data, approved maintenance, no blind format/restart/guard bypass

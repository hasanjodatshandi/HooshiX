# فضای محدود OpenBao روی VPS

بستهٔ ادامهٔ این بنیاد، محافظ هویت mount پیش از K3s و در زمان اجرا و کاندیدای
local PV است. تغییر در حال بررسی است؛ نصب محافظ و اتصال واقعی Kubernetes از
وجود کد یا نتیجهٔ CI استنتاج نمی‌شود.

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
این mount به‌تنهایی مانع fallback volume به دیسک ریشه برای pod آینده نیست.

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
artifact فقط رسید JSON است، نه image/filesystem یا secret. روی Windows/VPS
این rehearsal را اجرا نکنید.

وضعیت ساخت واقعی، reboot، PV، OpenBao، backup و ظرفیت کامل stack از نتیجهٔ
CI استنتاج نمی‌شود. Stage 10 و آمادگی Production همچنان `Not verified` هستند.

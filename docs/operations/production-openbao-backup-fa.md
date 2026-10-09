# پشتیبان رمزدار OpenBao در پارس‌پک

## محدودهٔ بسته

مالک مقصد فعلی `c892683` در `https://c892683.parspack.net` را برای snapshot
با مسیر مستقل `openbao-backups/` انتخاب کرده است. تنظیمات باکت تأییدشده توسط
مالک دوباره probe نمی‌شوند. credential مخصوص backup از فایل محلی خصوصی
`/home/coder/.local/share/hooshix-openbao-backup/credentials.json` دریافت می‌شود؛
credential حساب audit تغییر نمی‌کند.

بستهٔ جاری، snapshot تحت نظارت، encryption با recipientهای موجود، دریافت
نسخهٔ مشخص و آزمون decrypt را پیاده می‌کند. نصب موفق OpenBao یا init/unseal
تکرار نمی‌شود. این بسته، scheduler ساعتی، restore واقعی هدف، ESO، لغو root
یا آمادگی Production را تأیید نمی‌کند.

root token فقط هنگام اجرای تحت نظارت از custody رمزدار موجود در حافظهٔ
operator بازیابی می‌شود؛ در argv، environment، log، فایل plaintext یا timer
قرار نمی‌گیرد. سهم‌ها و کلیدهای خصوصی به VPS/باکت منتقل نمی‌شوند.

تست‌های native از OpenBao 2.6.4 پین‌شده و کلیدهای مصنوعی استفاده می‌کنند؛
هیچ secret واقعی به CI داده نمی‌شود. آزمون CI bytes حاصل از decrypt همین
envelope را در OpenBao آزمایشی جدا restore می‌کند؛ این شاهد restore هدف
Production نیست.

## اجرای اولین snapshot

از checkout تمیز، merge‌شده و CI موفق، با VNC کارا و نشست دوم خصوصی SSH،
در PowerShell بدون transcript/ضبط صفحه اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/backup_openbao_operator.py --rescue-and-second-session-ready --custody /home/coder/.local/share/hooshix-openbao-custody/7509bb067d814468982cc84fa50cb4d4
```

1. `READY` را فقط با مسیر نجات کارا وارد کنید.
2. **همان رمز قبلی custody OpenBao** را یک بار وارد کنید؛ رمز Root، CA، sudo
   یا GHCR نیست. کلید یا custody تازه ساخته نمی‌شود.
3. رمز sudo VPS را در prompt مخفی محلی وارد کنید. دستور عمومی hash-bound
   تنها snapshot مخزن موجود را می‌خواند و آن را با سه public recipient موجود
   رمز می‌کند. token فقط در header درخواست TLS و stdin رمزدار SSH می‌رود.
4. ابزار ciphertext را با حد ۳۳MiB/۹۰ ثانیه دانلود و با mode `0600` خارج
   پروژه ذخیره می‌کند. snapshot خام حداکثر ۳۲MiB است؛ عبور از این حد، خطاست،
   نه مجوز افزایش خودکار حد یا ارسال ناقص.
5. یک PUT با UUID مستقل، شرط `If-None-Match: *` و checksum ارسال می‌شود؛
   فقط همان version با GET خوانده و byte/hash آن تطبیق داده می‌شود. TLS
   معتبر، بدون proxy/redirect و بدون retry خودکار است. version ناموجود/null،
   timeout یا پاسخ ناسازگار success نیست.
6. نسخهٔ بازخوانی‌شده با private export قبلی فقط در حافظهٔ WSL decrypt و
   hash snapshot اصلی تطبیق داده می‌شود. هیچ restore روی VPS انجام نمی‌شود.

نتیجهٔ موفق `OPENBAO_OFF_HOST_SNAPSHOT=Passed` و `PUBLIC_RECEIPT` است.
receipt عمومی را می‌توان فرستاد؛ credentials، private export، token، رمز و
bytes snapshot را نفرستید. فایل‌های backup در زیرپوشهٔ UUID خارج Git، در
`/home/coder/.local/share/hooshix-openbao-backup/` می‌مانند؛ export رمزدار
VPS هم تا cleanup جدا و بازبینی‌شده حفظ می‌شود.

## شکست و ادامه

### اختلال فعلی ارائه‌دهنده، ۲۰۲۶-۱۰-۰۹

GET فایل اولین snapshot با HTTP 200 و hash برابر نسخهٔ محلی موفق شد؛
پاسخ فاقد `x-amz-version-id` بود و ListObjectVersions برای همان key مقدار
`VersionId=null` برگرداند. مالک گزارش کرده پشتیبانی پارس‌پک این را اختلال
موقت و قابل‌رفع طی چند روز دانسته است. این گزارش، تأیید API نسخه یا قفل
همین object نیست: ارسال/hash `Passed`، تأیید نسخه `Not verified` می‌ماند.
upload همان key تکرار نمی‌شود؛ باکت/کلید/custody/init تغییر نمی‌کند.

کار مستقل بعدی آزمون recovery همان envelope در یک محیط خصوصی جداست؛
آزمون مستقل، تأیید نسخهٔ باکت یا دروازهٔ Production را جایگزین نمی‌کند.

### آزمون بازیابی بک‌آپ موجود، بدون اتصال به VPS

از checkout تمیز و merge‌شده با CI موفق، در PowerShell بدون ضبط/transcript:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/restore_openbao_isolated.py --snapshot /home/coder/.local/share/hooshix-openbao-backup/64e48c07a0074d6896e46537b837c032 --custody /home/coder/.local/share/hooshix-openbao-custody/7509bb067d814468982cc84fa50cb4d4
```

فقط **همان رمز custody OpenBao** یک بار درخواست می‌شود؛ sudo، GHCR، رمز
Root/CA یا تغییر باکت لازم نیست. ابزار hash envelope و اتصال آن به همان
recipientها را بررسی می‌کند؛ snapshot، دو سهم و root token فقط در حافظهٔ
operator بازیابی می‌شوند. این عملیات recovery تحت نظارت است، نه محیط توسعه؛
هیچ secret واقعی به CI یا فایل plaintext منتقل نمی‌شود.

Docker محلی با socket ثابت، نسخهٔ حداقل ۲۸ و قابلیت محدودسازی swap لازم است.
همان digest عمومی OpenBao 2.6.4 (برابر mirror خصوصی Production) دریافت
می‌شود؛ کلید/گواهی یک‌روزهٔ آزمایشی جدید فقط برای TLS همین clone است، نه
Root یا intermediate پروژه. هدف با نام تصادفی، شبکهٔ `--internal`، پورت
TLS فقط روی `127.0.0.1`، data روی tmpfs با حد 128MiB، RAM با حد 512MiB و
swap غیرفعال برای container ساخته می‌شود. Docker daemon و سیستم operator
باید مورد اعتماد باشند؛ سایر پردازش‌های مدیر همان دستگاه مرز مستقل نیستند.

فقط همین clone خالی init و `snapshot-force` می‌شود؛ پس از restore، دو سهم
اصلی آن را unseal می‌کنند و احراز هویت اصلی، mountها و audit بدون `log_raw`
آزمایش می‌شوند. هیچ endpoint ورودی، مسیر data Production، SSH، upload،
لغو root یا init/unseal VPS وجود ندارد. سازوکار force برای هدف خالی جدا
طبق [API نسخهٔ 2.6.4](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/api/system/storage/raft.mdx)
است؛ استفاده از آن روی مخزن فعال مجاز نیست.

موفقیت فقط پس از حذف clone و شبکهٔ دارای label مالک همین اجرا گزارش می‌شود:
`OPENBAO_ISOLATED_RECOVERY=Passed` و `PUBLIC_RECEIPT` عمومی در پوشهٔ همان
بک‌آپ. اصل بک‌آپ/custody تغییر نمی‌کند. این نتیجه recovery محلی را ثابت
می‌کند، نه restore VPS، نسخه‌سازی باکت، scheduler ساعتی یا آمادگی Production.
CI همین adapter را با snapshot و سهم‌های **مصنوعی** اجرا می‌کند.

در شکست، نتیجهٔ عمومی را بفرستید؛ هیچ init/upload/reset خودکار تکرار نشود.
در kill سخت یا قطع Docker، cleanup ممکن است کامل نشود: container/network
با پیشوند `hooshix-bao-restore-` و label `hooshix.isolated-restore` همان اجرا
باید پیش از retry بررسی و فقط با تطبیق هویت حذف شود. دادهٔ clone موقت است؛
اصل فایل‌های recovery را پاک نکنید.

در شکست، `RETAINED_BACKUP_DIRECTORY` را حفظ کنید. `intent.json` پیش از
snapshot و `delivery-intent.json` پیش از PUT ثبت می‌شوند. با timeout PUT
ممکن است object ایجاد شده باشد؛ همان object/key را کورکورانه overwrite یا
upload دوباره نکنید. `cloud-receipt.json` یعنی PUT و readback موفق بوده‌اند؛
شکست decrypt بعدی مجوز init، reset custody یا upload مجدد نیست. ابتدا فقط
نتیجهٔ عمومی را گزارش کنید تا ادامهٔ متناسب با receipt انتخاب شود.

این دستور scheduler نیست. زمان‌بندی ساعتی باید هویت محدود و قابل‌لغو
مخصوص snapshot، مقصد محدود backup و هشدار/آزمون failure داشته باشد؛ root
token نباید در timer، فایل سرویس یا محیط دائمی قرار گیرد. آزمون restore
واقعیِ جدا، hourly backup، scoped authentication/ESO و root revocation هنوز
کارهای بعدی‌اند؛ این بسته دروازهٔ Production را باز نمی‌کند.

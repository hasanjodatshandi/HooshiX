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

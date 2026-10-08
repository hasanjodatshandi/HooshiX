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
هیچ secret واقعی به CI داده نمی‌شود. دستور operator و نتیجهٔ اجرا پس از
تکمیل و عبور از بررسی‌های همین PR ثبت می‌شوند.

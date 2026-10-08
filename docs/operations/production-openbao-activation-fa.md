# فعال‌سازی OpenBao نصب‌شده

## محدوده

نصب واقعی روی VPS در ۲۰۲۶-۱۰-۰۸ با receipt عمومی
`hooshix-platform-d6c52f209c034d019f67ee38b727724a.json` موفق شد:
OpenBao 2.6.4 نصب شده، sealed و هنوز initialize نشده است. Root، SSH، ایمیل و
storage دوباره ساخته یا نصب نمی‌شوند. Stage 10 و Production readiness هنوز
`Not verified` هستند.

هدف این تغییر واحد، initialization با Shamir سه سهم/آستانهٔ دو، رمزکردن سهم‌ها
قبل از خروج از OpenBao، تحویل و آزمایش custody بیرون VPS، و unseal است.
اسکریپت اجرای مالک و آزمون واقعی با secrets مصنوعی در همین تغییر اضافه می‌شوند؛
این سند به‌تنهایی مجوز اجرا یا شاهد موفقیت فعال‌سازی نیست.

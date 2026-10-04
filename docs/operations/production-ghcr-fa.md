# Registry خصوصی Production

مالک در ۲۰۲۶-۱۰-۰۴، GHCR خصوصی در حساب `hasanjodatshandi` را انتخاب کرد.
تصاویر برنامه زیر `ghcr.io/hasanjodatshandi/hooshix/` قرار می‌گیرند.
این انتخاب به معنی انتشار، امضا، استقرار یا آمادگی Production نیست.

## کلید خواندن برای VPS

1. در GitHub، Settings حساب ← Developer settings ← Personal access tokens
   ← Tokens (classic) ← Generate new token (classic) را باز کنید.
2. نام `HooshiX-production-image-pull` و انقضای محدود، مثلاً ۳۰ روز، انتخاب کنید.
3. فقط `read:packages` را انتخاب کنید؛ `repo`، `write:packages` و
   `delete:packages` لازم نیستند. دسترسی حساب باید محدود به packageهای لازم باشد؛
   scope به‌تنهایی محدودیت تک‌package ایجاد نمی‌کند. این token به داده‌های سایر
   packageهای قابل‌خواندن حساب هم ممکن است دسترسی داشته باشد.
4. token را فقط در مقدار `token` فایل محلی زیر قرار دهید؛ در چت نفرستید:

   `/home/coder/workspace/Hooshix/.platform-runtime/production/private/ghcr-read-credentials.json`

5. فایل باید `0600` و والد private باید `0700` باشد. مقدار را با `cat` چاپ نکنید؛
   نام کاربری `hasanjodatshandi` و registry `ghcr.io` باقی بمانند.
6. وقتی ذخیره شد، فقط بگویید «کلید خواندن GHCR گذاشته شد». کلید باید بعداً از مسیر
   مصوب OpenBao به مرز محدود pull منتقل شود؛ فایل bootstrap محل دائمی secret نیست.

## انتشار و امضا

credential نوشتن باید token موقت همان job در GitHub Actions با `packages: write`
باشد، نه PAT مدیر و نه کلید خواندن VPS. workflow باید فقط روی `main` بررسی‌شده،
در environment محافظت‌شده، با بررسی package خصوصی و digest دقیق اجرا شود.
workflow موجود `Production release evidence` اکنون login با token موقت، بررسی
namespace انتخاب‌شده، GET محدود برای private بودن هفت package، و پاک‌سازی فایل
احراز هویت runner را انجام می‌دهد. خطای API یا package عمومی اجازهٔ امضا نمی‌دهد.
این workflow تصاویر را نمی‌سازد؛ فقط digestهای منتشرشده و manifest تأییدشده را
اسکن و امضا می‌کند. build/publish تصاویر و staging قبل از manifest واقعی release لازم‌اند؛
نبود manifest/تصویر/approval موفقیت نیست. مرحلهٔ انتشار اجازهٔ rollout نمی‌دهد.

environment واقعی `production-release` در GitHub ایجاد شد: فقط branch دقیق `main`
و reviewer حساب مالک. مالک تک‌نفره اجازهٔ تأیید اجرای خودش را دارد؛ این دسترسی مستقل
یا تأیید آمادگی Production را اثبات نمی‌کند. حذف environment برای گذراندن job مجاز نیست.

مرجع: [مستندات رسمی GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
Context7 و مستندات رسمی برای token موقت، scope خواندن و private پیش‌فرض بررسی شدند.

## شواهد این تغییر

۱۸۲ تست production شامل شش تست مرز registry، قراردادها و context محلی `Passed` شدند.
CI اولیهٔ `63e93bb7` در Gitleaks رد شد: تنها finding در خط ۱۷۴ roadmap، عبارت ثابت
عمومی `owned/private` بود؛ این متن credential نیست. استثنای بازبینی‌شده فقط rule
`generic-api-key`، همان فایل و **کل همان خط ثابت** را شامل می‌شود؛ تغییر متن یا افزودن
مقدار واقعی با آن تطبیق نمی‌کند. اسکن secret فعلی و تاریخچه و fixture مثبت همچنان
الزامی‌اند؛ هیچ file-wide allowlist، حذف rule یا پنهان‌کردن مقدار واقعی وجود ندارد.
fixture اجرایی جدید در همان فایلِ مجاز، ابتدا متن عمومی را قبول و سپس یک کلید
مصنوعی افزوده‌شده را رد می‌کند؛ این کنترل در شش security job موجود اجرا می‌شود.

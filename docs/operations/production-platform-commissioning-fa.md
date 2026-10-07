# نصب اولیهٔ mesh و OpenBao روی VPS

این راهنما متعلق به تغییر یکپارچهٔ Stage 10 است: استفاده از CA و storage موجود،
نصب محدود Istio و OpenBao، و ثبت نتیجهٔ واقعی هر مرحله. ساخت دوبارهٔ Root،
CSR یا filesystem هشت GiB، تغییر SSH/ایمیل و حذف دسترسی مدیر در این تغییر نیست.

انتشار تصویر با نصب روی VPS فرق دارد. receipt انتشار فقط امضا، provenance،
SBOM و scan همان digest را ثابت می‌کند؛ جای staging یا admission زنده را نمی‌گیرد.
پیش از فعال‌سازی PV/workload، الزام‌های ADR-0011 و ADR-0017 باید برقرار باشند.
نصب اولیه از استثنای محدود ADR-0030 و ورود محلی رمز sudo استفاده می‌کند؛
این مسیر مجوز عمومی CI برای مدیریت Production نیست.

## وضعیت

Implementation: In progress
VPS installation: Not run by this change
Production readiness: Not verified

فرمان نصب فقط پس از تکمیل implementation و بررسی CI همین تغییر اضافه می‌شود.
هیچ کلید، رمز، سهم Shamir یا token را در گفتگو ارسال نکنید.

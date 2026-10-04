# راه‌اندازی OpenBao در Stage 10

## محدودهٔ این تغییر

هدف این PR آماده‌کردن بستهٔ قابل بازبینی Kubernetes برای OpenBao 2.6.1 است؛
نه فعال‌کردن Production، init واقعی، تغییر SSH/MCP یا حذف sudo دائمی.
تعریف reusable در `infrastructure/production/secrets/` می‌ماند و تا عبور از
gateهای promotion وارد ریشهٔ فعال `deploy/clusters/production` نمی‌شود.

## ترتیب اجرا و نقطهٔ ادامه

1. آماده‌کردن workload تک‌نمونهٔ Raft، PVC با نگهداری هنگام حذف workload،
   TLS خصوصی، ServiceAccount مستقل، منابع محدود، NetworkPolicy و STRICT mTLS.
2. آزمون ساختار و کنترل‌های منفی در pipeline موجود؛ آزمون native TLS/Raft
   موجود نیز الزامی باقی می‌ماند. خروجی renderer فقط candidate است، نه مجوز deploy.
3. بررسی storage class، PKI/SAN، mesh/admission و staging همان digest،
   SBOM/scan/signature؛ سپس promotion بازبینی‌شده از مسیر Argo CD.
4. init واقعی یک‌باره، تحویل خصوصی سه سهم با آستانهٔ دو، policy محدود، لغو root
   token، snapshot رمز‌شدهٔ ساعتی خارج از سرور و restore مستقل.
5. materialization credential برای exporter ممیزی؛ سپس JIT و آزمون قطع sink،
   expiry/revoke. حذف دسترسی دائمی فقط پس از تأیید جایگزین کامل و مسیر نجات.

وضعیت فعلی: مرحلهٔ اول در حال پیاده‌سازی؛ استقرار واقعی، recovery و Stage 10
همچنان `Not verified` هستند. کلید WireGuard گزارش‌شده به‌عنوان افشاشده نیاز به
چرخش کنترل‌شده با کنسول نجات فعال دارد؛ کلید یا محتوای فایل آن را در گفتگو
نفرستید. این PR شبکهٔ مدیریت یا پورت MCP را تغییر نمی‌دهد.

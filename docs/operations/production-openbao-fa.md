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

وضعیت فعلی: کد foundation و آزمون‌های آن آماده است؛ اجرای Kubernetes، استقرار
واقعی، recovery و Stage 10 همچنان `Not verified` هستند. کلید WireGuard گزارش‌شده به‌عنوان افشاشده نیاز به
چرخش کنترل‌شده با کنسول نجات فعال دارد؛ کلید یا محتوای فایل آن را در گفتگو
نفرستید. این PR شبکهٔ مدیریت یا پورت MCP را تغییر نمی‌دهد.

## استفاده از بستهٔ عمومی

در checkout بازبینی‌شدهٔ همین PR/نسخهٔ merge‌شده در WSL:

```bash
python3 scripts/production/render_openbao_candidate.py \
  --candidate --storage-class <reviewed-storage-class>
```

`<reviewed-storage-class>` را با نام واقعی storage class هدف جایگزین کنید؛
هیچ نام پیش‌فرضی فرض نشده است. خروجی JSON معتبر Kubernetes List است و فقط
metadata عمومی دارد. آن را مستقیم به `kubectl apply` pipe نکنید؛ ابتدا شواهد
promotion و مسیر Argo CD زیر باید آماده شوند. ابزار هیچ کلید، token یا فایل
credential نمی‌خواند، image دانلود نمی‌کند، فایل نمی‌نویسد و به سرور وصل نمی‌شود.

خروجی شامل Namespace با PSA restricted و Ambient، ServiceAccount بدون token،
ConfigMap مشتق از `openbao-server.json`، Service داخلی headless و StatefulSet
تک‌نمونه است. PVC هنگام حذف/کاهش نمونه نگه داشته می‌شود؛ prune نیاز به تأیید
دارد. راه‌اندازی هیچ init container ریشه یا قابلیت افزوده‌ای ندارد.
پیش‌فرض candidate: درخواست ۲۵۰ میلی‌CPU/۲۵۶MiB، سقف ۱ CPU/۵۱۲MiB، PVC هشت GiB
و tmp حافظه‌ای ۱۶MiB. اینها سقف شروع آزمون هستند، **نه ظرفیت تأییدشدهٔ Production**.
درخواست هشت GiB برای PVC سقف مصرف filesystem را ثابت نمی‌کند؛ برخی storage classها
مثل local-path آن را quota نمی‌کنند. پیش از نصب، enforcement واقعی quota/فضای
رزرو و رفتار پرشدن volume باید آزموده شود؛ storage فاقد کنترل رشد تأیید نمی‌شود.
حجم audit، rotation/export و reserve دیسک باید قبل از استفادهٔ واقعی اندازه‌گیری
و محدود شوند؛ PVC پرشده نباید باعث حذف evidence یا ادامهٔ grant جدید شود.

TLS Secret به نام `openbao-server-tls` باید خارج از Git با `tls.crt`، `tls.key`
و `ca.crt` معتبر آماده شود. SAN لازم شامل IP `127.0.0.1` برای probe داخلی و
DNSهای `openbao.hooshix-secrets.svc` و
`openbao-0.openbao.hooshix-secrets.svc` برای API/Raft است. CA خصوصی bootstrap
باید جدا و کنترل‌شده باشد؛ کلید root CA Istio وارد OpenBao، Kubernetes یا این
Secret نمی‌شود. mount TLS فقط‌خواندنی و mode `0440` با fsGroup اختصاصی است.

probeهای startup/liveness از `bao status` با TLS معتبر استفاده می‌کنند و exit
صفر (باز) یا دو (sealed) را زنده می‌دانند؛ readiness فقط صفر را می‌پذیرد.
خطای TLS/شبکه/فرایند و exit یک موفق نیست. خروجی probe چاپ نمی‌شود، timeout
سه ثانیه و retry صفر است. unseal دستی زمان‌بر، دلیل restart مداوم نیست.
StatefulSet از `OnDelete` استفاده می‌کند: تغییر config/image به‌تنهایی pod را
restart نمی‌کند؛ replacement باید پس از snapshot، پنجرهٔ نگهداری و آمادگی
دو سهم با Argo CD و رویهٔ بازبینی‌شده انجام شود. downgrade یا حذف PVC rollback
محسوب نمی‌شود.

## gateهای پیش از نصب

- خروجی روی API هدف Kubernetes 1.35.6 و CRDهای Istio 1.30.3 اعتبارسنجی شود؛
  fsGroup/CSI، TLS خواندنی برای UID 10001، PVC پس از restart و وضعیت sealed
  در staging واقعی آزموده شوند. unit test جای این آزمون نیست.
- digest موجود باید SBOM، تصمیم vulnerability و signature/provenance مورد قبول
  admission داشته باشد؛ image upstream یا آزمون Docker به‌تنهایی مجوز promotion
  نیست. سیاست Kyverno باید namespace جدید را نیز پوشش دهد؛ policy فعلی platform
  به‌طور خودکار همهٔ namespaceهای جدید را پوشش نمی‌دهد.
- NetworkPolicy هر دو جهت و AuthorizationPolicy دسترسی client را بسته‌اند؛ فقط
  DNS روی TCP/UDP 53 به podهای kube-dns در kube-system مجاز است تا نام Raft/API
  قابل حل باشد. labels/resolver واقعی هدف نیز باید پیش از نصب تطبیق داده شوند.
  kube exec/port-forward مسیر کنترل Kubernetes است، نه اثبات workload mTLS.
  قبل از ESO/host exporter، هویت، مسیر ارتباط، TokenReview محدود، trust domain،
  deadline، CA و policy دقیق آن edgeها در یک تغییر بازبینی‌شده اضافه و آزموده شوند؛
  namespace-wide allow یا خاموش‌کردن STRICT راه‌حل نیست.
- فقط پس از این شواهد، promotion PR تعریف فعال را به ریشهٔ Argo CD اضافه کند.
  Secretهای واقعی، سهم‌ها و root token در این PR یا CI قرار نمی‌گیرند.
- snapshot رمز‌شدهٔ ساعتی، export ممیزی، هشدار دیسک/backlog، sealed/startup،
  rotation گواهی و restore خارج از failure domain باید واقعاً اجرا شوند.

## آزمون و گزارش بازبینی

```bash
python3 -m unittest discover -s scripts/production/tests -p 'test_openbao*.py'
make production-verify
```

pipeline موجود `Repository baseline` همهٔ production testها و render عمومی را
اجرا می‌کند. job اجباری `OpenBao TLS and Raft recovery` آزمون native با secretهای
مصنوعی را انجام می‌دهد و اکنون فرمان probe را نیز در حالت sealed/unsealed بررسی
می‌کند. runner موقت cleanup دارد؛ این آزمون را روی لپ‌تاپ/VPS با دادهٔ واقعی
اجرا نکنید. وضعیت run همان commit را بررسی کنید؛ run سبز قبلی شاهد تغییر جدید نیست.

### ادامهٔ بررسی artifact در CI

قدم بعدی این تغییر، ساخت SBOM و اسکن آسیب‌پذیری برای همان digest عمومی است؛
این بررسی نصب، امضای artifact، آزمون staging یا اجازهٔ promotion نیست.
هیچ کلید Production برای این job لازم نیست و دانلود فقط روی runner موقت GitHub است.

Architecture review mode: full-read
Architecture document version/commit: main@58c067ecac27b5fdbab93801c765d59b282b3404
Architecture sections reviewed: platform, runtime/deployment, security/secrets, readiness, capacity, recovery, delivery
Search terms used: OpenBao, Raft, Shamir, PVC, GitOps, JIT, audit, promotion
ADRs reviewed or changed: ADR-0011, ADR-0030, ADR-0042..0045 reviewed; None changed
Changed bounded context/module: Production secret-authority foundation candidate
Contracts changed: public review-only JSON Kubernetes List renderer CLI
Database migration: Not applicable
Transaction boundary: Not applicable
Timeout/deadline behavior: status client 3s, probe 5s, finite startup/liveness thresholds
Retry/cancellation/concurrency behavior: zero CLI retries; one replica; controlled OnDelete replacement
Kafka/event and idempotency behavior: Not applicable
Security impact: no secret generation/reading, TLS required, non-root bounded retained storage
Istio identity and authorization impact: dedicated SA, Ambient/STRICT, deny-all pending reviewed client edges
Logging and PII impact: probe output discarded; existing non-raw audit configuration unchanged
Observability added or changed: startup/readiness/liveness only; exporter/alert/capacity Not verified
Build/CI/architecture enforcement changed: existing production unit/render gate and native recovery probe checks
Tests executed: 18 focused OpenBao tests and 165 production tests Passed locally; native probe/TLS/Raft job Passed on PR #162 at 68bea8aa (run 37183265521); final-head protected CI pending; Kubernetes runtime Not verified
Architecture deviations: None; candidate is deliberately outside active GitOps roots
Rollback considerations: retain PVC, reviewed prior digest/config, never blind downgrade/delete; live rollback Not verified

منابع: Context7 برای OpenBao فراخوانی شد و رفتار exitهای status، پارامترهای CA،
timeout و retry با مستندات رسمی tag دقیق 2.6.1 تطبیق داده شد:
[status](https://github.com/openbao/openbao/blob/v2.6.1/website/content/docs/commands/status.mdx)،
[CLI](https://github.com/openbao/openbao/blob/v2.6.1/website/content/docs/commands/index.mdx)،
[configuration](https://github.com/openbao/openbao/blob/v2.6.1/website/content/docs/configuration/index.mdx).

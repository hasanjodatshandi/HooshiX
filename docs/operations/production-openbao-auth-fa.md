# احراز هویت محدود OpenBao و آماده‌سازی ESO

این مرحله از OpenBao موجود استفاده می‌کند: شش هویت فقط‌خواندنی برای شش سرویس،
بدون نصب دوباره، init، تعویض کلید، restart، لغو root یا تغییر SSH/ایمیل.
نصب ESO و همگام‌سازی واقعی اسرار مرحلهٔ بعد است؛ این راهنما به معنی آماده‌بودن Production نیست.

## مرز دسترسی

در `platform-apps` شش ServiceAccount تحویل اسرار، `eso-<service>`، برای
authorization-service، compromised-password-service، conversation-service،
identity-service، notification-service و web-bff ساخته می‌شوند. حساب برنامه
به این نقش‌ها bind نمی‌شود. هر نقش فقط `read` روی
`hooshix/data/production/<service>/*` دارد؛ metadata/list، write/delete، اسرار
سرویس دیگر و مدیریت OpenBao مجاز نیست. token برنامه/تحویل حداکثر ۳۰۰ ثانیه
اعتبار دارد و policy پیش‌فرض ندارد. root فعلی فقط در حافظهٔ عملیات تحت نظارت است.

mount مستقل `hooshix-kubernetes` با TokenReview واقعی و JWT تازهٔ client کار
می‌کند؛ static reviewer token یا token خودکار Pod OpenBao اضافه نمی‌شود و
OpenBao restart نمی‌شود. فقط شش حساب تحویل، `create tokenreviews` دارند؛
SubjectAccessReview، خواندن Secret یا cluster-admin به آن‌ها داده نمی‌شود.
audience API از TokenRequest معتبر همان API کشف می‌شود، به‌علاوه audience دقیق
`hooshix-openbao`. JWT فقط در حافظه است و TokenRequest ده‌دقیقه‌ای است؛
token صادرشدهٔ OpenBao سقف پنج دقیقه دارد. حذف حساب/JWT نامعتبر در TokenReview
رد می‌شود؛ سرویس‌اکانت باید مانند credential حساس کنترل شود.

`platform-apps` با Ambient، PSA Restricted، NetworkPolicy default deny و mesh
default deny آماده می‌شود. فقط egress خود OpenBao به API همان کلاستر اضافه
می‌شود. ingress OpenBao یا خروجی برنامه‌ها باز نمی‌شود. ESO هنوز نصب نشده است؛
SecretStoreهای namespace-scoped، CA عمومی ConfigMap و role/SA مشخص در رسید
به‌عنوان قرارداد آینده آماده می‌شوند، نه CRD نصب‌شده یا تحویل واقعی Secret.
نصب ESO باید RBAC ساخت TokenRequest را فقط به نام‌های همین حساب‌ها محدود کند،
نه همهٔ ServiceAccountها؛ دسترسی شبکه/mesh آن جداگانه آزموده خواهد شد.

## اجرای روی VPS فعلی، فقط بعد از merge و CI موفق

این ابزار مخصوص VPS فعلی و alias `hooshix-server` است؛ روی نصب دوم قبل از
ساخت بستهٔ اختصاصی اجرا نشود. rescue VNC و نشست مستقل خصوصی SSH باز بمانند.
از checkout تمیز، در PowerShell محلی بدون transcript/ضبط:

پیش از اجرای ابزار، `platform-apps` باید سه برچسب `istio.io/dataplane-mode=ambient`،
`pod-security.kubernetes.io/enforce=restricted` و
`pod-security.kubernetes.io/enforce-version=v1.35` داشته باشد. ابزار namespace
موجود را خودکار relabel نمی‌کند. اگر فقط برچسب Ambient غایب است، ابتدا بررسی کنید:

```powershell
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl get namespace platform-apps --show-labels'
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl get pods -n platform-apps'
```

فقط وقتی namespace هیچ Pod ندارد و دو برچسب امنیتی دقیقاً مطابق‌اند:

```powershell
ssh.exe -t hooshix-server 'sudo /usr/local/bin/k3s kubectl label namespace platform-apps istio.io/dataplane-mode=ambient'
```

برای namespace دارای workload یا هر تعارض دیگر، توقف و بررسی جداگانه لازم است؛
حذف namespace یا overwrite برچسب‌ها روش نصب نیست.

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning --exec python3 scripts/production/bootstrap_openbao_auth_operator.py --custody /home/coder/.local/share/hooshix-openbao-custody/7509bb067d814468982cc84fa50cb4d4 --rescue-and-second-session-ready
```

1. فقط با مسیر نجات کارا `READY` بنویسید.
2. **رمز موجود custody OpenBao** یک بار، نه Root/CA/GHCR/sudo، وارد شود.
3. رمز sudo فقط در prompt مخفی همان پنجره وارد شود. token جدید GHCR لازم نیست.
4. source عمومی hash-bound قبل از اجرای root بررسی می‌شود؛ root token از
   custody موجود فقط در RAM، stdin رمزدار SSH و header TLS استفاده می‌شود.
   داده/PVC/Root/کلید/SSH/ایمیل و وضعیت unseal تغییر نمی‌کنند.
5. عملیات deadline ده دقیقه‌ای دارد؛ source conflict یا API timeout باعث حفظ
   state و توقف می‌شود. object متعلق به دیگری یا policy/role متفاوت overwrite
   نمی‌شود. پس از بررسی علت، resume همین روش فقط موارد دقیقاً منطبق را می‌پذیرد؛
   marker، auth mount، namespace یا PVC را برای retry حذف نکنید.
6. برای هر شش هویت login، capability خواندن مسیر خودش، رد cross-service/write/
   admin/هویت دیگر/audience دیگر/namespace دیگر و revocation آزموده می‌شود.
   tokenهای موقت آزمون لغو می‌شوند و root حفظ می‌شود. مسیر read-only probe اگر
   خالی باشد 404 مجاز است؛ این شاهد تحویل اسرار واقعی نیست. write آزمون فقط باید
   با 403 رد شود. protected OS/OpenBao audit لازم است؛ off-host audit را اثبات نمی‌کند.

نتیجهٔ مورد انتظار `OPENBAO_SCOPED_AUTH=Passed` و مسیر `PUBLIC_RECEIPT` خارج Git
در `/home/coder/.local/share/hooshix-openbao-auth/` است. فقط نتیجهٔ عمومی را
بفرستید؛ token، رمز، shares، ciphertext یا فایل credential نفرستید.

اجرای مالک روی VPS اول با source `467acaed9a2c56ad6e5f14d08659ebb3c3362ed3`
نتیجهٔ `Passed` گزارش کرده است؛ رسید عمومی
`auth-481ef67e63ce4f7d97e6a6a3c60e562a.json` خارج Git است. root حفظ شده و ESO
نصب نشده است. این شاهد جای آزمون مستقل نصب دوم را نمی‌گیرد.

## کاربرد و مرحلهٔ بعد

با نصب و آزمون ESO، operator از هویت مشخص هر سرویس login می‌کند و فقط کلیدهای
منطقی مصوب را همگام می‌کند؛ برنامه‌ها hot-path OpenBao call ندارند. secret
واقعی در این مرحله نوشته/همگام نمی‌شود. هیچ root job، scheduler backup، لغو root
یا JIT جدید اجرا نمی‌شود. بعدی: artifact امضاشدهٔ ESO، محدودسازی RBAC و مسیر
شبکه/mesh، سپس materialization و گردش/recovery اسرار.

CI native از OpenBao 2.6.4 و Kubernetes واقعی با دادهٔ مصنوعی استفاده می‌کند؛
JWT TokenRequest/TokenReview، شش canary مستقل، ACL negatives و revocation را
آزمون می‌کند. این شاهد اجرای واقعی VPS یا مسیر ESO/Ambient نیست.

مراجع: [OpenBao Kubernetes auth نسخهٔ 2.6.4](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/auth/kubernetes.mdx)،
[ESO Vault provider نسخهٔ 2.8.0](https://github.com/external-secrets/external-secrets/blob/v2.8.0/docs/provider/hashicorp-vault.md).

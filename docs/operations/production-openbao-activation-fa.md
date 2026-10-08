# فعال‌سازی OpenBao نصب‌شده

## محدوده

نصب واقعی روی VPS در ۲۰۲۶-۱۰-۰۸ با receipt عمومی
`hooshix-platform-d6c52f209c034d019f67ee38b727724a.json` موفق شد:
OpenBao 2.6.4 نصب شده است. بررسی مالک در ۲۰۲۶-۱۰-۰۸ نشان داد initialization
انجام شده ولی خروجی رمزدار آن ثبت نشده و سرویس sealed مانده است؛ سهم‌های
این initialization در دسترس مالک نیستند. بازیابی فعلی، با تأیید صریح مالک،
باید ابتدا کل datastore و intent قبلی را خصوصی و قابل‌بازگردانی بایگانی کند
و فقط سپس مخزن همین نصب تازه را جایگزین کند؛ اجرای خودکار init دوباره ممنوع است.
Root، SSH، ایمیل و
storage دوباره ساخته یا نصب نمی‌شوند. Stage 10 و Production readiness هنوز
`Not verified` هستند.

در اجرای مالک در ۲۰۲۶-۱۰-۰۸، بایگانی initialization گمشده با receipt
`recovery-receipt-7a40872c679942469077401e5d14603a.json` موفق شد؛ مرحلهٔ بعد
با `ACTIVATION_GET_PUBLIC_CA_EXIT_FAILED` متوقف شد. پس از اصلاح خواندن فقط
گواهی عمومی از Secret نصب‌شدهٔ `openbao-server-tls`، ادامه باید با همان
custody و `--resume` **بدون** `--recover-lost-initialization` باشد؛ بایگانی
قبلی حفظ می‌شود و recovery تکرار نمی‌شود. این خروجی هنوز شاهد init/unseal نیست.

هدف این تغییر واحد، initialization با Shamir سه سهم/آستانهٔ دو، رمزکردن سهم‌ها
قبل از خروج از OpenBao، تحویل و آزمایش custody بیرون VPS، و unseal است.
این فقط bootstrap محدود ADR-0030 است، نه جایگزین GitOps/JIT؛ client ingress،
port عمومی، Kubernetes Auth و policy برنامه تغییر نمی‌کنند. API audit باید
Metadata باشد؛ عملیات نوشتن با CA موجود و TLS معتبر از یک forwarding موقت
فقط روی loopback خود میزبان انجام می‌شود و هیچ secret در
argv/environment/فایل plaintext/خروجی قرار نمی‌گیرد.

## اجرای مالک

پس از merge و CI موفق، با VNC کارا و نشست دوم خصوصی SSH در PowerShell محلی،
بدون transcript/ضبط صفحه اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --rescue-and-second-session-ready
```

checkout باید تمیز و revision عضو `origin/main` باشد. ابزار از SSH/SCP موجود
Windows و alias موجود `hooshix-server` استفاده می‌کند؛ canonical checkout
دارای تغییرهای دیگر، forwarding و SSH را عوض نمی‌کند.

1. با برقرار بودن مسیر نجات، `READY` بنویسید.
2. یک رمز **تازهٔ custody OpenBao**، ۲۰ تا ۱۲۸ نویسه، وارد و تکرار کنید و در
   password manager نگه دارید. این رمز Root/Intermediate/GHCR/sudo نیست.
3. سه کلید RSA-3072 OpenPGP روی WSL دستگاه خودتان ساخته می‌شوند. exportهای
   خصوصی با همان رمز محافظت می‌شوند. قبل از init، recovery از export در keyring
   تازه و رد رمز اشتباه آزمایش می‌شود. GnuPG موجود 2.4.8 روی WSL فعلی و 2.4.4
   در runner پشتیبانی می‌شوند؛ چیزی نصب یا به keyring شخصی import نمی‌شود.
4. رمز sudo را فقط در prompt مخفی محلی وارد کنید. supervisor hash منبع را پیش
   از اجرای root بررسی می‌کند؛ init/unseal deadline سه‌دقیقه‌ای و recovery
   deadline پنج‌دقیقه‌ای دارد. timeout نوشتن init برابر ۶۰ ثانیه، unseal
   برابر ۲۰ ثانیه و status/probeهای بدون تغییر سه ثانیه است؛ retry نداریم.
5. خود OpenBao سهم‌ها و root token اولیه را با public keyها رمز می‌کند؛ فقط
   ciphertext به دستگاه شما برمی‌گردد. private key و رمز custody به VPS نمی‌روند.
6. مسیر `CUSTODY_DIRECTORY` بیرون پروژه چاپ می‌شود. پس از readback دیسک، هر سه
   سهم و token از backup خصوصی در حافظه باز می‌شوند؛ plaintext نمایش/ذخیره
   نمی‌شود. فقط پنج فایل زیر برای custody لازم‌اند: `recipients.json`،
   `encrypted.json` و سه `recipient-N.secret.pgp`. در Explorer، `ID` را با
   شناسهٔ چاپ‌شده جایگزین کنید:

   ```text
   \\wsl.localhost\Ubuntu\home\coder\.local\share\hooshix-openbao-custody\ID
   ```

7. نسخه‌های رمزدار را در دو محل custody مصوب خودتان کپی و وجودشان را بررسی
   کنید؛ سپس در همان پنجره `COPIED` بنویسید. این تأیید مالک است، نه اثبات مستقل
   توزیع. سه private key با یک رمز مالک محافظت می‌شوند؛ ادعای custody چندنفره
   یا سخت‌افزاری نداریم. برای محافظت از quorum، حداقل دو سهم و کلیدهای متناظرشان
   را در دسترس عادی یک دستگاه نگذارید.
8. فقط بعد از recovery و تأیید custody، دو سهم حافظه‌ای با SSH رمزدار و JSON
   stdin وارد OpenBao می‌شوند. سهم اول باید sealed بماند؛ سهم دوم unseal می‌کند.

موفقیت فقط با `OPENBAO_ACTIVATION=Passed` و `activation-receipt-*.json` ثبت
می‌شود. فقط receipt عمومی را به دستیار بدهید؛ exportها، `encrypted.json`، رمز،
سهم یا token را در چت/Git نفرستید.

## قطع اجرا، ادامه و unseal پس از restart

### بررسی خطا بدون تکرار initialization

اگر خروجی عمومی `ACTIVATION_NATIVE_FAILED_STATE_PRESERVED` بود، ابتدا این
دستور را از checkout تمیز و merge‌شده در PowerShell اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --diagnose-only
```

فقط رمز sudo محلی خواسته می‌شود؛ رمز custody، GHCR یا CA لازم نیست. ابزار
public sourceهای hash‌شده را در cache کاربر هدف stage می‌کند؛ اجرای root فقط
preflight، وضعیت seal و وجود دو فایل journal را می‌خواند. کلیدها یا محتوای
فایل‌های custody خوانده نمی‌شوند؛ init، unseal، audit event جدید، تغییر
سرویس/فایروال/lock/داده انجام نمی‌شود. receipt عمومی در
`/home/coder/.local/share/hooshix-openbao-diagnostics` ذخیره می‌شود. خروجی
`OPENBAO_DIAGNOSTIC=Passed` فقط موفقیت بررسی است، نه فعال‌سازی.

| خروجی | اقدام بعدی |
| --- | --- |
| `preflight.status=Failed` | علت ثابت `reason` را رفع کنید؛ activation را کورکورانه تکرار نکنید |
| `initialized=false`، بدون intent/ciphertext و preflight موفق | با همان custody محلی `--resume` کنید؛ recipient تازه نسازید |
| `initialized=true` و هر دو فایل journal موجود | فقط با همان custody و رمز قبلی resume کنید؛ init دوباره اجرا نمی‌شود |
| intent موجود ولی ciphertext نیست، یا وضعیت init نامعلوم | بررسی recovery لازم است؛ marker/PV/Raft را حذف نکنید |
| `initialized=true` ولی journal/ciphertext متعلق به ابزار موجود نیست | datastore موجود حفظ شود؛ bootstrap تازه ممنوع است |

وجود فایل‌ها به‌تنهایی صحت ciphertext/هویت را ثابت نمی‌کند؛ resume بررسی
recipients، PVC و ciphertext را همچنان انجام می‌دهد. خطاهای native جدید
نام operation و نوع شکست را به‌صورت کد ثابت مشخص می‌کنند، مثلاً
`ACTIVATION_GET_STATEFULSET_OUTPUT_BOUND`، `ACTIVATION_BAO_STATUS_EXIT_FAILED`
یا `ACTIVATION_BAO_INIT_TIMEOUT`. stdout/stderr، argv و secret چاپ نمی‌شوند.

### ادامه با custody موجود

ابزار کلید تازه یا Root تازه نمی‌سازد. ciphertext و intent روی VPS هم در مسیر
root-only `/var/lib/hooshix-pki/openbao-activation` می‌مانند؛ این backup بیرون
میزبان نیست. پوشهٔ custody محلی را تا recovery/cutover پاک نکنید. پس از قطع
دانلود یا قبل از unseal با همان `ID` ادامه دهید:

در اجرای `--resume` رمز **قبلی همان custody** لازم است، نه رمز تازه؛ انتخاب
رمز تازه exportهای موجود را باز نمی‌کند. پیام عمومی شکست native به‌تنهایی
نشان نمی‌دهد initialization انجام شده یا نه. تا بررسی وضعیت هدف، installer
را تکرار نکنید و هیچ فایل intent، ciphertext یا private export را حذف نکنید.

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --rescue-and-second-session-ready --resume /home/coder/.local/share/hooshix-openbao-custody/ID
```

با همان recipients/PVC، init تکرار نمی‌شود و ciphertext قبلی برمی‌گردد. اگر
نتیجهٔ init نامعلوم و فقط intent موجود باشد، عملیات متوقف می‌شود؛ PV، Raft،
marker یا داده را برای retry حذف نکنید و از فولدر تازه برای دورزدن استفاده
نکنید. رمز اشتباه هم custody را حفظ می‌کند. پس از restart، manual Shamir دوباره
sealed می‌شود؛ resume فقط progress ناتمام حافظه‌ای را reset و دو سهم را وارد
می‌کند، نه datastore/key را. reboot واقعی در این تغییر انجام نمی‌شود.

### فقط بازیابی initialization اول با خروجی گمشده و تأیید صریح مالک

این روش نصب معمول یا reset یک مخزن Production نیست. فقط وقتی ابزار خودش
intent را با همان recipients/PVC ثبت کرده، سرویس initialized و sealed با
۳/۲ است، خروجی رمزدار در هر دو سمت وجود ندارد، سهم‌ها در دسترس نیستند، و
مالک جایگزینی **فقط همین نصب تازه** را صریحاً تأیید کرده است:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --rescue-and-second-session-ready --resume /home/coder/.local/share/hooshix-openbao-custody/ID --recover-lost-initialization
```

`ID` همان custody قبلی است؛ برای VPS فعلی
`7509bb067d814468982cc84fa50cb4d4`. رمز **قبلی custody**، `READY` و تأیید
`ARCHIVE` در پنجرهٔ محلی خواسته می‌شود. ابزار فقط StatefulSet خود OpenBao را
به صفر می‌رساند و منتظر حذف pod می‌ماند. تمام داده‌های متوقف‌شده با hash و
metadata به پوشهٔ root-only همان filesystem منتقل می‌شوند؛ داده و intent
قبلی حذف نمی‌شوند. انتقال inodeها را حفظ می‌کند و فضای یک نسخهٔ کامل دوم
نمی‌خواهد. دایرکتوری اصلی data، mount، PVC، CA و image ثابت می‌مانند. مخزن
ناشناخته، خروجی رمزدار موجود، هویت ناسازگار، لینک/فایل ویژه، بیش از ۱۲۸ مدخل،
عمق بیش از ۸ یا دادهٔ بیش از ۵۱۲MiB اجازهٔ جایگزینی نمی‌گیرد.

خروجی `OPENBAO_LOST_INITIALIZATION_RECOVERY=Passed` و receipt عمومی، دو مسیر
`data_archive` و `journal_archive` را مشخص می‌کنند. این بایگانی محلی جای backup
خارج میزبان یا کلیدهای گمشده را نمی‌گیرد؛ محتوایش را در چت/Git نگذارید و پاک
نکنید. پس از restart باید initialized=false و sealed=true باشد؛ عملیات
recovery خودش init نمی‌کند. سپس رمز sudo دوباره فقط محلی خواسته می‌شود و
activation با **همان recipients قبلی** ادامه می‌یابد. هیچ Root یا export
خصوصی تازه ساخته نمی‌شود.

در شکست/قطع recovery، `recovery-pending.json` مانع init است؛ آن را حذف یا
دستور recovery را کورکورانه تکرار نکنید. ابزار در failure فقط replica count
را برمی‌گرداند، نه اینکه دادهٔ جدید/ناتمام را overwrite کند. مسیر بازگردانی:
از VNC، پس از بررسی receipt/intent و توقف همین pod، دادهٔ replacement را
جداگانه خصوصی نگه دارید، manifest بایگانی قبلی را تطبیق دهید، تمام مدخل‌های
آن را به همان data خالی برگردانید و intent اصلی را بازگردانید؛ سپس replica=1.
این فقط bytes قبلی را بازیابی می‌کند؛ بدون سهم‌های قدیمی unseal ممکن نیست.
هیچ حذف، پاک‌سازی خودکار، format، حذف PVC یا restart میزبان/K3s انجام نمی‌شود.

## شواهد و ادامهٔ Stage 10

job موجود **Repository baseline / OpenBao TLS and Raft recovery** با OpenBao
2.6.4 پین‌شده، init PGP واقعی، export رمزدار، رمز اشتباه، decrypt از keyring
تازه، adapter واقعی HTTPS init/unseal، quorum یک/دو، منع reinit، restart، snapshot restore، ACL،
audit redaction و revoke root **مصنوعی** را اجرا می‌کند. به VPS/secret واقعی
دسترسی ندارد. unitها intent/partial failure، حفظ فایل، recipient/PVC mismatch
و منع plaintext را پوشش می‌دهند. CI یا سند، شاهد اجرای هدف نیست.
همان job، توقف store، بایگانی با حفظ bytes/metadata، initialization مخزن
جایگزین و بازگردانی بایگانی اصلی و خواندن دادهٔ اصلی را نیز آزمایش می‌کند.

snapshot ساعتی رمزدار خارج PVC/میزبان و restore هدف، bounded audit export/rotation،
scoped auth/ESO، لغو root token اولیه پس از ایجاد و آزمایش مسیر مدیریتی جایگزین،
JIT و حذف دسترسی دائمی، GitOps و دیگر گیت‌های ظرفیت/DR/برنامه هنوز لازم‌اند.
root token اولیه فقط رمزدار تحویل داده می‌شود و در این مرحله revoke نمی‌شود؛
استفادهٔ روزمره از آن ممنوع است. ترافیک Production/دادهٔ واقعی باز نمی‌شود.

مرجع: [OpenBao 2.6.4 sys/init](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/api/system/init.mdx)
و [JSON stdin write](https://github.com/openbao/openbao/blob/v2.6.4/command/write.go).

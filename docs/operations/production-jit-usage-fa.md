# راهنمای استفاده از JIT — اجازهٔ موقت مدیریت سرور

## این ابزار چه کاری می‌کند؟

JIT یعنی اجازهٔ مدیریتی برای **یک کار مشخص و زمان محدود**، نه دسترسی دائمی root.
شما کار و شمارهٔ پیگیری را انتخاب و درخواست را با کلید شخصی امضا می‌کنید؛ ابزار
هویت، امضا، زمان، تکراری‌نبودن درخواست و ثبت ممیزی را کنترل می‌کند.

این راهنما مربوط به `scripts/production/jit_broker.py` و `jit_runtime.py` است.
اعتبار کلی این مرحله را [وضعیت پروژه](../architecture/implementation-status.md)
و [ADR-0030](../adr/0030-define-production-human-jit-access-v1.md) تعیین می‌کنند.

## الان کدام قسمت قابل استفاده است؟

| قسمت | وضعیت فعلی |
| --- | --- |
| `request`: ساخت درخواست بدون اعطای دسترسی | قابل اجرای محلی |
| `bundle`: بسته‌بندی درخواست و امضای جداگانه | قابل اجرای محلی؛ فایل‌های خصوصی لازم دارد |
| `execute`: اجرای کار تأییدشده | کد موجود؛ روی VPS نصب/راه‌اندازی نشده |
| `revoke`: توقف کار همان مدیر | کد موجود؛ روی VPS نصب/راه‌اندازی نشده |
| adapter ممیزی با OpenBao و مقصد واقعی | هنوز آماده/نصب نشده؛ نبود آن اجرای واقعی را مسدود می‌کند |
| تغییر حساب مدیر به حالت بدون sudo دائمی | هنوز انجام نشده؛ باید پس از آزمون recovery انجام شود |

داشتن این فایل‌ها به معنی فعال‌شدن JIT یا آماده‌شدن Production نیست. باکت، SSH،
فایروال و پورت MCP با اجرای فرمان‌های محلی این راهنما تغییر نمی‌کنند.

## ۱. دیدن راهنما و ساخت یک درخواست محلی

در ترمینال **WSL**، بدون `sudo`:

```bash
cd /home/coder/workspace/Hooshix
python3 scripts/production/jit_broker.py --help
python3 scripts/production/jit_broker.py request \
  --action service-inspect --target caddy.service \
  --ticket OPS-DEMO --seconds 60
```

خروجی یک JSON شامل نوع کار، سرویس، هویت همین کاربر، شناسهٔ درخواست و زمان اعتبار
است. هیچ سرویسی اجرا یا restart نمی‌شود و اجازهٔ مدیریتی صادر نمی‌شود. درخواست
محلی به همین دستگاه و همین boot مربوط است؛ آن را برای اجرای روی VPS استفاده نکنید.
چون خروجی برای امضا باید دقیق باشد، انتهای آن newline ندارد؛ چسبیدن prompt ترمینال
به آخر JSON اشکال نیست. برای خوانایی می‌توانید فقط نمایش را به `python3 -m json.tool`
بدهید، ولی فایل نمایش‌داده‌شده را جای فایل اصلیِ امضا نگذارید.

کارهای فعلی فقط `service-inspect` و `service-restart` برای `caddy.service` و
`k3s.service` هستند. restart از `try-restart` استفاده می‌کند: سرویس غیرفعال را
خودسرانه روشن نمی‌کند. shell، فرمان دلخواه، SSH، فایروال، MCP و reboot در scope نیستند.
مدت باید عدد صحیح ۱۰ تا ۱۸۰۰ ثانیه باشد؛ زمان تأیید و ثبت ممیزی از همین اعتبار
کم می‌شود. ساخت درخواست به‌تنهایی اجازهٔ restart واقعی نیست.

## ۲. آزمون امن بدون استفاده از کلید یا باکت شما

```bash
python3 -m unittest discover -s scripts/production/tests -p 'test_jit_*.py'
python3 scripts/production/test_jit_systemd_expiry.py
```

فرمان اول باید `OK` بدهد. آزمون امضا از کلید مصنوعی داخل پوشهٔ موقت استفاده می‌کند
و پس از آزمون آن را پاک می‌کند؛ این کلید برای Production ثبت نمی‌شود.
فرمان دوم باید این خروجی را بدهد:

```text
NATIVE_JIT_CGROUP_EXPIRY=Passed; child expiry and operator lock release
```

این آزمون فقط یک فرایند مصنوعی کوچک را با systemd کاربر اجرا می‌کند؛ پس از حدود
۴ ثانیه فرایند پس‌زمینه متوقف و قفل آزاد می‌شود. کل آزمون حداکثر حدود ۱۰ ثانیه
زمان دارد و unit/فایل موقت را جمع می‌کند. سرویس واقعی یا provider فراخوانی نمی‌شود.
نسخهٔ دارای `--system` فقط برای runner یک‌بارمصرف CI است؛ روی VPS اجرا نکنید و
برای دورزدن محدودیت، متغیر `GITHUB_ACTIONS` را دستی روی سیستم خود تنظیم نکنید.

## ۳. بسته‌بندی امضا چگونه کار می‌کند؟

این بخش فقط قالب کار را توضیح می‌دهد؛ اگر روی این دستگاه کلید شخصی **رمزدار**
ندارید، برای تمرین از آزمون بخش ۲ استفاده کنید. کلید خصوصی را به VPS یا چت نفرستید.
کلید مصنوعی آزمون را هم به‌عنوان کلید واقعی ثبت نکنید.

فایل درخواست باید دقیقاً خروجی `request` باشد. با کلید ثبت‌شده روی دستگاه خود،
همان فایل با namespace `hooshix-jit-v1` امضا می‌شود. `ssh-keygen -Y sign` فایل
`request.json.sig` را می‌سازد؛ رمز کلید فقط در پنجرهٔ محلی وارد می‌شود. پس از امضا
نباید حتی یک فاصله یا newline به درخواست اضافه کنید.

با فرض وجود فایل‌های `request.json` و `request.json.sig` در پوشهٔ خصوصی:

```bash
python3 scripts/production/jit_broker.py bundle \
  --request-file /مسیر/پوشه-خصوصی/request.json \
  --signature-file /مسیر/پوشه-خصوصی/request.json.sig
```

`/مسیر/پوشه-خصوصی` را با مسیر واقعی جایگزین کنید؛ نمونهٔ بالا مسیر آماده نیست.
هر دو فایل باید متعلق به شما، regular و بدون symlink/hardlink، با مجوز `0600`
باشند و پوشهٔ والد باید متعلق به شما با مجوز `0700` باشد. خروجی یک envelope شامل
درخواست و امضا است؛ `bundle` هیچ مجوزی صادر نمی‌کند. از قالب JSON دستی، ویرایش
متن امضاشده و تبدیل encoding با PowerShell اجتناب کنید.

برای کار روی VPS، درخواست باید **روی همان VPS با حساب مدیر** ساخته شود، بدون
تغییر بایت به دستگاه مدیر منتقل و امضا شود و سپس envelope به broker نصب‌شده
برگردد. wrapper انتقال/نصب نهایی هنوز آماده نیست؛ فعلاً اجرای Production را از
روی دستورهای تمرینی محلی شروع نکنید. اعتبار درخواست به boot و ساعت elapsed سرور
بسته است؛ reboot یا پایان اعتبار نیازمند درخواست و تأیید تازه است.

## ۴. اجرای واقعی و لغو، پس از نصب نهایی

این بخش توضیح رابط پیاده‌شده است، **نه دستور نصب یا تأیید شروع Production**.
مسیر ثابت broker نهایی `/usr/local/libexec/hooshix-jit/jit_broker.py` است و باید
از entrypoint بازبینی‌شده با Python isolated و sudo `NOSETENV` فراخوانی شود.
مجوز sudo روی Python عمومی، فایل داخل checkout یا آرگومان/مسیر دلخواه ممنوع است.

`execute` فقط envelope را از stdin می‌گیرد؛ ورودی بیش از ۸ KiB یا ورودی بدون
پایان در ۵ ثانیه رد می‌شود. هویت از نگاشت محافظت‌شدهٔ حساب sudo گرفته می‌شود، نه
از هویت ادعایی JSON. grant پس از امضای معتبر، ثبت durable ضد replay و receipt
ممیزی واقعی ساخته می‌شود. هر کار unit یکتا دارد؛ قفل native اجازهٔ هم‌زمانی کارهای
همان مدیر را نمی‌دهد و پس از مرگ broker نیز همراه فرایند کار باقی می‌ماند.
خروجی موفق `JIT_OPERATION=Passed` است؛ خروجی شکست/ابهام را بدون بررسی تکرار نکنید.

`revoke` فقط unitهای فعال همان مدیر را متوقف می‌کند، نه سرویس عمومی یا مدیر دیگر.
توقف **قبل از** ارسال ممیزی revoke انجام می‌شود تا قطع مقصد مانع حذف privilege
نشود. خروجی موفق `JIT_REVOKE=Passed` است. پیام `job stopped; revoke audit delivery
unavailable` یعنی کار متوقف شده، ولی ثبت ممیزی کامل نشده و باید رسیدگی شود.

نتیجهٔ پایان کار و revoke ابتدا در پوشهٔ root-owned رویدادها ثبت و سپس به adapter
ممیزی تحویل می‌شود. پرشدن صف/ledger و قطع audit مجوز تازه ایجاد نمی‌کنند. منقضی‌شدن
unit کار مدیریتی را متوقف می‌کند؛ اثر مجاز یک restart قبلی را rollback نمی‌کند.

## ۵. خطاهای رایج

| پیام/وضعیت | معنی و اقدام |
| --- | --- |
| `broker is not installed` | برای execute/revoke از checkout انتظار می‌رود؛ sudo عمومی اضافه نکنید |
| `JIT=Denied` | هویت/امضا/فایل/ممیزی/زمان معتبر نیست؛ پیام و exit code را گزارش کنید، نه کلید |
| امضای ردشده | فایل پس از امضا تغییر کرده، کلید/namespace اشتباه است یا signer ثبت نشده |
| درخواست تکراری یا منقضی | همان envelope را نفرستید؛ وضعیت عملیات قبلی بررسی و درخواست تازه تأیید شود |
| خطای مجوز فایل در bundle | مالکیت و `0600`/`0700` را اصلاح کنید؛ مجوز عمومی ندهید |
| خطای user manager در آزمون محلی | آزمون را در WSL با systemd کاربر اجرا کنید؛ CI نسخهٔ native را مستقل می‌سنجد |
| `job ended; outcome audit unavailable; do not replay` | ممکن است کار انجام شده باشد؛ تکرار کورکورانه ممنوع است |

## ۶. چک‌لیست مدیر نصب و کار باقی‌مانده

### پیش‌نیاز ممیزی OS در VPS فعلی

بررسی تازهٔ ۱ اکتبر نشان داد `auditd` نصب نیست. ابزار
`install_audit_prerequisite.py` فقط برای Ubuntu 26.04 amd64 فعلی، سه بستهٔ
`auditd`، `libauparse0t64` و `libauplugin1` را با نسخهٔ دقیق
`1:4.1.2-1ubuntu0.1` نصب می‌کند؛ سیستم، SSH، MCP، sudoers و باکت را تغییر نمی‌دهد.
قبل از نصب، transaction بدون upgrade/remove و سقف دانلود ۱۶ MiB بررسی می‌شود؛
حداقل ۲۵۶ MiB فضای خالی لازم است. مخازن و کنترل امضای APT تغییر نمی‌کنند.

در **Windows PowerShell**، از ریشهٔ checkout:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\production\run-audit-prerequisite.ps1
```

این فرمان فقط preflight است و باید `"preflight": "Passed"` و `"applied": false`
بدهد. برای نصب همان پیش‌نیاز:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\production\run-audit-prerequisite.ps1 -Install
```

رمز sudo فقط همان‌جا وارد شود. launcher کد عمومی را موقتاً منتقل، هش آن را روی همان
بایت‌های اجراشونده بررسی و Python isolated اجرا می‌کند؛ هیچ secret منتقل نمی‌شود.
فقط فایل موقت یکتای همین launcher پس از پایان پاک می‌شود. هنگام نصب dpkg پنجره را
نبندید؛ در صورت شکست، نصب ممکن است جزئی باشد و نباید کورکورانه تکرار شود.
اگر اجرای UNC در سیاست Windows محدود باشد، فقط این دو فایل عمومی را به پوشهٔ موقت
Windows کپی و همان فرمان را از آن پوشه اجرا کنید؛ سیاست execution عمومی را تغییر ندهید.

خروجی موفق باید `"applied": true` و `daemon`، `kernel` و `log_bounds` برابر `Passed`
داشته باشد. پیکربندی پیش‌فرض بسته باید log با حدود ۸ MiB و پنج فایل و رفتار
`SUSPEND` برای خطای دیسک داشته باشد؛ ابزار تنظیمات سفارشی را بازنویسی نمی‌کند.
این **ممیزی کامل نیست**: rotation ممکن است رویداد قدیمی را حذف کند؛ بدون exporter،
پوشش ruleها، صف محافظت‌شده و کنترل backlog، JIT همچنان بسته می‌ماند.
`audit_readiness` و `jit_readiness` حتی پس از نصب `Not verified` می‌مانند.

### به‌روزرسانی میزبان در ۲ اکتبر ۲۰۲۶

`auditd` فعال است و ۲۳ rule میزبان بارگذاری شده‌اند، اما این فقط پوشش محلی و همان boot است. MariaDB فعلاً فقط اتصال‌ها را ثبت می‌کند. `sudo-rs` نصب‌شده گزینه‌های متداول I/O logging را نمی‌پذیرد؛ پروندهٔ آزمایشی نامعتبر خارج از sudoers نگهداری شده و policy اصلی دوباره معتبر است. مراحل، آزمون‌ها و محدودیت‌ها در [راهنمای ممیزی میزبان](production-host-audit-fa.md) نوشته شده‌اند. تا export مقاوم خارج از VPS، OpenBao، JIT و آزمون recovery/قطع مقصد برقرار نشوند، `execute` روی production مجاز نیست و sudo دائمی حذف نمی‌شود.

candidate جدید audit برای حذف record آرگومان‌های `EXECVE`/`PROCTITLE` زیر بازبینی است؛ تعداد ۲۳ متعلق به مشاهدهٔ قبلی میزبان است و candidate ۲۵ rule دارد. verifier فقط تطبیق همان boot را بررسی می‌کند و جایگزین پوشش event، export و health adapter JIT نیست.

### اگر نصب تمام شد ولی بررسی خطا داد

بسته‌ها را دوباره نصب نکنید. فقط بررسیِ بدون تغییر را اجرا کنید:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\production\run-audit-prerequisite.ps1 -VerifyOnly
```

این mode هیچ فرمان APT یا تغییر تنظیمات اجرا نمی‌کند و یک receipt عمومی کوچک در
کنار launcher ذخیره می‌کند؛ مسیر آن با `PUBLIC_RECEIPT=` نمایش داده می‌شود.
`-Install` و `-VerifyOnly` هم‌زمان مجاز نیستند. اگر بررسی شکست بخورد، نام مرحلهٔ
`packages`، `log_bounds`، `daemon` یا `kernel` گزارش می‌شود، نه محتوای config/secret.
در اجرای ۱ اکتبر، خروجی چندکلمه‌ای `loginuid_immutable 0 unlocked` باعث شکست parser
قدیمی بود؛ parser اصلاح شد و بررسیِ فقط‌خواندنی روی VPS همهٔ این مراحل را پاس کرد.
دانلود واقعی نصب اولیه ۲۹۸ kB و افزایش فضای نصب ۹۷۵ kB بود؛ نصب تکرار نشد.

### آیا دیگر نمی‌توانم مدیر سرور باشم؟

خودِ برنامهٔ `sudo` حذف نمی‌شود. هدف، جایگزینی مجوز نامحدود و دائمی با اجازهٔ
موقت برای کار مشخص است. ابزار فعلی فقط inspect/restart سرویس‌های محدود دارد و
هنوز جایگزین کامل کارهای مدیریتی مالک نیست. پیش از cutover باید تمام کارهای لازم
مالک نقش/فرمان مناسب، approval، audit و expiry داشته باشند و مسیر recovery واقعاً
آزموده شود. تا آن موقع sudo دائمی حفظ می‌شود؛ VNC جای مسیر روزمرهٔ مدیریت نیست.

رفتار APT و auditd با Context7 از مستندات رسمی
[APT](https://github.com/debian/apt/blob/main/doc/apt-get.8.xml) و
[Linux audit](https://github.com/linux-audit/audit-userspace/blob/master/init.d/auditd.conf)
بررسی شد؛ نسخهٔ نصب‌شده و پیکربندی میزبان هم جداگانه سنجیده می‌شوند.
قالب status نسخهٔ ۴.۱.۲ نیز در
[سورس رسمی auditctl](https://github.com/linux-audit/audit-userspace/blob/v4.1.2/src/auditctl-listing.c)
بررسی شد؛ `enabled` یا `lost` تکراری/نامعتبر همچنان fail-closed است.

این موارد کار نصب‌کننده است، نه لازم‌کردن نوشتن تنظیمات امنیتی توسط مالک:

- کد در مسیر ثابت root-owned با فایل‌های `0644` و والدهای بدون write عمومی؛
  نگاشت نام/UID در `/etc/hooshix/jit/operator.json` و signer دقیق در
  `/etc/hooshix/jit/allowed_signers`، هر دو `0600` و پوشهٔ `0700`؛
- پوشه‌های `/var/lib/hooshix/jit/{replay,private,events}` و
  `/run/hooshix/jit/locks`، root-owned و `0700`؛ runtime directory در هر boot ساخته شود؛
- adapter واقعی root-owned `audit-deliver` با `0755`، سلامت audit OS، پوشش قوانین،
  ارسال/بازیابی backlog، receipt هم‌هش/version ID و alert؛ اسرار از OpenBao، نه fallback فایل؛
- کنترل backlog ناقص پیش از grant تازه، bounded retention/پاک‌سازی reviewed ledger؛
- entrypoint sudo محدود، inventory کلید رمزدار، تست expiry/revoke/قطع broker و
  suspend/reboot در محیط مربوط؛ سپس cutover بدون standing sudo با recovery برقرار.

این موارد هنوز روی VPS commissioning نشده‌اند. تأیید مالک دربارهٔ باکت ثبت شده
و طبق دستور او probe تازه‌ای انجام نمی‌شود؛ این راهنما درخواست بررسی مجدد باکت نیست.

### اتصال ممیزی: کدام قسمت آماده است؟

کد `scripts/production/parspack_audit_transport.py` مسیر ارسال به باکت جاری
`c892683` را پیاده می‌کند: یک فایل کوچک با نام یکتا می‌فرستد، همان نسخه را
بازمی‌خواند و فقط با محتوای کاملاً یکسان و version ID معتبر رسید می‌دهد.
نام فایل از UUID رکورد durable محلی می‌آید تا حتی ارسال مبهم قابل پیگیری باشد؛
نصب‌کننده باید رکورد را پیش از فراخوانی transport ثبت کند، نه بعد از ارسال.
خطا، timeout، نسخهٔ null، redirect یا تفاوت محتوا رسید موفق نمی‌سازند؛ ارسال
مبهم خودکار تکرار نمی‌شود. این کد تنظیمات باکت را probe یا تغییر نمی‌دهد.
هر فایل حداکثر ۸ KiB و زمان کل ارسال/بازخوانی ۷ ثانیه است؛ فایل‌های موقت
خودکار بسته/حذف می‌شوند و خروجی provider یا secret چاپ نمی‌شود.

این یک کتابخانهٔ داخلی است، نه فرمانی برای اجرای دستی شما و نه نصب JIT.
credential باید از materialization محافظت‌شدهٔ OpenBao برسد؛ فایل خصوصی
bootstrap شما به fallback runtime تبدیل نشده است. ابتدا باید محل نگهداری
امن سهم‌های بازیابی OpenBao مشخص و secret authority با snapshot/recovery
راه‌اندازی شود. بعد از نصب exporter/کنترل سلامت و backlog، نصب‌کننده این
transport را به broker وصل می‌کند. تا آن زمان sudo و SSH/MCP فعلی تغییر نمی‌کنند.

### OpenBao: وضعیت و مراحل راه‌اندازی

شما وجود دو محل امن و مستقل برای نگهداری مواد بازیابی را تأیید کردید؛ این انتخاب
ثبت شده و نیازی به فرستادن کلید در گفتگو نیست. هنوز هیچ سهم واقعی یا root token
تولید نشده است. نگهداری همهٔ سهم‌ها روی VPS یا Git مجاز نیست؛ آستانهٔ بازکردن
OpenBao دو سهم از سه سهم است. مالک باید مواد بازیابی را خارج از سرور نگهداری
و دریافت/امکان استفاده از آن‌ها را هنگام راه‌اندازی تأیید کند.

فایل‌های `infrastructure/production/secrets/openbao-image.json` و
`openbao-server.json` نسخهٔ دقیق، digest عمومی، Raft، TLS و ممیزی بدون مقدار خام
را آماده می‌کنند. این فایل‌ها installer یا manifest قابل apply نیستند و نباید
به‌جای بستهٔ نصب، دستی روی production اجرا شوند.

renderer عمومی foundation اکنون در `scripts/production/render_openbao_candidate.py`
قرار دارد. این خروجی هنوز در ریشهٔ فعال Argo CD نیست و مسیرهای client/ESO/host
را عمداً باز نمی‌کند. راهنمای استفاده و تفاوت «candidate» با استقرار واقعی در
[راهنمای OpenBao](production-openbao-fa.md) آمده است؛ sudo دائمی تغییر نمی‌کند.

آزمون **OpenBao TLS and Raft recovery** در GitHub با کلیدهای موقتی و مصنوعی
اجرا می‌شود: بازشدن با دو سهم، بسته‌ماندن با یک سهم، restart، snapshot و restore
روی نمونهٔ خالی و جدا، محدودبودن token خواندن، نبود secret در audit و لغو root
token اولیه. این تست فقط روی runner موقت اجرا می‌شود؛ هیچ دانلود image یا
حجم بزرگ داده‌ای روی سیستم شما ایجاد نمی‌کند. containerها، volumeهای موقتی و
فایل‌های fixture پس از آزمون حذف می‌شوند؛ کلید واقعی را در CI قرار ندهید.

راه‌اندازی واقعی هنوز به این ترتیب انجام می‌شود:

1. بستهٔ Kubernetes/GitOps بازبینی‌شده با PVC، TLS خصوصی معتبر، منابع محدود،
   هویت/شبکهٔ محدود و evidence امضا/SBOM آماده و در staging آزموده شود.
2. همان artifact روی VPS نصب شود؛ عملیات sudo فقط در پنجرهٔ محلی شما انجام شود.
3. init فقط یک بار انجام و سهم‌ها در مسیر خصوصی مالک تحویل داده شوند؛ خروجی
   کامل init، سهم‌ها و token در گفتگو، لاگ یا CI چاپ نشوند. پاسخ مبهم init تکرار
   کورکورانه نشود؛ قبل از ادامه وضعیت و مواد دریافت‌شده رسیدگی شوند.
4. snapshot رمز‌شدهٔ ساعتی در مقصد خارج از PVC/سرور ذخیره و restore مستقل واقعاً
   آزموده شود؛ صرف موفقیت backup کافی نیست. `snapshot-force` فقط در fixture خالی
   CI استفاده می‌شود و دستور عمومی restore production نیست.
5. پس از policy محدود، secret sync و لغو root token اولیه، exporter ممیزی به
   materialization محافظت‌شدهٔ OpenBao متصل شود؛ سپس نصب/آزمون JIT و cutover انجام شود.

تا اجرای این مراحل، این بخش و کل Stage 10 completed محسوب نمی‌شوند. ممیزی
OpenBao در فایل محلی باید exporter، retention و کنترل فضای دیسک محافظت‌شده داشته
باشد؛ configuration فعلی به‌تنهایی اجازهٔ استفادهٔ واقعی از secrets نمی‌دهد.

ساختار audit با [مستندات رسمی همان tag نسخهٔ ۲.۶.۴](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/configuration/audit.mdx)
و رفتار health/init با Context7 و منابع رسمی OpenBao بررسی شده است؛ مثال latest
جای نسخهٔ نصب‌شده را نمی‌گیرد.

آزمون‌های این قسمت با `make production-test` اجرا می‌شوند و pipeline موجود
هم آن‌ها را خودکار کشف می‌کند؛ هیچ کلید واقعی را در GitHub قرار ندهید.

رفتار امضای SigV4/config توسط Context7 از مستندات رسمی curl و رفتار checksum/
version-specific GET از [PutObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)
و [GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)
بررسی شده است. این منابع قرارداد مرجع S3 هستند، نه evidence اجرای پارس‌پک.

## ۷. CI را از کجا ببینم؟

در صفحهٔ PR، تب **Checks** را باز کنید. در **Repository baseline**، job
**Repository structure verify**، مرحلهٔ **Verify native JIT expiry on disposable
runner** و مرحلهٔ **Verify production** نتیجهٔ native و آزمون‌های واحد را نشان می‌دهند.
هر push این آزمون‌ها را خودکار اجرا می‌کند؛ نیازی به اجرای دستی کل تست‌های سرویس‌ها
یا قرار دادن credential در GitHub نیست. موفقیت CI به‌تنهایی نصب روی VPS را اثبات نمی‌کند.

## منابع رفتار ابزارها

- [امضا و verify در OpenSSH](https://man.openbsd.org/ssh-keygen)
- [تنظیمات service در systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html)
- [قفل native در util-linux](https://github.com/util-linux/util-linux/blob/master/sys-utils/flock.1.adoc)

Context7 برای OpenSSH/systemd/util-linux استفاده شده است. میزبان مشاهده‌شده systemd
259.5 دارد؛ semantics نسخهٔ نصب‌شده و آزمون native نیز ملاک هستند، نه صرفاً مستندات latest.

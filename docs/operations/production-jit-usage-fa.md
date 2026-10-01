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

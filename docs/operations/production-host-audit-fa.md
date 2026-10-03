# ممیزی میزبان Stage 10 — وضعیت و راهنمای اجرای دوباره

این راهنما برای Ubuntu 26.04 روی VPS فعلی است. فایل‌های نمونه در `infrastructure/production/host/` قرار دارند. نصب `auditd`، ثبت محلی، ارسال خارج از VPS و JIT چهار کنترل جدا هستند. تا وقتی خروجی audit به مقصد مقاوم در برابر تغییر با credential تحویلی OpenBao نرسیده، grant مدیریتی جدید نباید فعال شود و Production Readiness برابر `Not verified` است.

## وضعیت مشاهده‌شده در ۲ اکتبر ۲۰۲۶

- `auditd`، K3s، WireGuard، MariaDB و rsyslog فعال بودند. ۲۳ rule از فایل `70-hooshix.rules` در kernel بارگذاری شد؛ `enabled 1`، `lost 0` و `backlog 0` مشاهده شد. پس از reboot دوباره آزمون نشده است.
- ruleها برای اجرای فرایند انسانی، تغییر privilege و تغییر تنظیمات میزبان انتخاب شده‌اند. وجود rule اثبات تولید event نیست؛ پوشش ورود/احراز هویت، API Kubernetes و عملیات دیتابیس آزمون جدا می‌خواهد. `-e 2` فعال نشده تا پیش از اتمام commissioning، اصلاح کنترل‌شده ممکن باشد. کل پوشهٔ دیتای K3s و syscallهای حذف/تغییر مجوز در همهٔ سیستم watch نشده‌اند تا حجم audit بدون اندازه‌گیری ظرفیت بالا نرود.
- افزونهٔ MariaDB `SERVER_AUDIT` فعال و فایل `/var/log/mariadb/audit.log` با مالک `mysql:adm` و مجوز `0640` است. تنها `CONNECT` ثبت می‌شود. متن SQL برای DDL/DCL ممکن است credential یا دادهٔ خصوصی داشته باشد؛ ثبت آن تا وجود راه امن و تست leak ممنوع است. اتصال واقعی و یک ساخت/حذف جدول موقت فقط برای آزمون گذرای plugin انجام شد؛ SQL audit پس از آزمون خاموش شد.
- نسخهٔ نصب‌شدهٔ sudo، `sudo-rs 0.2.13-0ubuntu1.2` است. گزینه‌های `log_input`، `log_output`، `iolog_dir` و `logfile` را نمی‌پذیرد. فایل آزمایشی نامعتبر از `/etc/sudoers.d` خارج شد و `visudo -cf /etc/sudoers` دوباره Passed شد. **sudo I/O/session هنوز پیاده‌سازی نشده است.** تا بررسی راه سازگار، تکیه به command log یا shell history برای این gate ممنوع است.
- OpenBao روی VPS نصب نیست؛ export خارج از VPS، append-only/retention واقعی، قطع sink، audit Kubernetes API و عملیات privileged دیتابیس هنوز `Not verified` هستند. sudo دائمی موجود به‌خاطر نبود JIT و مسیر بازیابی حذف نشده است.

## خواندن وضعیت بدون نمایش محتوای audit

در نشست مدیریت خصوصی سرور، فقط بعد از احراز sudo در همان نشست:

```bash
sudo auditctl -s
sudo auditctl -l | wc -l
sudo augenrules --check
sudo visudo -cf /etc/sudoers
sudo mariadb --protocol=socket -NBe "SHOW GLOBAL VARIABLES LIKE 'server_audit_events'; SHOW GLOBAL STATUS LIKE 'server_audit_active';"
sudo systemctl is-active auditd mariadb k3s wg-quick@wg-hooshix
```

محتوای audit، فایل SQL، خروجی `ausearch` و اطلاعات خصوصی را در chat، Git یا CI کپی نکنید. `lost` باید صفر باشد؛ افزایش آن یا پرشدن دیسک موجب توقف grant جدید JIT و رسیدگی فوری است. وجود ۲۳ rule فقط وضعیت همین boot است؛ بعد از reboot و تغییر package/مسیرها دوباره با فایل مورد انتظار مقایسه کنید.

## تغییر زیر بازبینی در ۳ اکتبر ۲۰۲۶

بررسی read-only واقعی با نسخهٔ قبلیِ بازبینی‌شده (۲۳ rule و hash `e47626ff6c2ff1813e0c6c1c3254b11abdbfb9a89eb0fd71ad6fd5c2a6076b82`) در `2026-10-03T06:16:35Z` Passed شد: policy نصب‌شده و kernel برابر، `lost=0` و `backlog=0`. این مقایسه فقط baseline قبلی را تأیید می‌کند؛ candidate جدید یا leak/export را تأیید نمی‌کند. receipt عمومی در `/home/hooshixadmin/hooshix-audit-receipt-20261003.json` است.

نسخهٔ قبلی ruleها ممکن است آرگومان فرمان را در `EXECVE` و `PROCTITLE` ثبت کند؛ آرگومان می‌تواند secret داشته باشد. نسخهٔ repository دو فیلتر محدود برای حذف همین نوع record دارد؛ `SYSCALL` و metadata اجرای فرایند، نتیجه، executable، privilege و تغییر config حذف نمی‌شوند. شمار candidate جدید ۲۵ است. این فیلترها جایگزین sudo session audit نیستند و قبل از نصب و آزمون canary روی kernel واقعی، حفاظت از آرگومان **Not verified** است. این policy فعلاً b64 است؛ پوشش اجرای b32 و processهای با loginuid نامعتبر هنوز gate باز دارند.

ابزار [verify_host_audit.py](../../scripts/production/verify_host_audit.py) فقط وضعیت را می‌خواند. تعداد یکسان rule کافی نیست؛ فایل نصب‌شده باید با SHA-256 نسخهٔ بازبینی‌شده برابر باشد و مجموعهٔ ruleهای فعال نیز دقیقاً با آن تطبیق کند. symlink، hardlink، parent قابل‌نوشتن برای دیگران، BOM/CRLF، rule اضافه/کم/تکراری، daemon غیرفعال، lost غیرصفر یا backlog از ۷۵٪ به بالا شکست است. exit صفر فقط سازگاری فایل و kernel همان boot را ثابت می‌کند؛ پوشش event، export، JIT و Production همواره `Not verified` باقی می‌مانند.

هش candidate را روی checkout بازبینی‌شده بخوانید؛ هش فایل نصب‌شدهٔ ناشناخته را صرفاً برای سبزشدن نتیجه جای reference نگذارید:

```bash
sha256sum infrastructure/production/host/audit.rules
# مقدار public بالا را جای <reviewed-sha256> قرار دهید؛ این مقدار secret نیست.
sudo python3 -I /path/to/reviewed/verify_host_audit.py \
  --expected-rules-sha256 <reviewed-sha256>
```

ابزار read-only و receipt آن احراز grant نیست و نباید health adapter نهایی JIT محسوب شود. در میزبان فعلی package اصلی `sudo` نیز کنار `sudo-rs` نصب است؛ تغییر alternative یا policy تا بررسی نسخه، staging، عدم نشت ورودی و rollback انجام نمی‌شود. برای OpenBao نیز metadata upstream به‌تنهایی اجازهٔ deploy نمی‌دهد؛ promotion امضاشده، staging همان digest و custody/recovery واقعی لازم هستند.

## نصب مجدد روی سرور دیگر

1. پیش از نصب، نسخهٔ OS، `auditd`، MariaDB و sudo را بررسی کنید. مسیرها را با سرور جدید تطبیق دهید؛ نمونه را کورکورانه نصب نکنید. از `/etc/audit/rules.d`، `/etc/mysql/mariadb.conf.d` و وضعیت سرویس‌ها backup خارج از مسیر فعال بگیرید.
2. فایل‌های [audit.rules](../../infrastructure/production/host/audit.rules) و verifier را به‌صورت binary با UTF-8 بدون BOM و LF منتقل کنید؛ pipe متنی Windows ممکن است bytes را تغییر دهد. پیش از نصب hash مبدأ/مقصد باید یکسان باشد. ruleها را در `/etc/audit/rules.d/70-hooshix.rules` با مالک root و مجوز `0640` نصب کنید. `augenrules --check` فقط نیاز به بازسازی را بررسی می‌کند و syntax check یا runtime proof نیست. سپس `augenrules --load` اجرا کنید و exit code اصلی را بدون مخفی‌کردن در pipeline بگیرید؛ verifier باید مجموعهٔ دقیق ruleها، digest و kernel health را تأیید کند. در نصب جدید فقط پس از canary تولید event و عدم ثبت آرگومان می‌توان حفاظت محلی را پذیرفت. مستند رسمی `auditctl 4.1.2` comment ابتدایی را پشتیبانی می‌کند؛ مشکل انتقال متن/BOM/CRLF را با حذف comment اشتباه نگیرید.
3. MariaDB را بدون خاموش کردن سرویس بررسی کنید، plugin بومی `server_audit.so` و مجوزهای `/var/log/mariadb` را تأیید کنید. [mariadb-audit.cnf](../../infrastructure/production/host/mariadb-audit.cnf) را فقط در مسیر config همان نسخه نصب کنید. قبل از restart، `mariadbd --verbose --help` باید بدون خطای config موفق شود؛ restart را در پنجرهٔ نگهداری و با backup/rollback آزموده انجام دهید. مقدار `server_audit_events` باید `CONNECT` و status `Server_audit_active` باید `ON` باشد.
4. برای سیستم‌های دارای `sudo-rs`، فایل sudoers استاندارد sudo را وارد نکنید. ابتدا راه سازگار I/O/session recording را در staging با آزمون عدم نشت رمز و بازیابی تأیید کنید. برای audit خارج از VPS نیز credential خصوصی را فقط از OpenBao materialize کنید؛ فایل bootstrap یا مقدار chat مجاز نیست.
5. سپس export، retention/حذف‌ناپذیری، بازیابی، قطع مقصد، reboot، Kubernetes API audit، database privileged actions، JIT expiry/revoke و حذف standing sudo را جداگانه با receipt بدون secret آزمایش کنید. تا پاس شدن همهٔ اینها stage همچنان باز است.

بازگشت کنترل‌شده: نسخهٔ قبلی فایل‌های config و rules را فقط پس از بررسی diff و مسیر recovery برگردانید؛ `auditctl -D` بدون بارگذاری فوری policy معتبر، audit را بی‌پوشش می‌کند. restartهای بی‌برنامه روی سرور ایمیل/دیگر برنامه‌ها انجام ندهید.

محدودیت: چرخش local log بدون export قابل‌اعتماد می‌تواند evidence را از بین ببرد. accountهای عضو `adm` و مالک `mysql` دسترسی لازم به فایل مربوط دارند؛ این فایل‌ها tamper-resistant نیستند. هیچ secret را در argv، نام فایل، SQL یا interactive session recording وارد نکنید. فیلتر kernel همهٔ مسیرهای log از جمله command log sudo را پاک‌سازی نمی‌کند؛ مسیرهای دریافت secret باید stdin/socket محافظت‌شده و آزمون leak مستقل داشته باشند.

## منابع نسخهٔ بررسی‌شده

- `linux-audit/audit-userspace`، tag `v4.1.2`، فایل `docs/auditctl.8`: commentها، exclusion filter، قواعد b32/b64 و syntax.
- `trifectatechfoundation/sudo-rs`، tag `v0.2.13`، فایل `README.md`: اختلاف با sudo، syslog و timestamp مخصوص tty.
- مشاهدهٔ host در ۳ اکتبر: `auditd 1:4.1.2-1ubuntu0.1`، `sudo-rs 0.2.13-0ubuntu1.2`، package `sudo 1.9.17p2-1ubuntu3.1` و MariaDB `1:11.8.6-5ubuntu0.1`. نسخهٔ package به‌تنهایی evidence امنیت یا ضبط I/O نیست. Context7 tool در این نشست قابل فراخوانی نبود؛ منابع رسمی tag مربوط مستقیماً خوانده شدند.

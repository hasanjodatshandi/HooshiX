# WireGuard برای مدیریت HooshiX — راهنمای فارسی

این راهنما ساخت یک اتصال مدیریت مستقل برای نصب جدید و نگهداری اتصال موجود را پوشش می‌دهد.
مرجع امنیتی آن ADR-0030 و ADR-0043 است. برقرارشدن VPN تأیید Production نیست.

## ۱. به چه درد می‌خورد؟

WireGuard یک مسیر شبکه رمزدار بین رایانه مدیر و سرور است. به‌جای اینکه SSH و ابزار مدیریت
در دسترس همه اینترنت باشند، فقط دستگاه‌های ثبت‌شده به آدرس مدیریت سرور می‌رسند.
این VPN برای مدیریت است؛ اینترنت کاربران سایت از آن عبور نمی‌کند و مدل AI روی آن اجرا نمی‌شود.
کلید WireGuard فقط ورود به شبکه را ممکن می‌کند؛ مجوز SSH یا sudo یا Kubernetes نمی‌دهد.

مسیر مصوب: دستگاه مدیر → WireGuard → کلید SSH مستقل → دسترسی JIT محدود و audit خارج از میزبان.
در profile تک‌سرور، FIDO2 اختیاری است؛ کلید نرم‌افزاری Ed25519 با رمز و مجوز فایل محدود مجاز است.
قواعد کامل در [سیاست دسترسی](../../infrastructure/production/host/access-policy.json) و
[ADR-0043](../adr/0043-define-production-network-trust-boundaries-v1.md) هستند.
این راهنما جای آن سیاست‌ها یا installer خودکار firewall نیست.

## ۲. اتصال فعلی را از نو نسازید

برای اتصال موجود Windows، WireGuard را باز کنید؛ tunnel با نام `hooshix-server` باید فعال باشد.
در PowerShell:

```powershell
Get-Service -Name 'WireGuardTunnel$hooshix-server'
ssh -o BatchMode=yes -o ConnectTimeout=10 hooshix-server hostname
```

`Running` و پاسخ نام سرور یعنی سرویس Windows و مسیر SSH کار می‌کنند؛ نه اینکه همه gateها Passed هستند.
IP مدیریت فعلی `10.77.47.1` است؛ alias فعلی از SSH پورت `22022` استفاده می‌کند.
این وضعیت bootstrap، مسیر نهایی مدیریت مصوب روی TCP/22 نیست. فایل config محلی و `client.key` خصوصی‌اند؛ محتویاتشان را
چاپ، screenshot یا در Git کپی نکنید. کلیدهای مشتری جدید باید مستقل باشند.
اگر اتصال فعلی کار می‌کند، مراحل ساخت زیر را روی آن دوباره اجرا نکنید.

## ۳. اطلاعات نصب جدید

پیش از ساخت، این جدول را با مقادیر واقعی نصب پر کنید. آدرس‌های زیر فقط نمونه‌اند؛
نباید با LAN، VPN دیگر، Pod/Service CIDR یا نصب مشتری دیگر تداخل داشته باشند.

| مورد | نمونه |
| --- | --- |
| IP عمومی/دامنه endpoint سرور | `SERVER_PUBLIC_IP` |
| پورت WireGuard | UDP `51820` |
| interface سرور | `wg-hooshix` |
| آدرس مدیریت سرور | `10.77.47.1/32` |
| آدرس دستگاه اول | `10.77.47.2/32` |
| SSH account | حساب انسانی اختصاصی همان نصب |

نسخه package و kernel واقعی را ثبت کنید. نسخه مصوب باید برای OS همان میزبان بررسی شود؛
شماره package سرور قبلی را روی Ubuntu متفاوت تحمیل نکنید.
موجودی peer شامل مالک، دستگاه، public key، IP `/32`، زمان تأیید و روش revoke است؛ private key ندارد.

## ۴. ساخت کلید دستگاه Windows

1. WireGuard for Windows را از منبع رسمی نصب کنید؛ امضای ناشر و نسخه نصب را بررسی و ثبت کنید.
2. در برنامه، `Add Tunnel` سپس `Add empty tunnel` را انتخاب کنید.
3. نام تازه مثل `customer-alpha-admin` بدهید. برنامه کلید مستقل دستگاه را تولید می‌کند.
4. فقط **Public key** را برای ثبت peer به مدیر سرور بدهید. مقدار `PrivateKey` همان دستگاه را حفظ کنید.
5. فعلاً tunnel را فعال نکنید؛ ابتدا peer سرور باید ثبت شود.

private key را از سرور تولید و بین دستگاه‌ها توزیع نکنید. export config شامل private key است؛
اگر برای بازیابی لازم شد، آن را فقط در backup رمزدار با دسترسی محدود و خارج Git نگه دارید.

## ۵. ساخت سمت سرور جدید

این بخش فقط در نشست مجاز مدیریتی **سرور جدید** اجرا می‌شود. package رسمی `wireguard-tools`
و پشتیبانی kernel باید قبلاً با نسخه و integrity بررسی‌شده نصب باشند. نشست فعلی را باز نگه دارید.
فعال‌سازی، تغییر firewall و rollback باید از قبل با مالک همان میزبان بررسی شده باشند.

ابتدا بدون تغییر:

```bash
hostname -f
uname -r
dpkg-query -W wireguard-tools
ip -brief address
ip route
ss -lun
systemctl is-active wg-quick@wg-hooshix
```

وجود interface/config/کلید قبلی یا اشغال UDP انتخابی یعنی توقف و بررسی؛ overwrite نکنید.
روی Ubuntu جدید، پس از بررسی نسخه هدف در مخزن رسمی همان OS، نصب package به شکل زیر است؛
`REVIEWED_PACKAGE_VERSION` را با نسخه بررسی‌شده جایگزین کنید:

```bash
sudo apt-get install --no-install-recommends wireguard-tools=REVIEWED_PACKAGE_VERSION
```

در shell مجاز root، بدون `set -x` و بدون ضبط محتوای فایل خصوصی، کلید را بسازید:

```bash
set -euo pipefail
test "$(id -u)" -eq 0
test ! -e /etc/wireguard/wg-hooshix.conf
test ! -e /etc/wireguard/wg-hooshix.key
test ! -e /etc/wireguard/wg-hooshix.pub
test ! -L /etc/wireguard
install -d -m 0700 -o root -g root /etc/wireguard
umask 077
set -o noclobber
wg genkey > /etc/wireguard/wg-hooshix.key
wg pubkey < /etc/wireguard/wg-hooshix.key > /etc/wireguard/wg-hooshix.pub
chmod 0600 /etc/wireguard/wg-hooshix.key /etc/wireguard/wg-hooshix.pub
```

اگر نیمه‌کاره شکست خورد، فایل‌های موجود را بررسی کنید؛ این دستورها عمداً retry کور را رد می‌کنند.
فایل `/etc/wireguard/wg-hooshix.conf` را با editor مدیریتی مجاز بسازید. فقط placeholder
public key را جایگزین کنید؛ private key داخل config نوشته نمی‌شود:

```ini
[Interface]
Address = 10.77.47.1/32
ListenPort = 51820
PostUp = wg set %i private-key /etc/wireguard/wg-hooshix.key

[Peer]
PublicKey = REPLACE_WITH_WINDOWS_PUBLIC_KEY
AllowedIPs = 10.77.47.2/32
```

مالک config باید root و mode آن `0600` باشد. `PostUp` فرمان اجرا می‌کند؛ config فقط باید
توسط مدیر مجاز قابل نوشتن باشد. `SaveConfig` اضافه نکنید تا تغییر runtime جای فایل بررسی‌شده ننشیند.
برای این اتصال نیازی به NAT، IP forwarding، route همه اینترنت یا دسترسی به subnet کلاستر نداریم.

پس از بررسی فایل، UDP و rollback، در نشست مجاز:

```bash
chmod 0600 /etc/wireguard/wg-hooshix.conf
systemctl enable --now wg-quick@wg-hooshix
systemctl is-active wg-quick@wg-hooshix
wg show wg-hooshix public-key
```

آخرین دستور فقط **public key** سرور را می‌دهد. آن را با کانال مدیریتی مورد اعتماد به Windows ببرید.
خواندن public key با `cat` توسط کاربر عادی ممکن است به‌دلیل مجوز parent directory رد شود؛
برای حل آن directory را عمومی نکنید. در نشست معمول از `sudo wg show wg-hooshix public-key` استفاده کنید.
`wg showconf`، `wg show ... dump` و چاپ config خصوصی خروجی مناسبی برای chat/CI نیستند.

## ۶. تنظیم Windows و اتصال

در editor tunnel Windows، `PrivateKey` تولیدشده مرحله ۴ را حفظ کنید و قسمت‌های زیر را اضافه کنید.
مقادیر نمونه و public key سرور را برای همان نصب جایگزین کنید:

```ini
[Interface]
# PrivateKey تولیدشده همین دستگاه در این بخش باقی می‌ماند.
Address = 10.77.47.2/32

[Peer]
PublicKey = REPLACE_WITH_SERVER_PUBLIC_KEY
Endpoint = SERVER_PUBLIC_IP:51820
AllowedIPs = 10.77.47.1/32
PersistentKeepalive = 25
```

این block یک فایل آماده import نیست؛ private key را از template یا chat نگیرید.
`AllowedIPs` فقط سرور مدیریت را route می‌کند؛ `0.0.0.0/0` یا CIDR کلاستر اضافه نکنید.
keepalive برای دستگاه پشت NAT مفید است؛ public key دو طرف باید مستقل از فایل انتقالی تأیید شود.
Save و Activate کنید. در PowerShell:

```powershell
Test-NetConnection 10.77.47.1 -Port 22
ssh -o ConnectTimeout=10 YOUR_USER@10.77.47.1 hostname
```

username را عوض کنید. fingerprint کلید SSH سرور را از کانال مورد اعتماد بررسی کنید؛
`StrictHostKeyChecking=no` استفاده نکنید. برای alias دلخواه، در فایل محلی `%USERPROFILE%\.ssh\config`:

```sshconfig
Host customer-alpha-server
    HostName 10.77.47.1
    User YOUR_USER
    Port 22
    IdentitiesOnly yes
    IdentityFile ~/.ssh/YOUR_APPROVED_SSH_KEY
```

بعداً `ssh customer-alpha-server` کافی است. این کلید SSH با کلید WireGuard فرق دارد؛
برای privileged Production باید مطابق ADR-0030 از کلید SSH مستقل و JIT استفاده شود؛ FIDO2 اختیاری است.

## ۷. firewall و جلوگیری از قطع دسترسی

در host و پنل ارائه‌دهنده، UDP endpoint انتخابی باید به سرور برسد. در host، SSH مدیریت باید
فقط روی interface/address WireGuard از peerهای مجاز پذیرفته شود؛ endpoint UDP دسترسی SSH عمومی نیست.
IPv4 و IPv6 و پورت‌های جایگزین SSH هم باید پوشش داده شوند. listener روی `0.0.0.0` به‌تنهایی
اثبات دسترسی اینترنتی نیست؛ firewall مؤثر و آزمون خارج VPN لازم است.

قوانین وابسته به میزبان‌اند؛ این راهنما rule کلی قابل paste روی همه nftablesها نمی‌دهد.
قبل از تغییر، ruleهای جاری و fail2ban/mail را بررسی و candidate را با `nft -c -f` اعتبارسنجی کنید.
یک rollback خودکار مستقل از نشست و مسیر بازیابی آزموده‌شده لازم است. فقط rule/table متعلق به نصب
را تغییر دهید؛ `flush ruleset` یا restore کل ruleset قدیمی ممکن است حفاظت سرویس‌های دیگر را خراب کند.
از نشست دوم SSH خصوصی را آزمون کنید؛ تا مسیر مدیریت و بازیابی تأیید نشده، public SSH را نبندید.
این bootstrap موقت را مدیریت Production تأییدشده اعلام نکنید.

### آزمایش محدود روی میزبان فعلی (پورت‌های انسانی 22 و 22022)

در بازرسی 2026-09-30، زنجیرهٔ زندهٔ `inet filter input` در handle 16 هر دو پورت را
بدون شرط WireGuard می‌پذیرفت. فایل `/etc/nftables.conf` همان پذیرش را دارد و
`nftables.service` در `ExecStop` کل ruleset را پاک می‌کند. بنابراین حذف handle 16،
`systemctl restart nftables` یا بارگذاری مجدد کل فایل، روش این آزمایش نیستند.
پورت 2222 متعلق به daemon جداگانهٔ tunnel است؛ این تغییر آن را باز نمی‌کند و
پیکربندی‌اش را دست‌کاری نمی‌کند. هدف این مرحله فقط محدودکردن مسیر SSH انسانی
روی 22 و 22022 است؛ بستن/تأیید همهٔ مسیرهای مدیریت و JIT/audit کارهای جداگانه‌اند.

کاندیدای مالکیت‌دار در `infrastructure/production/host/nftables-management-ssh.nft`
یک جدول مستقل با اولویت `-10` می‌سازد: loopback را حفظ می‌کند و ورودی غیر
`wg-hooshix` به 22/22022 را، برای IPv4 و IPv6، پیش از قانون پذیرش موجود رد می‌کند.
پذیرش در جدول جدید مجوز نهایی نیست؛ زنجیره‌های بعدی همچنان اجرا می‌شوند.

پیش از فعال‌سازی زنده، اپراتور باید هم‌زمان کنسول نجات مستقل VNC و نشست SSH
خصوصیِ دوم را آزموده و باز نگه دارد؛ SHA-256 فایل منتقل‌شده را با revision
بازبینی‌شده تطبیق دهد؛ و `sudo nft -c -f FILE` را بدون خطا اجرا کند.
یک timer بازگشت مستقل از نشست باید **قبل از** `sudo nft -f FILE` فعال و مشاهده شود.
بازگشت آزمایش فقط جدول `inet hooshix_management_ssh_guard` را حذف می‌کند؛
نباید ruleset مشترک یا قانون‌های mail/K3s را flush/restore کند. اگر timer یا
آزمون اولیه ناموفق شد، کاندیدا را فعال نکنید.

پس از فعال‌سازی موقت، از نشست SSH تازه روی `10.77.47.1:22022` اتصال را ثابت
کنید؛ از خارج مسیر WireGuard، ردشدن public TCP/22 و 22022 را مستقل بررسی کنید؛
و وضعیت سرویس‌های public، mail، K3s و tunnel 2222 را با baseline مقایسه کنید.
اگر یکی از اینها ناموفق یا مبهم بود، بگذارید timer بازگشت اجرا شود یا فقط جدول
اختصاصی را از کنسول حذف کنید و نتیجه را دوباره اندازه بگیرید. تا وقتی آزمون‌ها
و persistence امن در `/etc/nftables.conf` با `nft -c -f`، reboot و آزمون پس از
reboot تأیید نشده‌اند، timer را لغو و این گام را `Passed` اعلام نکنید. حتی پس از
آن، این guard به‌تنهایی الزامات bind-scope، SSH hardening، JIT و off-host audit
را برآورده نمی‌کند.

آزمون نهایی مدیریت: public SSH خارج VPN رد شود؛ peer نامعتبر/revoked رد شود؛ peer معتبر بدون
کلید SSH مجوز ورود نگیرد؛ SSH بدون JIT مجوز نوشتن نگیرد؛ expiry و audit خارج host کار کنند.
reboot فقط پس از آزمون rollback انجام شود و persistence دوباره بررسی شود.

### وضعیت تأییدشدهٔ همین VPS پس از reboot (2026-09-30)

مالک، نسخهٔ بازبینی‌شدهٔ PR #154 را با SHA-256
`d6b8829854a0ef74aaaf30951262e56fc6b1e2371af59ce6e88747888dd231fd`
در `/etc/nftables.d/hooshix-management-ssh.nft` نصب و از `/etc/nftables.conf`
include کرد. تنظیمات کامل نصب‌شده (`9eaaa0a2fef3b6088da179d01a8070787bbf12af07743ddab95132fb8c96ee63`)
آزمون `nft -c -f` را گذراند. پیش از فعال‌سازی، timer بازگشت مستقل ۳۰دقیقه‌ای
فعال بود. SSH تازه روی WireGuard وصل شد و TCP/22 و TCP/22022 عمومی از دستگاه
اپراتور رد شدند. پس از reboot موردتأیید مالک، `nftables.service` پیش از SSH
با موفقیت فایل اصلی را بارگذاری کرد؛ timer موقت دیگر فعال نبود، جدول محافظ
با همان قوانین باقی ماند، SSH خصوصی دوباره وصل شد و دو پورت انسانی عمومی
همچنان رد شدند. این شواهد برای **ماندگاری همین guard روی این VPS** `Passed` است.

قانون guard پورت tunnel مستقل 2222 را شامل نمی‌شود؛ daemon آن بعد از reboot
فعال و در حال listen بود. عدم دسترسی مستقیم عمومی به 2222 از دستگاه اپراتور
هم پیش و هم پس از تغییر مشاهده شد؛ این مشاهده، آزمون انتهابه‌انتهای tunnel
یا تضمین دسترس‌پذیری MCP نیست. فعال‌بودن سرویس‌های وب/ایمیل نیز به‌تنهایی
اثبات دسترسی خارجی آنها نیست. نسخهٔ پشتیبان پیکربندی قدیمی در
`/etc/nftables.conf.pre-hooshix-53ca3503` نگه داشته شده است؛ بازگرداندن آن
بدون guard معادل، TCP/22022 عمومی را دوباره باز می‌کند و rollback عادیِ امن
نیست. آزمون peer نامعتبر/لغوشده، bind و forwarding واقعی SSH، فایروال ارائه‌دهنده،
JIT، ممیزی خارج از میزبان و بازیابی هنوز `NOT VERIFIED` هستند؛ Stage 10 و
آمادگی Production تکمیل نشده‌اند.

## ۸. استفاده روزمره و عیب‌یابی

ابتدا tunnel را Activate، سپس alias SSH را اجرا کنید. برای قطع عمدی، از Windows Deactivate کنید؛
این کار نشست وابسته را قطع می‌کند. در رخداد VPN ناموفق، public SSH را خودکار باز نکنید.

در نشست مجاز سرور، این خروجی‌های محدود برای بررسی مفیدند:

```bash
systemctl is-active wg-quick@wg-hooshix
ip -brief address show wg-hooshix
sudo wg show wg-hooshix latest-handshakes
sudo wg show wg-hooshix transfer
```

peer public key، زمان و byte count خصوصی نیستند ولی موجودی مدیریت‌اند؛ انتشار عمومی لازم نیست.
handshake صفر یا قدیمی: endpoint، UDP پنل/host، فعال‌بودن Windows، public key و زمان دستگاه را بررسی کنید.
handshake تازه ولی SSH ناموفق: route `/32`، آدرس peer، SSH listener و firewall مدیریت را بررسی کنید.
SSH موفق ولی sudo ردشده: مشکل مجوز/احراز هویت مدیریتی است؛ بازنویسی VPN آن را حل نمی‌کند.
raw log را منتشر نکنید؛ فقط error دسته‌بندی‌شده و فاقد credential منتقل شود.

برای inventory محدود، فایل عمومی [inspect_host.py](../../scripts/production/inspect_host.py)
را از revision بررسی‌شده روی میزبان قرار دهید و hash آن را مستقل مقایسه کنید.
آن را با Python سیستم و `-I` اجرا کنید. با کاربر معمول فقط وضعیت سرویس‌ها؛ در نشست مدیریتی
مجاز، API readiness و تعداد containerهای ready/restart هر pod نیز خوانده می‌شود.
این ابزار هیچ resource/config/secret را تغییر نمی‌دهد و محتوای Secret، env، annotation یا log
را چاپ نمی‌کند. timeout هر command بیست ثانیه و retry صفر است؛ موفقیت inventory، Production approval نیست.
در اجرای root، علاوه بر مقدارهای سراسری SSH، نتیجهٔ `sshd -T -C` برای نمونهٔ ثابتِ
`hooshixadmin` از `10.77.47.2` به `10.77.47.1:22022` نیز به‌شکل allow-list گزارش می‌شود.
مقدار `host` در این نمونه همان IP عددی مشتری است، نه DNS reverse واقعی. در خروجی نسخهٔ ۲،
`probe_status` فقط موفقیت اجرای probe و `status` انطباق مقدارهای allow-list همین نمونه
با حداقل سیاست SSH را نشان می‌دهد. نسخهٔ ۱ فیلد `status` را صرفاً برای اجرای probe
استفاده می‌کرد و حتی با forwarding مجاز می‌توانست `Passed` نشان دهد. بنابراین حتی
`status: Passed` در نسخهٔ ۲ تأیید تنظیم مؤثر همهٔ کاربران، peerها،
نام‌های DNS، پورت‌ها یا daemon جداگانهٔ MCP روی 2222 نیست. تنظیم مؤثر اتصال واقعی و تست‌های
رد forwarding/authentication همچنان برای Production جداگانه لازم‌اند.
privileged execution همچنان نیازمند مسیر مصوب است؛ برای خودکارشدن آن `NOPASSWD: ALL` نسازید.

### پیش‌بررسی فقط‌خواندنی برای اصلاح SSH انسانی

پیش از اعمال policy فایل Git به VPS، منشأ واقعی listener را از خود میزبان پیدا کنید.
در Ubuntu ممکن است `ssh.socket` پورت‌ها را باز کند و `Port`/`ListenAddress` داخل
`sshd_config` به‌تنهایی listener زنده را جابه‌جا نکند. خروجی فقط‌خواندنی زیر،
تنظیمات مؤثر socket و جای فایل‌های override را نشان می‌دهد؛ آن را با وضعیت
`sshd -T -C` و listenerهای زنده مقایسه کنید. پورت tunnel مستقل 2222 فقط برای
تشخیص و آزمون عدم‌تغییر در فهرست است، نه هدف اصلاح SSH انسانی.

```bash
sudo systemctl show ssh.socket -p ActiveState -p FragmentPath -p DropInPaths -p Listen --no-pager
sudo systemctl cat ssh.socket ssh.service
sudo ss -H -ltnp '( sport = :22 or sport = :22022 or sport = :2222 )'
```

فایل `sshd_config` این repository را کورکورانه جایگزین فایل اصلی میزبان نکنید:
`Include`، ترتیب اولین مقدار مؤثر، `Match` و socket activation ابتدا باید روشن شوند.
روی VPS فعلی، `ssh.socket` از generator برای چهار listener آدرس‌های wildcard
IPv4/IPv6 در 22/22022 استفاده می‌کند. فایل اصلی در ابتدای کار drop-inها را
Include می‌کند و در انتها `Match User hooshixtunnel` دارد؛ drop-in مخصوص همان
کاربر اجازهٔ محدود TCP forwarding می‌دهد. پس `DisableForwarding yes` سراسری
یا قراردادن یک `Match` جدید در وسط Includeها بدون بررسی، راهکار امنی نیست.
اصلاح باید فقط به اتصال‌های SSH انسانی محدوده شود و تنظیم مؤثر tunnel و daemon
مستقل 2222 قبل/بعد یکسان بماند.
کاندیدای محدود همین میزبان در
[`sshd-human-match.tail`](../../infrastructure/production/host/sshd-human-match.tail)
قرار دارد. این فایل به‌تنهایی یک `sshd_config` کامل نیست و فقط باید پس از آخرین
`Match` فایل اصلی، روی یک نسخهٔ کاندیدا افزوده شود؛ نصب به‌صورت drop-in ابتدای
فایل مجاز نیست. افزودن حساب انسانی دیگر نیازمند بازبینی جداگانهٔ Match و آزمون
اتصال همان حساب است.
ابزار [`prepare_human_sshd_candidate.py`](../../scripts/production/prepare_human_sshd_candidate.py)
پس از دریافت hash تازهٔ `/etc/ssh/sshd_config`، یک کاندیدای `0600` در پوشهٔ
خصوصی متعلق به اپراتور می‌سازد و فایل اصلی را تغییر نمی‌دهد. اگر hash، آخرین
`Match`، محتوای template یا مجوز پوشه عوض شده باشد، متوقف می‌شود.
اجرای این ابزار نصب یا reload نیست؛ پیش از استفاده باید دو فایل عمومیِ بازبینی‌شده
با SHA-256 برابر revision PR به میزبان منتقل شوند و کاندیدا روی خود VPS با
`sshd -t -f` و `sshd -T -f ... -C ...` اعتبارسنجی شود.
پس از ساخت یک candidate محدود، `sshd -t -f CANDIDATE` و
`sshd -T -f CANDIDATE -C CONNECTION` باید همهٔ مسیرهای انسانی موردنیاز را
با مقادیر سخت‌گیرانه نشان دهند. تنها پس از وجود کنسول نجات، نشست خصوصی دوم،
نسخهٔ پشتیبان امن و timer rollback مستقل، اعمال زنده و آزمون SSH تازه مجاز است.
بازگشت نباید guard پایدار nftables را حذف یا TCP/22022 عمومی را باز کند؛ daemon
مستقل MCP روی TCP/2222 نیز نباید تغییر کند. موفقیت reload به‌تنهایی آزمون
ورود خصوصی/رد مسیر عمومی/ماندگاری پس از reboot نیست.

## ۹. دستگاه جدید، لغو و بازیابی

هر دستگاه public key و IP `/32` یکتا می‌گیرد؛ محدوده شبکه نصب باید از قبل ظرفیت آن را داشته باشد.
هر `[Peer]` مستقل است. هیچ دو peer نباید آدرس یا کلید مشترک داشته باشند.
پیش از لغو دستگاهی که با آن وصل هستید، از دستگاه مستقل مجاز اتصال مدیریت را آزمون کنید.

برای revoke، ابتدا peer عمومی دقیق را از فایل persistent بررسی‌شده حذف و تغییر را ثبت کنید؛
سپس در نشست مجاز، همان public key را از runtime حذف کنید:

```bash
sudo wg set wg-hooshix peer REPLACE_WITH_REVOKED_PUBLIC_KEY remove
```

`wg show wg-hooshix peers` باید نبود کلید را نشان دهد؛ از دستگاه revoked عدم اتصال و از
دستگاه باقی‌مانده اتصال را آزمون کنید. اگر فقط runtime عوض شود، reboot ممکن است peer را برگرداند.
کلید SSH آن دستگاه جداگانه revoke می‌شود. کلید گم‌شده را از مشتری/دستگاه دیگر کپی نکنید.

برای بازیابی host، version/kernel/package، config عمومی و موجودی peer را از منبع بررسی‌شده و
private key سرور را فقط از backup رمزدار مجاز بازیابی کنید؛ owner/mode و firewall را قبل از activation بررسی کنید.
اگر private key در معرض افشا بوده، کلید تازه بسازید و public key تازه را به همه دستگاه‌های مجاز برسانید.
برای Windows گم‌شده، peer قدیمی revoke و دستگاه جدید مستقل ثبت شود؛ reuse کلید ضرورت ندارد.
بازسازی، reboot، revoke و آزمون خارج VPN باید receipt واقعی داشته باشند؛ داشتن backup به‌تنهایی Passed نیست.

## منابع فنی

در ۲۰۲۶-۰۹-۲۷ با منابع رسمی زیر بازبینی شده است؛ نسخه نصب هدف همچنان باید ثبت شود:

```text
https://www.wireguard.com/quickstart/
https://www.wireguard.com/install/
https://git.zx2c4.com/wireguard-tools/about/src/man/wg.8
https://git.zx2c4.com/wireguard-tools/about/src/man/wg-quick.8
https://git.zx2c4.com/wireguard-windows/about/docs/enterprise.md
```

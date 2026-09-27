# WireGuard برای مدیریت HooshiX — راهنمای فارسی

این راهنما ساخت یک اتصال مدیریت مستقل برای نصب جدید و نگهداری اتصال موجود را پوشش می‌دهد.
مرجع امنیتی آن ADR-0030 و ADR-0043 است. برقرارشدن VPN تأیید Production نیست.

## ۱. به چه درد می‌خورد؟

WireGuard یک مسیر شبکه رمزدار بین رایانه مدیر و سرور است. به‌جای اینکه SSH و ابزار مدیریت
در دسترس همه اینترنت باشند، فقط دستگاه‌های ثبت‌شده به آدرس مدیریت سرور می‌رسند.
این VPN برای مدیریت است؛ اینترنت کاربران سایت از آن عبور نمی‌کند و مدل AI روی آن اجرا نمی‌شود.
کلید WireGuard فقط ورود به شبکه را ممکن می‌کند؛ مجوز SSH یا sudo یا Kubernetes نمی‌دهد.

مسیر مصوب: دستگاه مدیر → WireGuard → SSH/FIDO2 → دسترسی JIT محدود و audit خارج از میزبان.
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
IP مدیریت فعلی `10.77.47.1` است. فایل config محلی و `client.key` خصوصی‌اند؛ محتویاتشان را
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
برای privileged Production باید مطابق ADR-0030 از FIDO2/JIT استفاده شود.

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

آزمون نهایی مدیریت: public SSH خارج VPN رد شود؛ peer نامعتبر/revoked رد شود؛ peer معتبر بدون
FIDO2 مجوز SSH نگیرد؛ SSH بدون JIT مجوز نوشتن نگیرد؛ expiry و audit خارج host کار کنند.
reboot فقط پس از آزمون rollback انجام شود و persistence دوباره بررسی شود.

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
SSH/FIDO2 آن دستگاه جداگانه revoke می‌شود. کلید گم‌شده را از مشتری/دستگاه دیگر کپی نکنید.

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

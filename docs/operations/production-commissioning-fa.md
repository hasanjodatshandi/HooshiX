# اجرای امن سخت‌سازی Production

این بسته دو کنترل واقعی دارد:

1. `infrastructure/production/host/audit.rules` برای auditd است. رخدادهای SSH، اجرای process، تغییر privilege، sudo، تنظیمات K3s و metadata دیتابیس را ثبت می‌کند.
2. `infrastructure/production/host/rsyslog-audit-tls.conf.example` خروجی audit را با TLS به مقصد خارج از VPS می‌فرستد. تا زمانی که endpoint، نام TLS، CA و گواهی client از مسیر خصوصی provision نشده، این فایل نباید فعال شود.
3. `scripts/production/jit_grant.py` grant را فقط وقتی قبول می‌کند که دو هویت مستقل با `ssh-keygen -Y sign` آن را امضا کرده باشند و عمر آن حداکثر ۳۰ دقیقه باشد. بعد از verify، یک wrapper میزبان باید فقط scopeهای allow-list را در sudo policy موقت قرار دهد و در expiry آن را حذف کند.

## چرا endpoint و کلیدها را خودکار نساختیم؟

ساختن endpoint یا دو کلید روی همان VPS، audit خارجی یا تأیید دو نفره نیست. برای اجرای واقعی باید مالک سیستم این موارد را بیرون از VPS ایجاد کند:

- مقصد TLS خارج از VPS با retention و append-only یا دسترسی حذف‌شده برای operator؛
- CA و client certificate/key از مسیر secret manager یا فایل امن، نه Git و نه chat؛
- دو هویت مستقل approver و فایل `allowed_signers` متناظر؛
- mapping scope به commandهای محدود در sudoers.

پس از provision، تست باید شامل دریافت audit، قطع sink، برگشت sink، رد شدن grant منقضی، رد شدن grant با یک امضا، و حذف policy در expiry باشد. تا آن زمان `Production readiness = Not verified` باقی می‌ماند.

## WireGuard peer

راهنمای ساخت، ثبت، تطبیق fingerprint و revoke در `docs/operations/wireguard-management-fa.md` است. fingerprint عمومی دستگاه فعلی را با خروجی `wg show ... allowed-ips` سمت سرور تطبیق دهید؛ private key یا config کامل را منتشر نکنید.

## ترتیب اجرای روی VPS

1. از دو نشست WireGuard باز، یک نشست روی management address نگه دارید.
2. `sshd -t` و `sshd -T` را قبل از reload ذخیره کنید.
3. auditd و rsyslog TLS را نصب و با مقصد واقعی تست کنید.
4. ابتدا listener روی management address را در نشست دوم تست کنید؛ سپس listenerهای عمومی 22، 22022 و هر پورت SSH دیگر را ببندید.
5. `AllowAgentForwarding no`، `AllowTcpForwarding no`، `X11Forwarding no` و `PermitTunnel no` را در global و `Match`های مؤثر enforce کنید.
6. revoke یک peer آزمایشی را انجام دهید؛ peer اصلی را revoke نکنید.
7. قطع sink باید grant جدید را fail-closed کند، اما سرویس عادی را متوقف نکند.

این ترتیب عمداً بدون endpoint و هویت approver، claim موفقیت یا readiness نمی‌دهد.
